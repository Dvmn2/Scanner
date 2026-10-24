package net.dvmn2.scannerplugin.scan;

import net.dvmn2.scannerplugin.ScannerSettings;

import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Owns the running scans and the displays they create. Main thread only.
 *
 * <p>Lifecycle: {@link #start()} removes leftovers, {@link #begin(ScanRequest)} launches scans,
 * {@link #shutdown()} stops everything and removes all scan displays.
 */
public final class ScanManager {

    private final JavaPlugin plugin;
    private final Supplier<ScannerSettings> settings;
    private final ScanDisplayFactory displays;
    private final List<ActiveScan> active = new ArrayList<>();

    public ScanManager(JavaPlugin plugin, Supplier<ScannerSettings> settings, ScanDisplayFactory displays) {
        this.plugin = plugin;
        this.settings = settings;
        this.displays = displays;
    }

    /** Removes scan displays left by a crash; nothing is tracked yet, so every marked display is an orphan. */
    public void start() {
        int removed = removeAllDisplays();
        if (removed > 0) {
            plugin.getLogger().warning("Removed " + removed + " orphaned scan display(s) left by a previous run.");
        }
    }

    /** Stops all scans and removes every scan display. */
    public void shutdown() {
        stopAll();
        removeAllDisplays();
    }

    /**
     * Validates the request against the configured limits and starts a scan.
     *
     * @return {@link ScanResult#STARTED} or the reason the scan was rejected
     */
    public ScanResult begin(ScanRequest request) {
        ScannerSettings limits = settings.get();

        if (request.scanner().getWorld() == null || request.display().getWorld() == null) {
            return ScanResult.WORLD_NOT_FOUND;
        }
        if (active.size() >= limits.maxActiveScans()) {
            return ScanResult.TOO_MANY_SCANS;
        }
        if (request.maxRadius() > limits.maxRadiusBlocks()) {
            return ScanResult.RADIUS_TOO_LARGE;
        }
        if (request.displayScale() > limits.maxDisplayScale()) {
            return ScanResult.DISPLAY_SCALE_TOO_LARGE;
        }
        // The count is computed without allocating, so an oversized scan is rejected before any memory is spent.
        if (RayDirections.count(request.rayRadius()) > limits.maxRaysPerScan()) {
            return ScanResult.TOO_MANY_RAYS;
        }

        List<Vector3f> rays = RayDirections.build(request.rayRadius());
        ActiveScan scan = new ActiveScan(new ScanTask(request, rays, displays));
        scan.schedule(request.delayTicks());
        active.add(scan);
        return ScanResult.STARTED;
    }

    public int getActiveCount() {
        return active.size();
    }

    /**
     * Stops every running scan; already spawned displays stay.
     *
     * @return number of scans that were running
     */
    public int stopAll() {
        int stopped = active.size();
        for (ActiveScan scan : List.copyOf(active)) {
            scan.cancel();
        }
        active.clear();
        return stopped;
    }

    /**
     * Removes every scan display from all loaded worlds. Displays are never persisted, so unloaded chunks
     * cannot hold any.
     *
     * @return number of removed displays
     */
    public int removeAllDisplays() {
        int removed = 0;
        for (World world : plugin.getServer().getWorlds()) {
            removed += removeMarked(world.getEntitiesByClass(ItemDisplay.class));
        }
        return removed;
    }

    /**
     * Removes the scan displays among the given entities.
     *
     * @return number of removed displays
     */
    public int removeMarked(Collection<? extends Entity> entities) {
        int removed = 0;
        for (Entity entity : entities) {
            if (displays.isScanDisplay(entity)) {
                entity.remove();
                removed++;
            }
        }
        return removed;
    }

    /** A scan task together with its scheduler handle. */
    private final class ActiveScan implements Runnable {

        private final ScanTask task;
        private BukkitTask handle;

        private ActiveScan(ScanTask task) {
            this.task = task;
        }

        private void schedule(long periodTicks) {
            handle = plugin.getServer().getScheduler().runTaskTimer(plugin, this, 0L, periodTicks);
        }

        private void cancel() {
            handle.cancel();
        }

        @Override
        public void run() {
            boolean running;
            try {
                running = task.step();
            } catch (RuntimeException ex) {
                // A broken scan must not spam the log every tick: report once and drop it.
                plugin.getLogger().log(Level.SEVERE, "Scan failed and was stopped", ex);
                running = false;
            }

            if (!running) {
                handle.cancel();
                active.remove(this);
            }
        }
    }
}
