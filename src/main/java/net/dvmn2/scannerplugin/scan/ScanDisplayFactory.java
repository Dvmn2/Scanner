package net.dvmn2.scannerplugin.scan;

import io.papermc.paper.datacomponent.DataComponentType;
import io.papermc.paper.datacomponent.DataComponentTypes;

import net.dvmn2.scannerplugin.PluginKeys;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.jetbrains.annotations.Nullable;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * Creates and recognises the {@link ItemDisplay} entities a scan builds its model from. Main thread only.
 *
 * <p>Every display is marked with a {@link PluginKeys#getScanDisplay() PDC key} and is non-persistent, so it never
 * reaches the disk and disappears with its chunk.
 */
public final class ScanDisplayFactory {

    private static final byte MARK = 1;

    private final PluginKeys keys;

    public ScanDisplayFactory(PluginKeys keys) {
        this.keys = keys;
    }

    /**
     * Builds an item of the given material.
     *
     * @param renderSource when not {@code null}, its visual components (custom name, custom model data, item model)
     *                     are copied so a resource pack renders the same custom model; other components, such as
     *                     the service {@code custom_data}, are intentionally left out
     */
    public ItemStack createItem(Material material, @Nullable ItemStack renderSource) {
        ItemStack item = new ItemStack(material);
        if (renderSource != null) {
            copyComponent(DataComponentTypes.CUSTOM_NAME, renderSource, item);
            copyComponent(DataComponentTypes.CUSTOM_MODEL_DATA, renderSource, item);
            copyComponent(DataComponentTypes.ITEM_MODEL, renderSource, item);
        }
        return item;
    }

    /** Spawns one marked, shadowless display with a uniform scale and no extra rotation or offset. */
    public void spawn(World world, Location location, ItemStack item, float scale) {
        world.spawn(location, ItemDisplay.class, display -> {
            display.setItemStack(item);
            display.setPersistent(false);
            display.getPersistentDataContainer().set(keys.getScanDisplay(), PersistentDataType.BYTE, MARK);

            display.setShadowRadius(0f);
            display.setShadowStrength(0f);

            display.setTransformation(new Transformation(
                    new Vector3f(0f, 0f, 0f),
                    new AxisAngle4f(0f, 0f, 0f, 1f),
                    new Vector3f(scale, scale, scale),
                    new AxisAngle4f(0f, 0f, 0f, 1f)));
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
        });
    }

    /** Returns true if the entity is a display created by a scan. */
    public boolean isScanDisplay(Entity entity) {
        return entity instanceof ItemDisplay
                && entity.getPersistentDataContainer().has(keys.getScanDisplay(), PersistentDataType.BYTE);
    }

    private static <T> void copyComponent(DataComponentType.Valued<T> type, ItemStack source, ItemStack target) {
        T value = source.getData(type);
        if (value != null) {
            target.setData(type, value);
        }
    }
}
