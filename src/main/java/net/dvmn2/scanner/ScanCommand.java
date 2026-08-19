package net.dvmn2.scanner;

import net.dvmn2.scanner.managers.SelectorChecker;
import net.dvmn2.scanner.managers.SelectorParser;
import net.dvmn2.scanner.managers.SelectorTab;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.*;

public final class ScanCommand implements CommandExecutor, TabCompleter {

    // ссылка на текущий выполняющийся скан, чтобы можно было отменить его
    // при повторном вызове команды
    private BukkitTask activeScanTask;

    // Кастомное имя, которым помечаются все спавнящиеся во время скана айтем-дисплеи
    private static final String SCAN_NAME = "scan";

    // Параметры анимации скана по умолчанию (можно переопределить аргументами команды)
    private static final int DEFAULT_MAX_RADIUS = 10;    // максимальный радиус скана в реальном мире (в блоках)
    private static final double DEFAULT_SCALE = 1.0;     // во сколько раз сжимается отображение (1 = без сжатия)
    private static final long DEFAULT_DELAY_TICKS = 10L; // задержка между шагами (тиков)
    private static final double POINT_SPACING = 1.0;     // примерное расстояние между лучами на сфере (в отображаемых блоках)
    private static final double RAY_STEP = 1.0;          // на сколько реальных блоков луч продвигается за тик
    private static final int RAYS_PER_TICK = 1500;       // сколько лучей максимум проверяем за один тик

    private final JavaPlugin plugin;

    public ScanCommand(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("scan.admin")) {
            sender.sendMessage(ChatColor.RED + "У вас нет прав на использование этой команды.");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Использование: /scan <энтити-сканер> <энтити-дисплей> [макс.радиус] [сжатие] [задержка-тиков]");
            return true;
        }

        // Резолвим селекторы в энтити
        List<Entity> scannerTargets = SelectorParser.parse(sender, args[0]);

        if (scannerTargets.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "Сканер-энтити не найден по селектору: " + args[0]);
            return true;
        }

        if (!SelectorChecker.isMatch(scannerTargets, 1)) {
            sender.sendMessage(ChatColor.RED + "Сканер-энтити должен быть один: " + args[0]);
            return true;
        }

        Entity scannerEntity = scannerTargets.get(0);

        List<Entity> displayTargets = SelectorParser.parse(sender, args[1]);

        if (displayTargets.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "Дисплей-энтити не найден по селектору: " + args[1]);
            return true;
        }

        if (!SelectorChecker.isMatch(displayTargets, 1)) {
            sender.sendMessage(ChatColor.RED + "Дисплей-энтити должен быть один: " + args[1]);
            return true;
        }

        Entity displayEntity = displayTargets.get(0);

        int maxRadius = DEFAULT_MAX_RADIUS;
        double scale = DEFAULT_SCALE;
        long delayTicks = DEFAULT_DELAY_TICKS;

        try {
            if (args.length >= 3) maxRadius = Integer.parseInt(args[2]);
            if (args.length >= 4) scale = Double.parseDouble(args[3]);
            if (args.length >= 5) delayTicks = Long.parseLong(args[4]);
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "Макс.радиус, сжатие и задержка должны быть числами.");
            return true;
        }

        if (scale <= 0) {
            sender.sendMessage(ChatColor.RED + "Сжатие должно быть больше 0.");
            return true;
        }

        if (maxRadius < 1) {
            sender.sendMessage(ChatColor.RED + "Макс.радиус должен быть не меньше 1.");
            return true;
        }

        // Реальный скан идёт до maxRadius, но на дисплее он сжимается в scale раз —
        // это уменьшает и видимый размер, и количество заспавненных айтем-дисплеев
        int maxDisplayRadius = (int) Math.max(0, Math.round(maxRadius / scale));

        // Снимаем координаты один раз в момент запуска — скан идёт от этой точки,
        // даже если сущности потом сдвинутся
        Location scannerBase = scannerEntity.getLocation().clone();
        Location displayBase = displayEntity.getLocation().clone();
        World scannerWorld = scannerBase.getWorld();
        World displayWorld = displayBase.getWorld();

        if (scannerWorld == null || displayWorld == null) {
            sender.sendMessage(ChatColor.RED + "Не удалось определить мир сущностей.");
            return true;
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

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 1) {
            return SelectorTab.getSelectors();
        }
        if (args.length == 2) {
            return SelectorTab.getSelectors();
        }
        if (args.length == 3) {
            return Arrays.asList("10", "15", "20", "30", "50", "100", "150");
        }
        if (args.length == 4) {
            return Arrays.asList("1", "2", "4", "6", "8");
        }
        if (args.length == 5) {
            return Arrays.asList("1", "2", "4", "6", "8");
        }
        return Collections.emptyList();
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
