package net.dvmn2.scanner;

import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;

import net.dvmn2.scanner.command.ScanCommand;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Главный класс плагина Scanner.
 * <p>
 * Плагин добавляет команду {@code /scan}, которая "просвечивает" пространство
 * вокруг одной сущности (сканера) лучами и отображает найденные блоки в виде
 * уменьшенной 3D-модели рядом с другой сущностью (дисплеем), используя
 * ItemDisplay-сущности.
 * <p>
 * Точка входа стандартная для Paper-плагинов: {@link #onEnable()} вызывается
 * при запуске/перезагрузке сервера, {@link #onDisable()} — при остановке
 * или выгрузке плагина.
 */
public final class Scanner extends JavaPlugin {

    /**
     * Хранит ссылку на обработчик команды /scan, чтобы при необходимости
     * можно было обратиться к нему из других частей плагина (например,
     * для отмены активного скана при onDisable).
     */
    private ScanCommand scanCommand;

    @Override
    public void onEnable() {
        // Если рядом с jar-файлом плагина лежит config.yml (ресурс внутри jar),
        // он будет скопирован в папку плагина при первом запуске.
        // Если конфигурация не используется — вызов безвреден и ничего не делает.
        saveDefaultConfig();

        // Создаём обработчик команды /scan. Плагин передаётся в конструктор,
        // чтобы ScanCommand мог планировать асинхронные/тикающие задачи
        // через Bukkit Scheduler (BukkitRunnable#runTaskTimer и т.п.).
        scanCommand = new ScanCommand(this);

        // Регистрация команд в Paper 1.20.6+ выполняется через новое
        // Brigadier-based Commands API, а не через plugin.yml.
        // LifecycleEvents.COMMANDS срабатывает на этапе, когда сервер
        // готов принимать регистрацию команд.
        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Commands commands = event.registrar();

            // Регистрируем построенное дерево команды и её краткое описание,
            // которое будет показано, например, в /help.
            commands.register(
                    scanCommand.create(),
                    "Сканирует блоки вокруг сущности-сканера и отображает их в виде уменьшенной модели из item-display"
            );
        });

        getLogger().info("Scanner enabled!");
    }

    @Override
    public void onDisable() {
        // На случай, если сервер выключается посреди выполнения скана —
        // корректно останавливаем задачу, чтобы она не пыталась
        // спавнить сущности в уже выгружаемом мире.
        if (scanCommand != null) {
            scanCommand.cancelActiveScan();
        }

        getLogger().info("Scanner disabled!");
    }
}
