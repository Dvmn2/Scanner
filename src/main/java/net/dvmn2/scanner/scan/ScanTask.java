package net.dvmn2.scanner.scan;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Периодическая задача, реализующая пошаговую анимацию скана.
 * <p>
 * На каждом тике (с интервалом delayTicks, задаваемым при регистрации через
 * {@link #runTaskTimer}) все ещё "неразрешённые" лучи продвигаются на
 * {@link #RAY_STEP} блоков дальше от сканера и проверяют, не наткнулись ли
 * они на непустой блок или на Interaction-сущность. Как только все лучи
 * разрешены или достигнут maxRadius — задача сама себя отменяет.
 * <p>
 * Экземпляры этого класса полностью независимы друг от друга (у каждого
 * своё состояние — resolved, occupiedDisplayCells и т.д.), поэтому
 * несколько ScanTask могут безопасно выполняться параллельно, если их
 * запустил {@code ScanCommand}.
 */
public final class ScanTask extends BukkitRunnable {

    // На сколько реальных блоков каждый луч продвигается вперёд за один тик
    // анимации. Меньшее значение делает скан более точным (не "перескакивает"
    // тонкие препятствия), но требует больше тиков для сканирования того же радиуса.
    private static final double RAY_STEP = 1.0;

    // Радиус поиска Interaction-сущностей вокруг точки луча. Немного больше
    // блока, так как bounding box Interaction-сущности не привязан к сетке
    // блоков и может быть смещён относительно точки проверки.
    private static final double INTERACTION_SEARCH_RADIUS = 1.5;

    // Радиус поиска ближайшего к Interaction-сущности item-display'я с
    // именем "scannable" — по нему определяется, каким предметом рисовать
    // найденную Interaction-сущность в модели.
    private static final double SCANNABLE_SEARCH_RADIUS = 3.0;

    // Имя, которым помечается "визуальный" item-display, привязанный к
    // Interaction-сущности (задаётся вручную при создании объекта на карте).
    private static final String SCANNABLE_TAG = "scannable";

    private final Location scannerBase;
    private final Location displayBase;
    private final World displayWorld;
    private final List<Vector3f> rayDirections;
    private final int maxRadius;
    private final int scannerScale;

    // Во сколько раз повышается плотность item-display'ев за счёт их
    // уменьшения: модель делится на подсетку displayScale³ ячеек на
    // каждый исходный блок дисплея (см. {@link #computeCell}).
    private final int displayScale;

    // Способ визуализации найденных блоков — партиклы или item-display'и.
    private final ScanType type;

    // Текущее реальное расстояние (в блоках) от сканера, на котором
    // проверяются все ещё активные лучи на этом шаге анимации.
    private double currentRealDistance = RAY_STEP;

    // Отмечает, какие лучи уже нашли свой первый непустой блок
    // и больше не нуждаются в проверке на следующих шагах.
    private final boolean[] resolved;

    // Ячейки подсетки дисплея (с учётом displayScale), в которых уже был
    // заспавнен ItemDisplay/партикл на этом скане — нужно, чтобы два разных
    // луча, попавших в одну и ту же ячейку модели после сжатия, не создавали
    // два наложенных друг на друга результата.
    private final Set<Long> occupiedDisplayCells = new HashSet<>();

    // Список индексов лучей, которые ещё нужно проверить на текущем
    // currentRealDistance, и позиция курсора в этом списке.
    // Пересобирается заново на каждом новом радиусе.
    private List<Integer> pending;
    private int pendingCursor;

    public ScanTask(Location scannerBase, Location displayBase, World displayWorld,
                    List<Vector3f> rayDirections, int maxRadius, int scannerScale, int displayScale,
                    ScanType type) {
        this.scannerBase = scannerBase;
        this.displayBase = displayBase;
        this.displayWorld = displayWorld;
        this.rayDirections = rayDirections;
        this.maxRadius = maxRadius;
        this.scannerScale = scannerScale;
        this.displayScale = displayScale;
        this.type = type;
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
     * Проверяет точку на смещении (dx, dy, dz) от сканера — сначала на
     * пересечение с Interaction-сущностью, затем (если пересечения нет)
     * на обычный непустой блок.
     * <p>
     * Если это Interaction-сущность — обрабатывается через
     * {@link #handleInteractionHit}, луч считается разрешённым.
     * Если это непустой блок с предметной формой — спавнится ItemDisplay
     * (или партикл) в соответствующей ячейке подсетки дисплея, и луч тоже
     * считается разрешённым. Если ни то ни другое — возвращается
     * {@code false}, и луч на следующем тике продвинется дальше.
     */
    private boolean trySpawnFirstHit(double dx, double dy, double dz) {
        Location scanPoint = scannerBase.clone().add(dx, dy, dz);

        // Сначала проверяем Interaction-сущности — они не привязаны к сетке
        // блоков и обычно не имеют собственного блока под собой (invisible
        // hitbox), поэтому их нужно проверять отдельно, до block-проверки.
        Interaction interaction = findInteractionAt(scanPoint);
        if (interaction != null) {
            handleInteractionHit(dx, dy, dz, interaction);
            return true;
        }

        Block block = scanPoint.getBlock();
        Material material = block.getType();

        if (material.isAir() || !material.isItem()) {
            return false; // ничего не нашли — луч летит дальше
        }

        CellPoint cell = computeCell(dx, dy, dz);

        if (!occupiedDisplayCells.add(cell.key())) {
            // Ячейка модели уже занята другим лучом — считаем луч разрешённым,
            // но не спавним дубликат сущности поверх уже существующей.
            return true;
        }

        if (type == ScanType.PARTICLE) {
            // Прогресс луча от сканера (0.0) до maxRadius (1.0) определяет
            // цвет партикла: синий вблизи сканера, зелёный к концу радиуса.
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double progress = distance / maxRadius;
            ScanParticles.spawn(displayWorld, cell.spawnLocation(), progress);
        } else {
            // Базовый масштаб 2f соответствует размеру целого блока
            // (при displayScale = 1). При большем displayScale каждый
            // item-display пропорционально уменьшается, чтобы ровно
            // displayScale³ штук помещалось в объёме одного блока модели.
            // renderSource = null: обычный блок из мира не несёт кастомных
            // компонентов предмета (custom_name/custom_model_data/item_model).
            float itemScale = 2f / displayScale;
            ItemStack itemStack = ScanDisplays.buildItemStack(material, null);
            ScanDisplays.spawn(displayWorld, cell.spawnLocation(), itemStack, itemScale);
        }

        return true;
    }

    /**
     * Обрабатывает попадание луча в Interaction-сущность вместо обычного
     * блока:
     * <ul>
     *     <li>в режиме {@link ScanType#PARTICLE} — спавнит красный партикл
     *         (чтобы визуально отличать такие точки от обычных блоков,
     *         окрашенных по градиенту синий→зелёный);</li>
     *     <li>в режиме {@link ScanType#DISPLAY} — ищет ближайший к этой
     *         Interaction-сущности item-display с именем "scannable" и
     *         спавнит в модели item-display того же материала — так
     *         Interaction-сущности (сами по себе невидимые) получают
     *         правильное визуальное представление в уменьшенной модели.</li>
     * </ul>
     */
    private void handleInteractionHit(double dx, double dy, double dz, Interaction interaction) {
        CellPoint cell = computeCell(dx, dy, dz);

        if (!occupiedDisplayCells.add(cell.key())) {
            return; // ячейка модели уже занята другим лучом
        }

        if (type == ScanType.PARTICLE) {
            ScanParticles.spawnRed(displayWorld, cell.spawnLocation());
            return;
        }

        ItemDisplay scannable = findNearestScannableDisplay(interaction);
        if (scannable == null || scannable.getItemStack() == null) {
            // Рядом нет размеченного "scannable" item-display'я — рисовать нечего.
            return;
        }

        // renderSource = scannable.getItemStack(): переносим с оригинального
        // предмета имя, custom_model_data и item_model, чтобы ресурспак
        // отрисовал заспавненный item-display так же, как оригинальный
        // "scannable"-объект на карте.
        float itemScale = 2f / displayScale;
        ItemStack sourceItemStack = scannable.getItemStack();
        ItemStack itemStack = ScanDisplays.buildItemStack(sourceItemStack.getType(), sourceItemStack);
        ScanDisplays.spawn(displayWorld, cell.spawnLocation(), itemStack, itemScale);
    }

    /**
     * Ищет Interaction-сущность, чей bounding box (ширина/высота,
     * заданные в самой сущности) содержит точку {@code point}.
     */
    private Interaction findInteractionAt(Location point) {
        World world = point.getWorld();
        if (world == null) {
            return null;
        }

        for (Entity entity : world.getNearbyEntities(point,
                INTERACTION_SEARCH_RADIUS, INTERACTION_SEARCH_RADIUS, INTERACTION_SEARCH_RADIUS)) {
            if (entity instanceof Interaction interaction && boundingBoxContains(interaction, point)) {
                return interaction;
            }
        }

        return null;
    }

    /**
     * Проверяет, попадает ли точка {@code point} внутрь bounding box
     * Interaction-сущности (цилиндрический hitbox: круглый в плане,
     * заданный шириной, и вертикальный, заданный высотой, — так же,
     * как это работает у ванильных Interaction-сущностей).
     */
    private static boolean boundingBoxContains(Interaction interaction, Location point) {
        Location base = interaction.getLocation();
        float halfWidth = interaction.getInteractionWidth() / 2f;
        float height = interaction.getInteractionHeight();

        double relX = point.getX() - base.getX();
        double relY = point.getY() - base.getY();
        double relZ = point.getZ() - base.getZ();

        return Math.abs(relX) <= halfWidth
                && Math.abs(relZ) <= halfWidth
                && relY >= 0
                && relY <= height;
    }

    /**
     * Ищет ближайший к Interaction-сущности item-display с именем
     * {@value #SCANNABLE_TAG} — предполагается, что такой item-display
     * вручную размещён рядом с Interaction-сущностью на карте, чтобы
     * задать ей визуальное представление (сама Interaction-сущность
     * невидима).
     */
    private ItemDisplay findNearestScannableDisplay(Interaction interaction) {
        World world = interaction.getWorld();
        Location interactionLocation = interaction.getLocation();

        ItemDisplay nearest = null;
        double nearestDistanceSquared = Double.MAX_VALUE;

        for (Entity entity : world.getNearbyEntities(interactionLocation,
                SCANNABLE_SEARCH_RADIUS, SCANNABLE_SEARCH_RADIUS, SCANNABLE_SEARCH_RADIUS)) {
            if (entity instanceof ItemDisplay display && display.getScoreboardTags().contains(SCANNABLE_TAG)) {
                double distanceSquared = display.getLocation().distanceSquared(interactionLocation);
                if (distanceSquared < nearestDistanceSquared) {
                    nearestDistanceSquared = distanceSquared;
                    nearest = display;
                }
            }
        }

        return nearest;
    }

    /**
     * Переводит реальное смещение (dx, dy, dz) от сканера в координаты
     * ячейки подсетки дисплея и мировую точку её центра.
     * <p>
     * Сначала точка сжимается в scannerScale раз — это переводит реальные
     * координаты в координаты модели. Затем, уже внутри модели, точка
     * дополнительно квантуется в ячейку подсетки размером 1/displayScale
     * блока: при displayScale = 1 это обычный блок модели, при
     * displayScale = N — блок модели делится на N×N×N ячеек, в каждую из
     * которых может попасть свой отдельный, пропорционально уменьшенный
     * item-display (или партикл).
     */
    private CellPoint computeCell(double dx, double dy, double dz) {
        double modelX = dx / scannerScale;
        double modelY = dy / scannerScale;
        double modelZ = dz / scannerScale;

        double cellSize = 1.0 / displayScale;

        long cellX = (long) Math.floor(modelX / cellSize);
        long cellY = (long) Math.floor(modelY / cellSize);
        long cellZ = (long) Math.floor(modelZ / cellSize);

        long cellKey = cellKey(cellX, cellY, cellZ);

        // Центр ячейки подсетки в мировых координатах — партиклы, как и
        // item-display'и, спавнятся именно здесь, на месте встреченного блока.
        Location spawnLocation = displayBase.clone().add(
                (cellX + 0.5) * cellSize,
                (cellY + 0.5) * cellSize,
                (cellZ + 0.5) * cellSize
        );

        return new CellPoint(cellKey, spawnLocation);
    }

    /**
     * Упаковывает координаты ячейки подсетки дисплея (уже с учётом
     * {@code displayScale}) в один {@code long} — для быстрого хранения
     * посещённых ячеек в {@link HashSet} без создания лишних объектов
     * {@link Location}/{@link Block} на каждое сравнение.
     * <p>
     * Ячейки считаются от точки дисплея, поэтому 21 бита на координату
     * с запасом хватает для любого разумного радиуса скана и displayScale.
     */
    private static long cellKey(long cellX, long cellY, long cellZ) {
        long x = cellX & 0x1FFFFFL; // 21 бит на координату X
        long y = cellY & 0x1FFFFFL; // 21 бит на координату Y
        long z = cellZ & 0x1FFFFFL; // 21 бит на координату Z
        return (x << 42) | (y << 21) | z;
    }

    /**
     * Пара "ключ ячейки подсетки" + "мировая точка её центра", чтобы не
     * пересчитывать оба значения отдельно в местах, где нужны сразу оба
     * (обычный блок и Interaction-сущность используют один и тот же расчёт).
     */
    private record CellPoint(long key, Location spawnLocation) {
    }
}