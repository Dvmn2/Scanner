package net.dvmn2.scanner.managers;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class SelectorTab {
    public static List<String> getSelectors() {
        return Arrays.asList("@s", "@a", "@p", "@r", "@e", "@n");
    }

    public static List<String> getEntities() {
        List<String> result = getPlayers();
        result.add("@s");
        result.add("@a");
        result.add("@p");
        result.add("@r");
        result.add("@e");
        result.add("@n");
        return result;
    }

    public static List<String> getPlayers() {
        List<String> result = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            result.add(p.getName());
        }
        return result;
    }
}
