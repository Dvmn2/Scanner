package net.dvmn2.scannerplugin.scan;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Step-by-step scan animation. Each {@link #step()} moves every still-unresolved ray {@link #RAY_STEP} blocks
 * further from the scanner and checks the reached point for an Interaction entity and then for a non-empty block.
 * The scan ends when every ray has hit something or the radius is exhausted. Main thread only; instances are
 * independent, so several scans can run in parallel.
 *
 * <p>Ray directions are given in a local frame (x right, y up, z forward) and cover the sphere evenly, so rotating
 * them is pointless. Instead each local direction is projected into the world twice, with different bases:
 * <ul>
 *     <li>the scanner basis (its yaw/pitch at launch) picks the real block to test;</li>
 *     <li>the display basis (its yaw/pitch at launch) picks where in the model the result is placed.</li>
 * </ul>
 * What was "straight ahead" of the scanner therefore ends up "straight ahead" of the display, whatever the
 * actual orientation of the two entities is.
 */
final class ScanTask {

    /** Ray advance per step, blocks. Smaller is more precise but needs more steps for the same radius. */
    private static final double RAY_STEP = 1.0;

    /** Extra margin when collecting Interaction entities, blocks; their hitbox may reach beyond their position. */
    private static final double INTERACTION_MARGIN_BLOCKS = 2.0;

    /** Search radius of the nearest "scannable" item display around an Interaction entity, blocks. */
    private static final double SCANNABLE_SEARCH_RADIUS = 3.0;

    private static final String SCANNABLE_TAG = "scannable";

    /** Item display scale that renders a whole block when the display scale is 1. */
    private static final float FULL_BLOCK_ITEM_SCALE = 2f;

    private static final float MIN_CROSS_LENGTH_SQUARED = 1e-6f;
    private static final int CELL_BITS = 21;
    private static final long CELL_MASK = (1L << CELL_BITS) - 1;
    private static final int CHUNK_SHIFT = 4;

    private final ScanRequest request;
    private final List<Vector3f> rays;
    private final ScanDisplayFactory displays;
    private final Location scannerBase;
    private final Location displayBase;
    private final Basis scannerBasis;
    private final Basis displayBasis;
    private final float itemScale;
    private final double cellSize;

    /** Rays that already found their first hit; they are not tested again. */
    private final boolean[] resolved;

    /** Interaction entities around the scanner, captured once at launch. */
    private final List<Target> targets;

    /** Occupied model cells, so two rays landing in one cell after shrinking produce a single result. */
    private final Set<Long> occupiedCells = new HashSet<>();

    private final Map<Material, ItemStack> blockItems = new EnumMap<>(Material.class);

    private double currentDistance = RAY_STEP;
    private int resolvedCount;

    ScanTask(ScanRequest request, List<Vector3f> rays, ScanDisplayFactory displays) {
        this.request = request;
        this.rays = rays;
        this.displays = displays;
        this.scannerBase = request.scanner();
        this.displayBase = request.display();
        this.scannerBasis = Basis.fromLocation(scannerBase);
        this.displayBasis = Basis.fromLocation(displayBase);
        this.itemScale = FULL_BLOCK_ITEM_SCALE / request.displayScale();
        this.cellSize = 1.0 / request.displayScale();
        this.resolved = new boolean[rays.size()];
        this.targets = collectTargets();
    }

    /**
     * Advances every unresolved ray by one step.
     *
     * @return true while the scan has more steps to do; false when it is finished or its world is gone
     */
    boolean step() {
        World scannerWorld = scannerBase.getWorld();
        World displayWorld = displayBase.getWorld();
        if (scannerWorld == null || displayWorld == null
                || !scannerBase.isWorldLoaded() || !displayBase.isWorldLoaded()) {
            return false;
        }

        for (int i = 0; i < rays.size(); i++) {
            if (!resolved[i] && castRay(scannerWorld, displayWorld, rays.get(i), currentDistance)) {
                resolved[i] = true;
                resolvedCount++;
            }
        }

        currentDistance += RAY_STEP;
        return currentDistance <= request.maxRadius() && resolvedCount < rays.size();
    }

    // ---------- Ray casting ----------

    /** Tests the point {@code distance} blocks along {@code direction}; returns true if the ray is resolved. */
    private boolean castRay(World scannerWorld, World displayWorld, Vector3f direction, double distance) {
        Vector3f offset = scannerBasis.toWorld(direction).mul((float) distance);
        double px = scannerBase.getX() + offset.x;
        double py = scannerBase.getY() + offset.y;
        double pz = scannerBase.getZ() + offset.z;

        Target target = findTarget(px, py, pz);
        if (target != null) {
            placeTargetHit(displayWorld, target, direction, distance);
            return true;
        }

        int blockX = (int) Math.floor(px);
        int blockY = (int) Math.floor(py);
        int blockZ = (int) Math.floor(pz);

        // Reading a block of an unloaded chunk would load it synchronously, so such points count as empty.
        if (!scannerWorld.isChunkLoaded(blockX >> CHUNK_SHIFT, blockZ >> CHUNK_SHIFT)) {
            return false;
        }

        Material material = scannerWorld.getType(blockX, blockY, blockZ);
        if (material.isAir() || !material.isItem()) {
            return false;
        }

        placeBlockHit(displayWorld, material, direction, distance);
        return true;
    }

    private void placeBlockHit(World displayWorld, Material material, Vector3f direction, double distance) {
        Cell cell = cellOf(direction, distance);
        if (!occupiedCells.add(cell.key())) {
            return;
        }

        if (request.type() == ScanType.PARTICLE) {
            ScanParticles.spawn(displayWorld, spawnLocation(cell), distance / request.maxRadius());
            return;
        }

        ItemStack item = blockItems.computeIfAbsent(material, key -> displays.createItem(key, null));
        displays.spawn(displayWorld, spawnLocation(cell), item, itemScale);
    }

    /**
     * Handles a hit on an Interaction entity: a red particle in particle mode, or, in display mode, the visual of
     * the nearest item display tagged {@value #SCANNABLE_TAG}.
     */
    private void placeTargetHit(World displayWorld, Target target, Vector3f direction, double distance) {
        Cell cell = cellOf(direction, distance);
        if (!occupiedCells.add(cell.key())) {
            return;
        }

        if (request.type() == ScanType.PARTICLE) {
            ScanParticles.spawnRed(displayWorld, spawnLocation(cell));
            return;
        }

        ItemStack item = renderItemOf(target);
        if (item != null) {
            displays.spawn(displayWorld, spawnLocation(cell), item, itemScale);
        }
    }

    // ---------- Interaction entities ----------

    private List<Target> collectTargets() {
        World world = scannerBase.getWorld();
        if (world == null) {
            return List.of();
        }

        double reach = request.maxRadius() + INTERACTION_MARGIN_BLOCKS;
        List<Target> result = new ArrayList<>();
        for (Entity entity : world.getNearbyEntities(scannerBase, reach, reach, reach,
                candidate -> candidate instanceof Interaction)) {
            if (entity instanceof Interaction interaction) {
                result.add(new Target(interaction.getLocation(), interaction.getInteractionWidth() / 2.0,
                        interaction.getInteractionHeight()));
            }
        }
        return result;
    }

    @Nullable
    private Target findTarget(double px, double py, double pz) {
        for (Target target : targets) {
            if (target.contains(px, py, pz)) {
                return target;
            }
        }
        return null;
    }

    /** Resolves, once per Interaction entity, the item that represents it in the model. */
    @Nullable
    private ItemStack renderItemOf(Target target) {
        if (!target.renderResolved) {
            target.renderResolved = true;
            ItemDisplay scannable = findNearestScannable(target.center);
            ItemStack source = scannable == null ? null : scannable.getItemStack();
            if (source != null && !source.getType().isAir()) {
                target.renderItem = displays.createItem(source.getType(), source);
            }
        }
        return target.renderItem;
    }

    @Nullable
    private static ItemDisplay findNearestScannable(Location center) {
        World world = center.getWorld();
        if (world == null) {
            return null;
        }

        ItemDisplay nearest = null;
        double nearestDistanceSquared = Double.MAX_VALUE;
        for (Entity entity : world.getNearbyEntities(center,
                SCANNABLE_SEARCH_RADIUS, SCANNABLE_SEARCH_RADIUS, SCANNABLE_SEARCH_RADIUS)) {
            if (entity instanceof ItemDisplay display && display.getScoreboardTags().contains(SCANNABLE_TAG)) {
                double distanceSquared = display.getLocation().distanceSquared(center);
                if (distanceSquared < nearestDistanceSquared) {
                    nearestDistanceSquared = distanceSquared;
                    nearest = display;
                }
            }
        }
        return nearest;
    }

    // ---------- Model cells ----------

    /**
     * Converts a local ray direction at {@code distance} into a cell of the display grid: the direction is
     * projected with the DISPLAY basis (this is what binds the model to where the display looks), shrunk by
     * {@code scannerScale} and quantised to cells of {@code 1 / displayScale} blocks.
     */
    private Cell cellOf(Vector3f direction, double distance) {
        Vector3f offset = displayBasis.toWorld(direction).mul((float) (distance / request.scannerScale()));
        return new Cell(
                (long) Math.floor(offset.x / cellSize),
                (long) Math.floor(offset.y / cellSize),
                (long) Math.floor(offset.z / cellSize));
    }

    private Location spawnLocation(Cell cell) {
        return displayBase.clone().add(
                (cell.x() + 0.5) * cellSize,
                (cell.y() + 0.5) * cellSize,
                (cell.z() + 0.5) * cellSize);
    }

    /** Cell coordinates relative to the display; {@link #key()} packs them into a long for a cheap hash set. */
    private record Cell(long x, long y, long z) {

        long key() {
            return ((x & CELL_MASK) << (2 * CELL_BITS)) | ((y & CELL_MASK) << CELL_BITS) | (z & CELL_MASK);
        }
    }

    /**
     * Snapshot of an Interaction entity. Its hitbox is a vertical cylinder-like box: square in plan, starting at
     * the entity position and going up by its height.
     */
    private static final class Target {

        private final Location center;
        private final double halfWidth;
        private final double height;
        private boolean renderResolved;
        @Nullable
        private ItemStack renderItem;

        private Target(Location center, double halfWidth, double height) {
            this.center = center;
            this.halfWidth = halfWidth;
            this.height = height;
        }

        private boolean contains(double px, double py, double pz) {
            double relY = py - center.getY();
            return Math.abs(px - center.getX()) <= halfWidth
                    && Math.abs(pz - center.getZ()) <= halfWidth
                    && relY >= 0
                    && relY <= height;
        }
    }

    /**
     * Orthonormal right/up/forward basis built from an entity's yaw and pitch. It decides what a local ray
     * direction becomes in world coordinates.
     */
    private record Basis(Vector3f right, Vector3f up, Vector3f forward) {

        static Basis fromLocation(Location location) {
            Vector3f forward = forwardOf(location);

            // right = forward x worldUp degenerates to zero when looking straight up or down,
            // so another reference axis is used in that case.
            Vector3f right = new Vector3f(forward).cross(new Vector3f(0f, 1f, 0f));
            if (right.lengthSquared() < MIN_CROSS_LENGTH_SQUARED) {
                right = new Vector3f(forward).cross(new Vector3f(0f, 0f, 1f));
            }
            right.normalize();

            Vector3f up = new Vector3f(right).cross(forward).normalize();
            return new Basis(right, up, forward);
        }

        /** Looking direction from yaw/pitch; the same formula as Bukkit's {@code Location#getDirection()}. */
        private static Vector3f forwardOf(Location location) {
            double yaw = Math.toRadians(location.getYaw());
            double pitch = Math.toRadians(location.getPitch());
            return new Vector3f(
                    (float) (-Math.sin(yaw) * Math.cos(pitch)),
                    (float) -Math.sin(pitch),
                    (float) (Math.cos(yaw) * Math.cos(pitch))).normalize();
        }

        /** Local x is right, y is up, z is forward relative to this basis. */
        Vector3f toWorld(Vector3f local) {
            return new Vector3f(
                    right.x * local.x + up.x * local.y + forward.x * local.z,
                    right.y * local.x + up.y * local.y + forward.y * local.z,
                    right.z * local.x + up.z * local.y + forward.z * local.z);
        }
    }
}
