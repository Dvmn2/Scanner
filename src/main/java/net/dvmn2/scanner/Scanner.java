package net.dvmn2.scanner;

import org.bukkit.plugin.java.JavaPlugin;

public final class Scanner extends JavaPlugin {

    @Override
    public void onEnable() {
        // Сохранение конфигурации по умолчанию (если есть config.yml)
        saveDefaultConfig();

        getCommand("scan").setExecutor(new ScanCommand(this));

        getLogger().info("Scanner enabled!");
    }

    @Override
    public void onDisable() {
        getLogger().info("Scanner disabled!");
    }
}
