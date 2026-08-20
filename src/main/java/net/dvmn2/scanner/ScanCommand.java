package net.dvmn2.scanner;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
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
 * Команда /scan, зарегистрированная через Brigadier (Paper Commands API).
 * <p>
 * Дерево аргументов:
 * scan <scanner> <display> <maxRadius> <scale> <delayTicks>
 * <p>
 */
public final class ScanCommand {

    // ссылка на текущий выполняющийся скан, чтобы можно было отменить его
    // при повторном вызове команды
    private BukkitTask activeScanTask;

    // Кастомное имя, которым помечаются все спавнящиеся во время скана айтем-дисплеи
    private static final String SCAN_NAME = "scan";

    // Параметры анимации скана по умолчанию
    private static final double POINT_SPACING = 1.0;     // примерное расстояние между лучами на сфере (в отображаемых блоках)
    private static final double RAY_STEP = 1.0;          // на сколько реальных блоков луч продвигается за тик
    private static final int RAYS_PER_TICK = 1500;       // сколько лучей максимум проверяем за один тик

    private final JavaPlugin plugin;

    public ScanCommand(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Собирает дерево Brigadier-команды. Регистрируется в Scanner#onEnable через
     * getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, ...).
     */
    public LiteralCommandNode<CommandSourceStack> create() {
        return Commands.literal("scan")
                .then(Commands.argument("scanner", ArgumentTypes.entity())
                        .then(Commands.argument("display", ArgumentTypes.entity())
                                .then(Commands.argument("maxRadius", IntegerArgumentType.integer(1))
                                        .then(Commands.argument("scale", IntegerArgumentType.integer(1))
                                                .then(Commands.argument("delayTicks", IntegerArgumentType.integer(1))
                                                        .executes(ctx -> run(ctx, plugin))
                                                )
                                        )
                                )
                        )
                )
                .build();
    }

    private int run(CommandContext<CommandSourceStack> ctx, JavaPlugin plugin) throws CommandSyntaxException {
        CommandSender sender = ctx.getSource().getSender();

        if (!sender.hasPermission("scan.admin")) {
            sender.sendMessage(ChatColor.RED + "У вас нет прав на использование этой команды.");
            return 0;
        }

        EntitySelectorArgumentResolver scannerResolver =
                ctx.getArgument("scanner", EntitySelectorArgumentResolver.class);
        EntitySelectorArgumentResolver displayResolver =
                ctx.getArgument("display", EntitySelectorArgumentResolver.class);

        Entity scanner = scannerResolver.resolve(ctx.getSource()).getFirst();
        Entity display = displayResolver.resolve(ctx.getSource()).getFirst();
        int maxRadius = IntegerArgumentType.getInteger(ctx, "maxRadius");
        int scale = IntegerArgumentType.getInteger(ctx, "scale");
        int delayTicks = IntegerArgumentType.getInteger(ctx, "delayTicks");

        // Реальный скан идёт до maxRadius, но на дисплее он сжимается в scale раз —
        // это уменьшает и видимый размер, и количество заспавненных айтем-дисплеев
        int maxDisplayRadius = (int) Math.max(0, Math.round(maxRadius / scale));

        // Снимаем координаты один раз в момент запуска — скан идёт от этой точки,
        // даже если сущности потом сдвинутся
        Location scannerBase = scanner.getLocation().clone();
        Location displayBase = display.getLocation().clone();
        World scannerWorld = scannerBase.getWorld();
        World displayWorld = displayBase.getWorld();

        if (scannerWorld == null || displayWorld == null) {
            sender.sendMessage(ChatColor.RED + "Не удалось определить мир сущностей.");
            return 0;
        }

        // Фиксированный набор направлений (лучей), покрывающих сферу.
        // Плотность считается от отображаемого радиуса — не пересчитывается на каждом тике.
        final List<Vector3f> rayDirections = buildRayDirections(maxDisplayRadius);

        sender.sendMessage(ChatColor.GREEN + "Скан запущен!");

        // отменяем предыдущий незавершённый скан, если он ещё работает
        if (activeScanTask != null && !activeScanTask.isCancelled()) {
            activeScanTask.cancel();
        }

        final int finalMaxRadius = maxRadius;
        final double finalScale = scale;

        // Перед обновлением удаляем все ранее заспавненные айтем-дисплеи скана
        removeScanDisplays(displayWorld);

        activeScanTask = new BukkitRunnable() {
            double currentRealDistance = RAY_STEP;
            final boolean[] resolved = new boolean[rayDirections.size()];
            final Set<Long> occupiedDisplayBlocks = new HashSet<>();

            // индексы лучей, которые ещё нужно проверить на текущем currentRealDistance
            List<Integer> pending = null;
            int pendingCursor = 0;

            @Override
            public void run() {
                if (currentRealDistance > finalMaxRadius) {
                    cancel();
                    return;
                }

                // начинаем новый "проход" по текущему радиусу
                if (pending == null) {
                    pending = new ArrayList<>();
                    for (int i = 0; i < rayDirections.size(); i++) {
                        if (!resolved[i]) pending.add(i);
                    }
                    pendingCursor = 0;

                    if (pending.isEmpty()) {
                        cancel(); // все лучи уже нашли свои блоки — скан можно завершать досрочно
                        return;
                    }
                }

                int processed = 0;
                while (pendingCursor < pending.size() && processed < RAYS_PER_TICK) {
                    int i = pending.get(pendingCursor++);
                    processed++;

                    Vector3f dir = rayDirections.get(i);
                    double dx = dir.x * currentRealDistance;
                    double dy = dir.y * currentRealDistance;
                    double dz = dir.z * currentRealDistance;

                    if (trySpawnFirstHit(scannerBase, displayBase, displayWorld, dx, dy, dz,
                            finalScale, occupiedDisplayBlocks)) {
                        resolved[i] = true;
                    }
                }

                // проход по текущему радиусу закончен — переходим к следующему
                if (pendingCursor >= pending.size()) {
                    pending = null;
                    currentRealDistance += RAY_STEP;
                }
            }
        }.runTaskTimer(plugin, 0L, delayTicks);

        return Command.SINGLE_SUCCESS;
    }

