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

            double dx = direction.x * currentRealDistance;
            double dy = direction.y * currentRealDistance;
            double dz = direction.z * currentRealDistance;

            if (trySpawnFirstHit(dx, dy, dz)) {
                resolved[rayIndex] = true;
            }
        }

        pending = null;
        currentRealDistance += RAY_STEP;
    }

    /**
     * Проверяет точку на смещении (dx, dy, dz) от сканера: сначала на
     * Interaction-сущность, затем на обычный непустой блок. Возвращает
     * {@code true}, если луч разрешён (что-то найдено).
     */
    private boolean trySpawnFirstHit(double dx, double dy, double dz) {
        Location scanPoint = scannerBase.clone().add(dx, dy, dz);

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
            return true; // ячейка уже занята другим лучом, дубликат не спавним
        }

        if (type == ScanType.PARTICLE) {
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
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
    private void handleInteractionHit(double dx, double dy, double dz, Interaction interaction) {
        CellPoint cell = computeCell(dx, dy, dz);

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
     * Переводит смещение (dx, dy, dz) от сканера в координаты ячейки подсетки
     * дисплея: сначала сжимается в scannerScale раз (реальные координаты →
     * координаты модели), затем квантуется в ячейку размером 1/displayScale блока.
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
}
