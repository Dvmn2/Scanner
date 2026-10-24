package net.dvmn2.scannerplugin.scan;

/** Outcome of {@link ScanManager#begin(ScanRequest)}. */
public enum ScanResult {
    STARTED,
    WORLD_NOT_FOUND,
    TOO_MANY_SCANS,
    RADIUS_TOO_LARGE,
    DISPLAY_SCALE_TOO_LARGE,
    TOO_MANY_RAYS
}
