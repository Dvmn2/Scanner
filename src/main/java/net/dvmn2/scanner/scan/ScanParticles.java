package net.dvmn2.scanner.scan;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;

public class ScanParticles {
    public static void spawn(World world, Location location, double progress) {
        double clamped = Math.max(0.0, Math.min(1.0, progress));
        int red = 0;
        int green = (int) Math.round(255 * clamped);
        int blue = (int) Math.round(255 * (1 - clamped));
        Particle.DustOptions dustOptions =
                new Particle.DustOptions(Color.fromRGB(red, green, blue), 1.0f);
        world.spawnParticle(Particle.DUST, location, 1, 0, 0, 0, 0, dustOptions);
    }
}
