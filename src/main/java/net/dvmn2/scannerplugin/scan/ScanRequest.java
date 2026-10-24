package net.dvmn2.scannerplugin.scan;

import org.bukkit.Location;

/**
 * Parameters of one scan. The locations are snapshots taken at launch, so the animation does not drift when the
 * entities move. The locations are defensive copies and MUST NOT be mutated by callers.
 *
 * @param type         how results are rendered
 * @param scanner      origin of the rays, real world
 * @param display      origin of the model
 * @param maxRadius    scan radius in real blocks, at least 1
 * @param scannerScale how many times the model is smaller than the scanned area, at least 1
 * @param displayScale each model block is split into {@code displayScale^3} cells, at least 1
 * @param delaySeconds delay between animation steps, clamped to {@link #MIN_DELAY_SECONDS}..{@link #MAX_DELAY_SECONDS}
 */
public record ScanRequest(ScanType type, Location scanner, Location display, int maxRadius,
                          int scannerScale, int displayScale, double delaySeconds) {

    /** One server tick. */
    public static final double MIN_DELAY_SECONDS = 0.05;
    public static final double MAX_DELAY_SECONDS = 3600.0;

    private static final int TICKS_PER_SECOND = 20;

    public ScanRequest {
        scanner = scanner.clone();
        display = display.clone();
        maxRadius = Math.max(1, maxRadius);
        scannerScale = Math.max(1, scannerScale);
        displayScale = Math.max(1, displayScale);
        delaySeconds = Double.isNaN(delaySeconds)
                ? MIN_DELAY_SECONDS
                : Math.max(MIN_DELAY_SECONDS, Math.min(MAX_DELAY_SECONDS, delaySeconds));
    }

    /** Radius of the model in blocks: the scan radius shrunk by {@code scannerScale}. */
    public int modelRadius() {
        return (int) Math.max(0, Math.round(maxRadius / (double) scannerScale));
    }

    /** Radius used to size the ray set; multiplied by {@code displayScale} so rays fill the finer cell grid. */
    public int rayRadius() {
        return modelRadius() * displayScale;
    }

    /** Scheduler period, server ticks, at least 1. */
    public long delayTicks() {
        return Math.max(1L, Math.round(delaySeconds * TICKS_PER_SECOND));
    }
}
