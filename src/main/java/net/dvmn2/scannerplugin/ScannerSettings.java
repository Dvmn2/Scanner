package net.dvmn2.scannerplugin;

import net.dvmn2.scannerplugin.scan.ScanRequest;

import org.bukkit.configuration.ConfigurationSection;

import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Immutable snapshot of {@code config.yml}. Invalid values are replaced with defaults and reported with a warning.
 *
 * @param language            {@code ru}, {@code en} or {@code auto}
 * @param defaultRadiusBlocks default scan radius, blocks
 * @param defaultScannerScale default scanner scale
 * @param defaultDisplayScale default display scale
 * @param defaultDelaySeconds default delay between scan steps, seconds
 * @param maxRadiusBlocks     maximum scan radius, blocks
 * @param maxDisplayScale     maximum display scale
 * @param maxRaysPerScan      maximum number of rays in one scan
 * @param maxActiveScans      maximum number of simultaneously running scans
 */
public record ScannerSettings(
        String language,
        int defaultRadiusBlocks,
        int defaultScannerScale,
        int defaultDisplayScale,
        double defaultDelaySeconds,
        int maxRadiusBlocks,
        int maxDisplayScale,
        int maxRaysPerScan,
        int maxActiveScans) {

    private static final String ROOT = "settings.";
    private static final Set<String> LANGUAGES = Set.of("ru", "en", "auto");

    private static final String DEFAULT_LANGUAGE = "auto";
    private static final int DEFAULT_RADIUS_BLOCKS = 10;
    private static final int DEFAULT_SCANNER_SCALE = 1;
    private static final int DEFAULT_DISPLAY_SCALE = 1;
    private static final double DEFAULT_DELAY_SECONDS = 0.05;
    private static final int DEFAULT_MAX_RADIUS_BLOCKS = 100;
    private static final int DEFAULT_MAX_DISPLAY_SCALE = 4;
    private static final int DEFAULT_MAX_RAYS_PER_SCAN = 50_000;
    private static final int DEFAULT_MAX_ACTIVE_SCANS = 8;

    private static final int HARD_MAX_SCANNER_SCALE = 100;
    private static final int HARD_MAX_RADIUS_BLOCKS = 1_000;
    private static final int HARD_MAX_DISPLAY_SCALE = 16;
    private static final int HARD_MAX_RAYS_PER_SCAN = 1_000_000;
    private static final int HARD_MAX_ACTIVE_SCANS = 64;

    public static ScannerSettings parse(ConfigurationSection config, Logger log) {
        String language = config.getString(ROOT + "language", DEFAULT_LANGUAGE).trim().toLowerCase(Locale.ROOT);
        if (!LANGUAGES.contains(language)) {
            log.warning("settings.language must be ru, en or auto, got " + language + "; using " + DEFAULT_LANGUAGE);
            language = DEFAULT_LANGUAGE;
        }

        int maxRadius = intInRange(config, log, "max-radius-blocks",
                DEFAULT_MAX_RADIUS_BLOCKS, 1, HARD_MAX_RADIUS_BLOCKS);
        int maxDisplayScale = intInRange(config, log, "max-display-scale",
                DEFAULT_MAX_DISPLAY_SCALE, 1, HARD_MAX_DISPLAY_SCALE);

        return new ScannerSettings(
                language,
                intInRange(config, log, "default-radius-blocks", DEFAULT_RADIUS_BLOCKS, 1, maxRadius),
                intInRange(config, log, "default-scanner-scale", DEFAULT_SCANNER_SCALE, 1, HARD_MAX_SCANNER_SCALE),
                intInRange(config, log, "default-display-scale", DEFAULT_DISPLAY_SCALE, 1, maxDisplayScale),
                doubleInRange(config, log, "default-delay-seconds", DEFAULT_DELAY_SECONDS,
                        ScanRequest.MIN_DELAY_SECONDS, ScanRequest.MAX_DELAY_SECONDS),
                maxRadius,
                maxDisplayScale,
                intInRange(config, log, "max-rays-per-scan", DEFAULT_MAX_RAYS_PER_SCAN, 1, HARD_MAX_RAYS_PER_SCAN),
                intInRange(config, log, "max-active-scans", DEFAULT_MAX_ACTIVE_SCANS, 1, HARD_MAX_ACTIVE_SCANS));
    }

    private static int intInRange(ConfigurationSection config, Logger log, String key, int fallback, int min,
                                  int max) {
        int value = config.getInt(ROOT + key, fallback);
        if (value >= min && value <= max) {
            return value;
        }
        // The fallback itself may lie outside a range narrowed by another key, so it is clamped too.
        int applied = Math.max(min, Math.min(max, fallback));
        log.warning("settings." + key + " must be in " + min + ".." + max + ", got " + value
                + "; using " + applied);
        return applied;
    }

    private static double doubleInRange(ConfigurationSection config, Logger log, String key, double fallback,
                                        double min, double max) {
        double value = config.getDouble(ROOT + key, fallback);
        if (!Double.isNaN(value) && value >= min && value <= max) {
            return value;
        }
        log.warning("settings." + key + " must be in " + min + ".." + max + ", got " + value
                + "; using " + fallback);
        return fallback;
    }
}
