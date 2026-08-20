package net.dvmn2.scanner;

import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;

import org.bukkit.plugin.java.JavaPlugin;

public final class Scanner extends JavaPlugin {

    @Override
    public void onEnable() {
        // Сохранение конфигурации по умолчанию (если есть config.yml)
        saveDefaultConfig();

        ScanCommand scanCommand = new ScanCommand(this);

        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Commands commands = event.registrar();
            commands.register(
                    scanCommand.create(),
                    "Сканирует блок под сканером и создаёт item-display"
            );
        });

        getLogger().info("Scanner enabled!");
    }

    @Override
    public void onDisable() {
        getLogger().info("Scanner disabled!");
    }
}