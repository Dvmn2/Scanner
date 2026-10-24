package net.dvmn2.scannerplugin.scan;

import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Builds unit ray directions that cover a sphere evenly: rings by latitude, evenly spaced by longitude within a
 * ring. Density depends on the model radius and is computed once per scan, not on every animation step.
 */
public final class RayDirections {

    /** Target distance between neighbouring rays on the sphere surface, in blocks of the shrunk model. */
    private static final double POINT_SPACING = 1.0;

    private static final int MIN_LATITUDE_RINGS = 4;

    private RayDirections() {
    }

    /**
     * Counts the directions {@link #build(int)} would return without allocating them.
     *
     * @param rayRadius model radius multiplied by the display scale
     * @return number of rays, at least 1, saturated at {@link Integer#MAX_VALUE}
     */
    public static int count(int rayRadius) {
        if (rayRadius <= 0) {
            return 1;
        }
        int rings = latitudeRings(rayRadius);
        long total = 0;
        for (int lat = 0; lat <= rings; lat++) {
            total += pointsInRing(Math.sin(Math.PI * lat / rings), rayRadius);
        }
        return (int) Math.min(total, Integer.MAX_VALUE);
    }

    /**
     * Builds the ray directions in the local frame: x is right, y is up, z is forward.
     *
     * @param rayRadius model radius multiplied by the display scale
     * @return unmodifiable list of unit vectors; a single zero vector when {@code rayRadius <= 0}
     */
    public static List<Vector3f> build(int rayRadius) {
        if (rayRadius <= 0) {
            // The model is too small to hold anything: scan only the scanner's own block.
            return List.of(new Vector3f(0f, 0f, 0f));
        }

        int rings = latitudeRings(rayRadius);
        List<Vector3f> directions = new ArrayList<>(count(rayRadius));

        for (int lat = 0; lat <= rings; lat++) {
            double phi = Math.PI * lat / rings; // angle from the pole (0) to the opposite pole (PI)
            double ringRadiusUnit = Math.sin(phi);
            double y = Math.cos(phi);
            int points = pointsInRing(ringRadiusUnit, rayRadius);

            for (int i = 0; i < points; i++) {
                double theta = 2 * Math.PI * i / points;
                directions.add(new Vector3f(
                        (float) (ringRadiusUnit * Math.cos(theta)),
                        (float) y,
                        (float) (ringRadiusUnit * Math.sin(theta))));
            }
        }

        return Collections.unmodifiableList(directions);
    }

    private static int latitudeRings(int rayRadius) {
        return Math.max(MIN_LATITUDE_RINGS, (int) Math.round(Math.PI * rayRadius / POINT_SPACING));
    }

    /** Points are proportional to the ring circumference so the density is the same at the poles and equator. */
    private static int pointsInRing(double ringRadiusUnit, int rayRadius) {
        return Math.max(1, (int) Math.round(2 * Math.PI * ringRadiusUnit * rayRadius / POINT_SPACING));
    }
}
