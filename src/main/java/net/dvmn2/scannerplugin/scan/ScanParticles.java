package net.dvmn2.scannerplugin.scan;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;

/** Spawns the dust particles used by {@link ScanType#PARTICLE} scans. Main thread only. */
public final class ScanParticles {

    private static final int MAX_COLOR_COMPONENT = 255;
    private static final float DUST_SIZE = 1.0f;

    private ScanParticles() {
    }

    /**
     * Spawns a dust particle whose colour goes from green (near) to blue (far).
     *
     * @param progress distance of the hit relative to the scan radius; clamped to 0..1
     */
    public static void spawn(World world, Location location, double progress) {
        double clamped = Math.max(0.0, Math.min(1.0, progress));
        int green = (int) Math.round(MAX_COLOR_COMPONENT * (1 - clamped));
        int blue = (int) Math.round(MAX_COLOR_COMPONENT * clamped);
        spawnDust(world, location, Color.fromRGB(0, green, blue));
    }

    /** Spawns a red particle; it marks a ray that hit an Interaction entity instead of a block. */
    public static void spawnRed(World world, Location location) {
        spawnDust(world, location, Color.RED);
    }

    private static void spawnDust(World world, Location location, Color color) {
        world.spawnParticle(Particle.DUST, location, 1, 0, 0, 0, 0, new Particle.DustOptions(color, DUST_SIZE));
    }
}
