package net.dvmn2.scanner.managers;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.List;

public class SelectorChecker {
    public static boolean isPlayers(List<Entity> entities) {
        for (Entity entity : entities) {
            if (!(entity instanceof Player)) {
                return false;
            }
        }
        return true;
    }

    public static boolean isMatch(List<Entity> entities, int count) {
        return entities.size() == count;
    }
}