    private static SuggestionProvider<CommandSourceStack> numberSuggestions(String... values) {
        return (ctx, builder) -> {
            String remaining = builder.getRemaining();
            for (String value : values) {
                if (value.startsWith(remaining)) {
                    builder.suggest(value);
                }
            }
            return builder.buildFuture();
        };
    }

    /**
     * Строит единичные (нормированные) направления лучей, равномерно покрывающие сферу,
     * с плотностью, рассчитанной под конечный отображаемый радиус.
     * Точки распределяются по широте (сверху вниз) и долготе (по кругу).
     */
    private List<Vector3f> buildRayDirections(int maxDisplayRadius) {
        List<Vector3f> directions = new ArrayList<>();

        if (maxDisplayRadius <= 0) {
            // Особый случай: сканируем только саму точку сканера
            directions.add(new Vector3f(0f, 0f, 0f));
            return directions;
        }

        int latitudeRings = Math.max(4, (int) Math.round((Math.PI * maxDisplayRadius) / POINT_SPACING));

        for (int lat = 0; lat <= latitudeRings; lat++) {
            // phi: 0 — верхний полюс, PI — нижний полюс
            double phi = (Math.PI * lat) / latitudeRings;
            double ringRadiusUnit = Math.sin(phi); // единичный (нормированный) радиус кольца
            double y = Math.cos(phi);

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
     * Проверяет реальный блок на расстоянии (dx, dy, dz) от сканера. Если это не воздух
     * и у блока есть предметная форма — спавнит айтем-дисплей в сжатой (dx/scale, dy/scale,
     * dz/scale) точке относительно дисплея и возвращает true (луч "нашёл" свой первый блок).
     * Если блока нет — возвращает false, и луч на следующем тике продвинется дальше.
     * Если после сжатия точка попадает в уже занятый другим лучом блок дисплея — луч всё
     * равно считается "разрешённым" (нашёл блок), но повторный айтем-дисплей не спавнится,
     * чтобы не создавать наложенные друг на друга сущности.
     */
    private boolean trySpawnFirstHit(Location scannerBase, Location displayBase, World displayWorld,
                                     double dx, double dy, double dz, double scale,
                                     Set<Long> occupiedDisplayBlocks) {
        Location scanPointLoc = scannerBase.clone().add(dx, dy, dz);
        Block block = scanPointLoc.getBlock();
        Material material = block.getType();

        if (material.isAir() || !material.isItem()) {
            return false; // ничего не нашли — луч летит дальше
        }

        Block spawnBlock = displayBase.clone().add(dx / scale, dy / scale, dz / scale).getBlock();
        long blockKey = blockKey(spawnBlock);

        if (!occupiedDisplayBlocks.add(blockKey)) {
            return true; // блок дисплея уже занят другим лучом — луч разрешён, но без дубликата
        }

        Location spawnLoc = spawnBlock.getLocation().add(0.5, 0.5, 0.5); // центрируем по блоку

        ItemStack itemStack = new ItemStack(material);

        displayWorld.spawn(spawnLoc, ItemDisplay.class, entity -> {
            entity.setItemStack(itemStack);
            entity.setCustomName(SCAN_NAME);
            entity.setCustomNameVisible(false);

            // Тень ничего не добавляет к результату скана, но заметно дороже для клиента —
            // отключаем её полностью
            entity.setShadowRadius(0f);
            entity.setShadowStrength(0f);

            Transformation transformation = new Transformation(
                    new Vector3f(0f, 0f, 0f),          // translation
                    new AxisAngle4f(0f, 0f, 0f, 1f),   // left rotation
                    new Vector3f(2f, 2f, 2f),          // scale — увеличено до размеров блока
                    new AxisAngle4f(0f, 0f, 0f, 1f)    // right rotation
            );
            entity.setTransformation(transformation);
            entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
        });

        return true;
    }

    /**
     * Упаковывает координаты блока в один long — для быстрого хранения в HashSet
     * без создания лишних объектов Location/Block на каждое сравнение.
     */
    private long blockKey(Block block) {
        long x = block.getX() & 0x3FFFFFFL; // 26 бит
        long y = block.getY() & 0xFFFL;      // 12 бит (с запасом на высоту мира)
        long z = block.getZ() & 0x3FFFFFFL; // 26 бит
        return (x << 38) | (y << 26) | z;
    }

    /**
     * Удаляет все айтем-дисплеи, помеченные тегом скана, в указанном мире.
     */
    private void removeScanDisplays(World world) {
        for (ItemDisplay display : world.getEntitiesByClass(ItemDisplay.class)) {
            if (SCAN_NAME.equals(display.getCustomName())) {
                display.remove();
            }
        }
    }
}