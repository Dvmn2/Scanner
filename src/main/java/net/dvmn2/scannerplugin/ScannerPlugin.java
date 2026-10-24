package net.dvmn2.scannerplugin;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;

import net.dvmn2.scannerplugin.command.ScannerCommand;
import net.dvmn2.scannerplugin.scan.ScanDisplayFactory;
import net.dvmn2.scannerplugin.scan.ScanManager;

import org.bukkit.plugin.java.JavaPlugin;

/** Composition root of the Scanner plugin: wires objects, registers handlers and stops running scans. */
public final class ScannerPlugin extends JavaPlugin {

    private static final String COMMAND_DESCRIPTION = "Scan the terrain around an entity and show it as a small model";

    private volatile ScannerSettings settings;
    private ScanManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadSettings();

        manager = new ScanManager(this, () -> settings, new ScanDisplayFactory(new PluginKeys(this)));
        manager.start();

        getServer().getPluginManager().registerEvents(new ScanListener(manager), this);

        ScannerCommand command = new ScannerCommand(this, manager, () -> settings);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(command.build(), COMMAND_DESCRIPTION));

        getLogger().info("Enabled. Language: " + settings.language() + ", max active scans: "
                + settings.maxActiveScans() + ", max rays per scan: " + settings.maxRaysPerScan() + ".");
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.shutdown();
        }
    }

    /** Re-reads {@code config.yml} and rebuilds the settings snapshot; running scans keep their parameters. */
    public void reloadAll() {
        reloadConfig();
        reloadSettings();
    }

    private void reloadSettings() {
        settings = ScannerSettings.parse(getConfig(), getLogger());
        Lang.setLanguage(settings.language());
    }
}
