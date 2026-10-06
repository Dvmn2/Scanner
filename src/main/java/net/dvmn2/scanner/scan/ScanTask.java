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
 * Пошаговая анимация скана. На каждом тике все ещё "неразрешённые" лучи
 * продвигаются на {@link #RAY_STEP} блоков дальше от сканера и проверяются
 * на непустой блок / Interaction-сущность. Когда все лучи разрешены или
 * достигнут maxRadius — задача сама себя отменяет. Экземпляры независимы
 * друг от друга, поэтому несколько ScanTask могут выполняться параллельно.
 * <p>
 * Направления из {@link RayDirections} заданы в "локальной" системе координат
 * (x — вправо, y — вверх, z — вперёд) и сами по себе изотропны (покрывают
 * сферу целиком), поэтому вращать их "просто так" бессмысленно. Вместо этого
 * один и тот же локальный вектор направления проецируется в мир ДВАЖДЫ,
 * разными базисами:
 * <ul>
 *     <li>базисом сканера (его yaw/pitch на момент запуска) — чтобы понять,
 *     какой реальный блок в мире проверять;</li>
 *     <li>базисом дисплея (его yaw/pitch на момент запуска) — чтобы понять,
 *     куда в модели рядом с дисплеем поставить item-display/партикл.</li>
 * </ul>
 * За счёт этого то, что было "прямо перед" сканером, в модели оказывается
 * "прямо перед" дисплеем — модель разворачивается вместе с тем, куда сейчас
 * смотрит дисплей, независимо от фактической ориентации сканера и дисплея
 * в мировых координатах.
 */
public final class ScanTask extends BukkitRunnable {

    // Шаг продвижения луча за один тик. Меньше — точнее (не "перескакивает"
    // тонкие препятствия), но больше тиков на тот же радиус.
    private static final double RAY_STEP = 1.0;

    // Радиус поиска Interaction-сущностей: их bounding box не привязан к
    // сетке блоков, поэтому берём с запасом больше блока.
    private static final double INTERACTION_SEARCH_RADIUS = 1.5;

    // Радиус поиска ближайшего "scannable" item-display рядом с Interaction-сущностью.
    private static final double SCANNABLE_SEARCH_RADIUS = 3.0;

    private static final String SCANNABLE_TAG = "scannable";

    private final Location scannerBase;
    private final Location displayBase;
    private final World displayWorld;
    private final List<Vector3f> rayDirections;
    private final int maxRadius;
    private final int scannerScale;

    // Модель делится на подсетку displayScale³ ячеек на каждый исходный блок.
    private final int displayScale;
    private final ScanType type;

    // Базисы (право/верх/вперёд), в которые проецируются локальные направления
    // лучей — считаются один раз по yaw/pitch сканера и дисплея на момент
    // запуска скана (Location'ы scannerBase/displayBase их уже содержат).
    private final Basis scannerBasis;
    private final Basis displayBasis;

    private double currentRealDistance = RAY_STEP;

    // Лучи, уже нашедшие свой первый непустой блок — больше не проверяются.
    private final boolean[] resolved;

    // Занятые ячейки подсетки дисплея — чтобы два луча, попавшие в одну
    // ячейку модели после сжатия, не создавали два наложенных результата.
    private final Set<Long> occupiedDisplayCells = new HashSet<>();

    // Индексы лучей, ещё не проверенных на текущем currentRealDistance;
    // пересобирается заново на каждом новом радиусе.
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
        this.scannerBasis = Basis.fromLocation(scannerBase);
        this.displayBasis = Basis.fromLocation(displayBase);
    }

    @Override
    public void run() {
        if (currentRealDistance > maxRadius) {
            cancel();
            return;
        }

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

        // Все оставшиеся на этом шаге лучи проверяются за один вызов run(),
        // чтобы анимация продвигалась ровно на один радиус за тик.
        while (pendingCursor < pending.size()) {
            int rayIndex = pending.get(pendingCursor++);
            Vector3f direction = rayDirections.get(rayIndex);

            if (trySpawnFirstHit(direction, currentRealDistance)) {
                resolved[rayIndex] = true;
            }
        }

        pending = null;
        currentRealDistance += RAY_STEP;
    }

    /**
     * Проверяет точку, в которую луч с локальным направлением {@code direction}
     * упирается на расстоянии {@code distance} от сканера (спроецированную в
     * мир базисом сканера): сначала на Interaction-сущность, затем на обычный
     * непустой блок. Возвращает {@code true}, если луч разрешён (что-то найдено).
     */
    private boolean trySpawnFirstHit(Vector3f direction, double distance) {
        Vector3f scanOffset = scannerBasis.toWorld(direction).mul((float) distance);
        Location scanPoint = scannerBase.clone().add(scanOffset.x, scanOffset.y, scanOffset.z);

        Interaction interaction = findInteractionAt(scanPoint);
        if (interaction != null) {
            handleInteractionHit(direction, distance, interaction);
            return true;
        }

        Block block = scanPoint.getBlock();
        Material material = block.getType();

        if (material.isAir() || !material.isItem()) {
            return false; // ничего не нашли — луч летит дальше
        }

        CellPoint cell = computeCell(direction, distance);

        if (!occupiedDisplayCells.add(cell.key())) {
            return true; // ячейка уже занята другим лучом, дубликат не спавним
        }

        if (type == ScanType.PARTICLE) {
            double progress = distance / maxRadius;
            ScanParticles.spawn(displayWorld, cell.spawnLocation(), progress);
        } else {
            // Базовый масштаб 2f = размер целого блока при displayScale = 1;
            // при большем displayScale каждый item-display пропорционально
            // уменьшается, чтобы displayScale³ штук помещалось в блоке модели.
            float itemScale = 2f / displayScale;
            ItemStack itemStack = ScanDisplays.buildItemStack(material, null);
            ScanDisplays.spawn(displayWorld, cell.spawnLocation(), itemStack, itemScale);
        }

        return true;
    }

    /**
     * Обрабатывает попадание луча в Interaction-сущность вместо блока: в
     * режиме PARTICLE — красный партикл; в режиме DISPLAY — ищет ближайший
     * "scannable" item-display и рисует в модели его материал/визуал.
     */
    private void handleInteractionHit(Vector3f direction, double distance, Interaction interaction) {
        CellPoint cell = computeCell(direction, distance);

        if (!occupiedDisplayCells.add(cell.key())) {
            return;
        }

        if (type == ScanType.PARTICLE) {
            ScanParticles.spawnRed(displayWorld, cell.spawnLocation());
            return;
        }

        ItemDisplay scannable = findNearestScannableDisplay(interaction);
        if (scannable == null || scannable.getItemStack() == null) {
            return; // рядом нет размеченного "scannable" item-display — рисовать нечего
        }

        float itemScale = 2f / displayScale;
        ItemStack sourceItemStack = scannable.getItemStack();
        ItemStack itemStack = ScanDisplays.buildItemStack(sourceItemStack.getType(), sourceItemStack);
        ScanDisplays.spawn(displayWorld, cell.spawnLocation(), itemStack, itemScale);
    }

    /** Ищет Interaction-сущность, чей bounding box содержит точку {@code point}. */
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

    /** Цилиндрический hitbox Interaction-сущности: круглый в плане, вертикальный по высоте. */
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

    /** Ближайший к Interaction-сущности item-display с тегом {@value #SCANNABLE_TAG}. */
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
     * Переводит локальное направление луча {@code direction} на расстоянии
     * {@code distance} в координаты ячейки подсетки дисплея: направление
     * проецируется в мир базисом ДИСПЛЕЯ (а не сканера — в этом и есть
     * привязка модели к тому, куда сейчас смотрит дисплей), затем сжимается
     * в scannerScale раз и квантуется в ячейку размером 1/displayScale блока.
     */
    private CellPoint computeCell(Vector3f direction, double distance) {
        Vector3f displayOffset = displayBasis.toWorld(direction).mul((float) (distance / scannerScale));

        double cellSize = 1.0 / displayScale;

        long cellX = (long) Math.floor(displayOffset.x / cellSize);
        long cellY = (long) Math.floor(displayOffset.y / cellSize);
        long cellZ = (long) Math.floor(displayOffset.z / cellSize);

        long cellKey = cellKey(cellX, cellY, cellZ);

        Location spawnLocation = displayBase.clone().add(
                (cellX + 0.5) * cellSize,
                (cellY + 0.5) * cellSize,
                (cellZ + 0.5) * cellSize
        );

        return new CellPoint(cellKey, spawnLocation);
    }

    /** Упаковывает координаты ячейки (21 бит на ось) в long — для быстрого HashSet без лишних объектов. */
    private static long cellKey(long cellX, long cellY, long cellZ) {
        long x = cellX & 0x1FFFFFL;
        long y = cellY & 0x1FFFFFL;
        long z = cellZ & 0x1FFFFFL;
        return (x << 42) | (y << 21) | z;
    }

    private record CellPoint(long key, Location spawnLocation) {
    }

    /**
     * Ортонормированный базис "право/верх/вперёд", построенный по yaw/pitch
     * сущности — определяет, во что превращается локальное направление луча
     * (x — вправо, y — вверх, z — вперёд) при проекции в мировые координаты.
     */
    private record Basis(Vector3f right, Vector3f up, Vector3f forward) {

        /** Вектор направления взгляда по yaw/pitch — формула из Bukkit Location#getDirection(). */
        private static Vector3f forwardOf(Location location) {
            double yaw = Math.toRadians(location.getYaw());
            double pitch = Math.toRadians(location.getPitch());

            double x = -Math.sin(yaw) * Math.cos(pitch);
            double y = -Math.sin(pitch);
            double z = Math.cos(yaw) * Math.cos(pitch);

            return new Vector3f((float) x, (float) y, (float) z).normalize();
        }

        static Basis fromLocation(Location location) {
            Vector3f forward = forwardOf(location);

            // "Право" получаем как forward × worldUp. Если взгляд направлен
            // почти строго вверх/вниз, такой crossproduct вырождается в
            // нулевой вектор — тогда берём другую опорную ось.
            Vector3f worldUp = new Vector3f(0f, 1f, 0f);
            Vector3f right = new Vector3f(forward).cross(worldUp);
            if (right.lengthSquared() < 1e-6f) {
                right = new Vector3f(forward).cross(new Vector3f(0f, 0f, 1f));
            }
            right.normalize();

            Vector3f up = new Vector3f(right).cross(forward).normalize();

            return new Basis(right, up, forward);
        }

        /** local.x — вправо, local.y — вверх, local.z — вперёд (относительно этого базиса). */
        Vector3f toWorld(Vector3f local) {
            return new Vector3f(
                    right.x * local.x + up.x * local.y + forward.x * local.z,
                    right.y * local.x + up.y * local.y + forward.y * local.z,
                    right.z * local.x + up.z * local.y + forward.z * local.z
            );
        }
    }
}