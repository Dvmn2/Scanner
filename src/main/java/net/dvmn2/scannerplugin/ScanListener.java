package net.dvmn2.scannerplugin;

import net.dvmn2.scannerplugin.scan.ScanManager;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;

/**
 * Removes orphaned scan displays when entities are loaded. Runs on the main thread.
 *
 * <p>Scan displays are never persisted, so a marked entity coming from disk can only be a leftover of a crash.
 */
public final class ScanListener implements Listener {

    private final ScanManager manager;

    public ScanListener(ScanManager manager) {
        this.manager = manager;
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        manager.removeMarked(event.getEntities());
    }
}
