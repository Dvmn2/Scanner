package net.dvmn2.scanner;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.EntitySelectorArgumentResolver;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Реализация команды {@code /scan}, зарегистрированной через Brigadier
 * (Paper Commands API, актуально для Paper 1.20.6+ / 1.21.x).
 * <p>
 * Синтаксис команды:
 * <pre>
 *     /scan <scanner> <display> <maxRadius> <scannerScale> <displayScale> <delayTicks>
 * </pre>
 * <ul>
 *     <li><b>scanner</b> — сущность, вокруг которой ведётся сканирование блоков;</li>
 *     <li><b>display</b> — сущность, рядом с которой строится уменьшенная модель;</li>
 *     <li><b>maxRadius</b> — максимальный радиус сканирования в реальных блоках;</li>
 *     <li><b>scannerScale</b> — во сколько раз уменьшается модель относительно оригинала
 *         (переносит реальные координаты блоков в координаты дисплея);</li>
 *     <li><b>displayScale</b> — во сколько раз повышается плотность ItemDisplay-сущностей
 *         за счёт их уменьшения: {@code displayScale = 1} — один item-display на блок модели,
 *         {@code displayScale = 2} — модель делится на подсетку 2×2×2 (8 item-display на блок),
 *         {@code displayScale = N} — подсетка N×N×N (N³ item-display на блок);</li>
 *     <li><b>delayTicks</b> — интервал (в тиках) между шагами анимации скана.</li>
 * </ul>
 * <p>
 * Алгоритм работы: от сканера во все стороны выпускаются лучи, равномерно
 * покрывающие сферу. На каждом тике каждый ещё "неразрешённый" луч
 * продвигается на {@link #RAY_STEP} блоков дальше и проверяет, не уткнулся
 * ли он в непустой блок. Как только луч находит первый непустой блок —
 * он "разрешается" (запоминается его материал), и рядом с сущностью-дисплеем
 * спавнится ItemDisplay в соответствующей уменьшенной точке.
 */
public final class ScanCommand {

    // Метка (custom name), которой помечаются все ItemDisplay-сущности,
    // созданные во время скана. По ней их же потом легко найти и удалить
    // перед следующим запуском команды.
    private static final String SCAN_NAME = "scan";

    // Примерное расстояние между соседними лучами на поверхности сферы,
    // в блоках отображаемой (уже уменьшенной) модели. Чем меньше значение —
    // тем плотнее сетка лучей и тем детальнее скан, но тем больше сущностей
    // будет заспавнено и тем дороже это для сервера/клиента.
    private static final double POINT_SPACING = 1.0;

    // На сколько реальных блоков каждый луч продвигается вперёд за один тик
    // анимации. Меньшее значение делает скан более точным (не "перескакивает"
    // тонкие препятствия), но требует больше тиков для сканирования того же радиуса.
    private static final double RAY_STEP = 1.0;

    // Ссылка на плагин нужна, чтобы планировать повторяющуюся задачу
    // через Bukkit Scheduler (BukkitRunnable#runTaskTimer).
    private final JavaPlugin plugin;

    // Ссылка на текущий выполняющийся скан. Нужна, чтобы:
    //  1) отменить предыдущий незавершённый скан при повторном вызове команды;
    //  2) корректно остановить скан при выключении плагина (см. Scanner#onDisable).
    private BukkitTask activeScanTask;

    public ScanCommand(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Отменяет текущий выполняющийся скан, если он ещё активен.
     * Безопасно вызывать, даже если скан уже завершён или не запускался.
     */
    public void cancelActiveScan() {
        if (activeScanTask != null && !activeScanTask.isCancelled()) {
            activeScanTask.cancel();
        }
    }

    /**
     * Строит дерево Brigadier-команды {@code /scan}.
     * Регистрируется в {@code Scanner#onEnable} через
     * {@code getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, ...)}.
     */
    public LiteralCommandNode<CommandSourceStack> create() {
        return Commands.literal("scan")
                // Команду может выполнять только тот, у кого есть право scan.admin
                // (обычно выдаётся операторам через права-плагин или permissions.yml).
                .requires(source -> source.getSender().hasPermission("scan.admin"))
                .then(Commands.argument("scanner", ArgumentTypes.entity())
                        .then(Commands.argument("display", ArgumentTypes.entity())
                                .then(Commands.argument("maxRadius", IntegerArgumentType.integer(1))
                                        .then(Commands.argument("scannerScale", IntegerArgumentType.integer(1))
                                                .then(Commands.argument("displayScale", IntegerArgumentType.integer(1))
                                                        .then(Commands.argument("delayTicks", IntegerArgumentType.integer(1))
                                                                .executes(this::run)
                                                        )
                                                )
                                        )
                                )
                        )
                )
                .build();
    }

    /**
     * Точка входа при выполнении команды: разбирает аргументы, готовит
     * общие данные (координаты, направления лучей) и запускает
     * периодическую задачу {@link ScanTask}, которая ведёт сам скан.
     */
    private int run(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSender sender = ctx.getSource().getSender();

        // Аргументы-селекторы сущностей (@e, @p, конкретное имя и т.д.)
        // резолвятся в конкретные сущности относительно источника команды.
        EntitySelectorArgumentResolver scannerResolver =
                ctx.getArgument("scanner", EntitySelectorArgumentResolver.class);
        EntitySelectorArgumentResolver displayResolver =
                ctx.getArgument("display", EntitySelectorArgumentResolver.class);

        Entity scannerEntity = scannerResolver.resolve(ctx.getSource()).getFirst();
        Entity displayEntity = displayResolver.resolve(ctx.getSource()).getFirst();

        int maxRadius = IntegerArgumentType.getInteger(ctx, "maxRadius");
        int scannerScale = IntegerArgumentType.getInteger(ctx, "scannerScale");
        int displayScale = IntegerArgumentType.getInteger(ctx, "displayScale");
        int delayTicks = IntegerArgumentType.getInteger(ctx, "delayTicks");

        // Реальный скан идёт до maxRadius, но на дисплее модель сжимается
        // в scannerScale раз — это одновременно уменьшает видимый размер модели
        // и количество заспавненных ItemDisplay-сущностей.
        int maxDisplayRadius = (int) Math.max(0, Math.round(maxRadius / (double) scannerScale));

        // Снимаем координаты сущностей один раз, в момент запуска команды.
        // Скан идёт от зафиксированной точки, даже если сканер или дисплей
        // потом сдвинутся, — иначе анимация "плыла" бы вместе с игроком.
        Location scannerBase = scannerEntity.getLocation().clone();
        Location displayBase = displayEntity.getLocation().clone();
        World scannerWorld = scannerBase.getWorld();
        World displayWorld = displayBase.getWorld();

        if (scannerWorld == null || displayWorld == null) {
            sender.sendMessage(ChatColor.RED + "Не удалось определить мир сущностей.");
            return 0;
        }

        // Фиксированный набор направлений (единичных векторов), равномерно
        // покрывающих сферу. Плотность считается один раз, от отображаемого
        // радиуса, а не пересчитывается на каждом тике анимации.
        // Умножаем радиус на displayScale, чтобы плотность лучей была
        // достаточной для заполнения более мелкой подсетки item-display'ев
        // (иначе несколько лучей продолжали бы попадать в одну и ту же
        // укрупнённую ячейку, и displayScale не давал бы видимого эффекта).
        List<Vector3f> rayDirections = buildRayDirections(maxDisplayRadius * displayScale);

        // Если уже идёт предыдущий скан — останавливаем его перед запуском нового,
        // чтобы две анимации не спавнили сущности одновременно.
        cancelActiveScan();

        // Удаляем все ItemDisplay-сущности, оставшиеся от предыдущего скана,
        // прежде чем начинать спавнить новые.
        removeScanDisplays(displayWorld);

        sender.sendMessage(ChatColor.GREEN + "Скан запущен!");

        ScanTask task = new ScanTask(scannerBase, displayBase, displayWorld,
                rayDirections, maxRadius, scannerScale, displayScale);

        activeScanTask = task.runTaskTimer(plugin, 0L, delayTicks);

        return Command.SINGLE_SUCCESS;
    }

    /**
     * Строит единичные (нормированные) направления лучей, равномерно
     * покрывающие сферу, с плотностью, рассчитанной под конечный
     * отображаемый радиус. Точки распределяются кольцами по широте
     * (сверху вниз), а внутри каждого кольца — равномерно по долготе.
     *
     * @param maxDisplayRadius итоговый (уже уменьшенный scannerScale-ом) радиус модели
     */
    private List<Vector3f> buildRayDirections(int maxDisplayRadius) {
        List<Vector3f> directions = new ArrayList<>();

        if (maxDisplayRadius <= 0) {
            // Особый случай: модель настолько маленькая, что сканируем
            // только саму точку сканера, без построения сферы направлений.
            directions.add(new Vector3f(0f, 0f, 0f));
            return directions;
        }

        // Количество "широтных" колец от полюса до полюса. Чем больше
        // радиус — тем больше колец нужно, чтобы сохранить плотность POINT_SPACING.
        int latitudeRings = Math.max(4, (int) Math.round((Math.PI * maxDisplayRadius) / POINT_SPACING));

        for (int lat = 0; lat <= latitudeRings; lat++) {
            // phi — угол от верхнего полюса (0) до нижнего полюса (PI).
            double phi = (Math.PI * lat) / latitudeRings;
            double ringRadiusUnit = Math.sin(phi); // радиус текущего кольца (в единичной сфере)
            double y = Math.cos(phi);              // высота текущего кольца

            // Число точек в кольце пропорционально его реальной длине окружности,
            // чтобы плотность точек была примерно одинаковой по всей сфере
            // (у полюсов колец меньше, у экватора — больше).
            int pointsInRing = Math.max(1,
                    (int) Math.round((2 * Math.PI * (ringRadiusUnit * maxDisplayRadius)) / POINT_SPACING));

            for (int i = 0; i < pointsInRing; i++) {
                double theta = (2 * Math.PI * i) / pointsInRing;
                double x = ringRadiusUnit * Math.cos(theta);
                double z = ringRadiusUnit * Math.sin(theta);
                directions.add(new Vector3f((float) x, (float) y, (float) z));
            }
        }

        return directions;
    }

    /**
     * Удаляет все ItemDisplay-сущности, помеченные тегом скана
     * ({@link #SCAN_NAME}), в указанном мире. Вызывается перед стартом
     * нового скана, чтобы старая модель не оставалась висеть рядом с новой.
     */
    private void removeScanDisplays(World world) {
        for (ItemDisplay display : world.getEntitiesByClass(ItemDisplay.class)) {
            if (SCAN_NAME.equals(display.getCustomName())) {
                display.remove();
            }
        }
    }

    /**
     * Упаковывает координаты ячейки подсетки дисплея (уже с учётом
     * {@code displayScale}) в один {@code long} — для быстрого хранения
     * посещённых ячеек в {@link HashSet} без создания лишних объектов
     * {@link Location}/{@link Block} на каждое сравнение.
     * <p>
     * В отличие от прежней версии (хранившей мировые координаты блока),
     * ячейки считаются от точки дисплея, поэтому 21 бита на координату
     * с запасом хватает для любого разумного радиуса скана и displayScale.
     */
    private static long cellKey(long cellX, long cellY, long cellZ) {
        long x = cellX & 0x1FFFFFL; // 21 бит на координату X
        long y = cellY & 0x1FFFFFL; // 21 бит на координату Y
        long z = cellZ & 0x1FFFFFL; // 21 бит на координату Z
        return (x << 42) | (y << 21) | z;
    }

    /**
     * Периодическая задача, реализующая пошаговую анимацию скана.
     * <p>
     * На каждом тике (с интервалом delayTicks) все ещё "неразрешённые" лучи
     * продвигаются на {@link #RAY_STEP} блоков дальше от сканера и проверяют,
     * не наткнулись ли они на непустой блок. Как только все лучи разрешены
     * или достигнут maxRadius — задача сама себя отменяет.
     */
    private static final class ScanTask extends BukkitRunnable {

        private final Location scannerBase;
        private final Location displayBase;
        private final World displayWorld;
        private final List<Vector3f> rayDirections;
        private final int maxRadius;
        private final int scannerScale;

        // Во сколько раз повышается плотность item-display'ев за счёт их
        // уменьшения: модель делится на подсетку displayScale³ ячеек на
        // каждый исходный блок дисплея (см. {@link #trySpawnFirstHit}).
        private final int displayScale;

        // Текущее реальное расстояние (в блоках) от сканера, на котором
        // проверяются все ещё активные лучи на этом шаге анимации.
        private double currentRealDistance = RAY_STEP;

        // Отмечает, какие лучи уже нашли свой первый непустой блок
        // и больше не нуждаются в проверке на следующих шагах.
        private final boolean[] resolved;

        // Ячейки подсетки дисплея (с учётом displayScale), в которых уже был
        // заспавнен ItemDisplay на этом скане — нужно, чтобы два разных луча,
        // попавших в одну и ту же ячейку модели после сжатия, не создавали
        // два наложенных друг на друга дисплея.
        private final Set<Long> occupiedDisplayCells = new HashSet<>();

        // Список индексов лучей, которые ещё нужно проверить на текущем
        // currentRealDistance, и позиция курсора в этом списке.
        // Пересобирается заново на каждом новом радиусе.
        private List<Integer> pending;
        private int pendingCursor;

        ScanTask(Location scannerBase, Location displayBase, World displayWorld,
                 List<Vector3f> rayDirections, int maxRadius, int scannerScale, int displayScale) {
            this.scannerBase = scannerBase;
            this.displayBase = displayBase;
            this.displayWorld = displayWorld;
            this.rayDirections = rayDirections;
            this.maxRadius = maxRadius;
            this.scannerScale = scannerScale;
            this.displayScale = displayScale;
            this.resolved = new boolean[rayDirections.size()];
        }

        @Override
        public void run() {
            if (currentRealDistance > maxRadius) {
                cancel(); // достигли максимального радиуса — скан завершён
                return;
            }

            // Начинаем новый "проход" по текущему радиусу: собираем список
            // ещё не разрешённых лучей, которые нужно проверить на этом шаге.
            if (pending == null) {
                pending = new ArrayList<>();
                for (int i = 0; i < rayDirections.size(); i++) {
                    if (!resolved[i]) {
                        pending.add(i);
                    }
                }
                pendingCursor = 0;

                if (pending.isEmpty()) {
                    cancel(); // все лучи уже нашли свои блоки — можно завершать досрочно
                    return;
                }
            }

            // Проверяем все оставшиеся на этом шаге лучи за один вызов run(),
            // чтобы анимация продвигалась ровно на один радиус за один тик,
            // независимо от того, сколько лучей осталось активными.
            while (pendingCursor < pending.size()) {
                int rayIndex = pending.get(pendingCursor++);
                Vector3f direction = rayDirections.get(rayIndex);

                double dx = direction.x * currentRealDistance;
                double dy = direction.y * currentRealDistance;
                double dz = direction.z * currentRealDistance;

                if (trySpawnFirstHit(dx, dy, dz)) {
                    resolved[rayIndex] = true;
                }
            }

            // Проход по текущему радиусу закончен — переходим к следующему.
            pending = null;
            currentRealDistance += RAY_STEP;
        }

        /**
         * Проверяет реальный блок на смещении (dx, dy, dz) от сканера.
         * Если это не воздух и у блока есть предметная форма — спавнит
         * ItemDisplay в соответствующей ячейке подсетки дисплея и
         * возвращает {@code true} (луч "нашёл" свой первый блок). Если
         * подходящего блока нет — возвращает {@code false}, и луч на
         * следующем тике продвинется дальше.
         * <p>
         * Сначала точка сжимается в scannerScale раз (как и раньше) —
         * это переводит реальные координаты в координаты модели. Затем,
         * уже внутри модели, точка дополнительно квантуется в ячейку
         * подсетки размером 1/displayScale блока: при displayScale = 1
         * это обычный блок модели (как раньше), при displayScale = N —
         * блок модели делится на N×N×N ячеек, в каждую из которых может
         * попасть свой отдельный, пропорционально уменьшенный item-display.
         */
        private boolean trySpawnFirstHit(double dx, double dy, double dz) {
            Location scanPoint = scannerBase.clone().add(dx, dy, dz);
            Block block = scanPoint.getBlock();
            Material material = block.getType();

            if (material.isAir() || !material.isItem()) {
                return false; // ничего не нашли — луч летит дальше
            }

            // Координаты точки в пространстве модели (после сжатия scannerScale),
            // относительно displayBase.
            double modelX = dx / scannerScale;
            double modelY = dy / scannerScale;
            double modelZ = dz / scannerScale;

            // Размер одной ячейки подсетки, в блоках модели.
            double cellSize = 1.0 / displayScale;

            // Индекс ячейки подсетки, в которую попадает точка.
            long cellX = (long) Math.floor(modelX / cellSize);
            long cellY = (long) Math.floor(modelY / cellSize);
            long cellZ = (long) Math.floor(modelZ / cellSize);

            long cellKey = cellKey(cellX, cellY, cellZ);

            if (!occupiedDisplayCells.add(cellKey)) {
                // Ячейка модели уже занята другим лучом — считаем луч разрешённым,
                // но не спавним дубликат сущности поверх уже существующей.
                return true;
            }

            // Центр ячейки подсетки в мировых координатах.
            Location spawnLocation = displayBase.clone().add(
                    (cellX + 0.5) * cellSize,
                    (cellY + 0.5) * cellSize,
                    (cellZ + 0.5) * cellSize
            );
            ItemStack itemStack = new ItemStack(material);

            // Базовый масштаб 2f соответствует размеру целого блока
            // (при displayScale = 1). При большем displayScale каждый
            // item-display пропорционально уменьшается, чтобы ровно
            // displayScale³ штук помещалось в объёме одного блока модели.
            float itemScale = 2f / displayScale;

            displayWorld.spawn(spawnLocation, ItemDisplay.class, entity -> {
                entity.setItemStack(itemStack);
                entity.setCustomName(SCAN_NAME);
                entity.setCustomNameVisible(false);

                // Тень ничего не добавляет к результату скана, но заметно дороже
                // для клиента при большом количестве сущностей — отключаем её.
                entity.setShadowRadius(0f);
                entity.setShadowStrength(0f);

                Transformation transformation = new Transformation(
                        new Vector3f(0f, 0f, 0f),        // translation — без смещения
                        new AxisAngle4f(0f, 0f, 0f, 1f),  // left rotation — без поворота
                        new Vector3f(itemScale, itemScale, itemScale), // scale — под размер ячейки
                        new AxisAngle4f(0f, 0f, 0f, 1f)   // right rotation — без поворота
                );
                entity.setTransformation(transformation);
                entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            });

            return true;
        }
    }
}