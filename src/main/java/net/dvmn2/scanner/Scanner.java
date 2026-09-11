package net.dvmn2.scanner;

import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;

import net.dvmn2.scanner.command.ScanCommand;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Главный класс плагина Scanner. Добавляет команду {@code /scan}, которая
 * "просвечивает" пространство вокруг сущности-сканера лучами и отображает
 * найденные блоки в виде уменьшенной 3D-модели рядом с сущностью-дисплеем
 * (партиклами или ItemDisplay-сущностями). Поддерживает произвольное число
 * одновременных сканирований и команды {@code /scan stop} / {@code /scan cancel}.
 */
public final class Scanner extends JavaPlugin {

    private ScanCommand scanCommand;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // Язык сообщений плагина берётся из config.yml (settings.language:
        // "ru" / "en" / "auto"). Должно быть выставлено до регистрации команды,
        // т.к. её статическое описание (для /help) берётся из Lang сразу при регистрации.
        Lang.setLanguage(getConfig().getString("settings.language"));

        scanCommand = new ScanCommand(this);

        // Регистрация команд в Paper 1.20.6+ идёт через Brigadier-based Commands API,
        // а не через plugin.yml. LifecycleEvents.COMMANDS срабатывает, когда сервер
        // готов принимать регистрацию команд.
        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Commands commands = event.registrar();
            commands.register(scanCommand.create(), Lang.getStatic(Lang.Key.COMMAND_DESCRIPTION));
        });

        getLogger().info("Scanner enabled!");
    }

    @Override
    public void onDisable() {
        // На случай выключения сервера посреди скана — останавливаем все задачи,
        // чтобы они не пытались спавнить сущности в уже выгружаемом мире.
        if (scanCommand != null) {
            scanCommand.stopAllScans();
        }

        getLogger().info("Scanner disabled!");
    }
}
