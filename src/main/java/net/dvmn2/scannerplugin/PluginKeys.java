package net.dvmn2.scannerplugin;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

/** Factory of {@link NamespacedKey}s used by the plugin; built from the plugin instance. */
public final class PluginKeys {

    private final NamespacedKey scanDisplay;

    public PluginKeys(Plugin plugin) {
        this.scanDisplay = new NamespacedKey(plugin, "scan_display");
    }

    /** Marks an {@code ItemDisplay} created by a scan. */
    public NamespacedKey getScanDisplay() {
        return scanDisplay;
    }
}
