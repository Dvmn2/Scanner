package net.dvmn2.scanner.scan;

import io.papermc.paper.datacomponent.DataComponentTypes;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/** Создание и удаление ItemDisplay-сущностей, из которых собирается модель скана. */
public final class ScanDisplays {

    // Метка (custom name) всех ItemDisplay, созданных сканом — по ней их
    // находят и удаляют перед следующим запуском / по /scan cancel.
    public static final String SCAN_NAME = "scan";

    private ScanDisplays() {
    }

    public static void removeAll(World world) {
        for (ItemDisplay display : world.getEntitiesByClass(ItemDisplay.class)) {
            if (SCAN_NAME.equals(display.getCustomName())) {
                display.remove();
            }
        }
    }

    /**
     * Собирает {@link ItemStack} материала {@code material}. Если задан
     * {@code renderSource} (например, "scannable"-предмет Interaction-сущности),
     * на новый предмет переносятся визуальные компоненты источника, чтобы
     * ресурспак отрисовал ту же кастомную модель.
     */
    public static ItemStack buildItemStack(Material material, ItemStack renderSource) {
        ItemStack itemStack = new ItemStack(material);

        if (renderSource != null) {
            copyRenderComponents(renderSource, itemStack);
        }

        return itemStack;
    }

    /**
     * Копирует только визуальные компоненты (custom_name, custom_model_data,
     * item_model) — остальные (например, служебный custom_data) намеренно
     * не переносятся, они не нужны для отображения.
     */
    private static void copyRenderComponents(ItemStack source, ItemStack target) {
        if (source.hasData(DataComponentTypes.CUSTOM_NAME)) {
            target.setData(DataComponentTypes.CUSTOM_NAME, source.getData(DataComponentTypes.CUSTOM_NAME));
        }

        if (source.hasData(DataComponentTypes.CUSTOM_MODEL_DATA)) {
            target.setData(DataComponentTypes.CUSTOM_MODEL_DATA, source.getData(DataComponentTypes.CUSTOM_MODEL_DATA));
        }

        if (source.hasData(DataComponentTypes.ITEM_MODEL)) {
            target.setData(DataComponentTypes.ITEM_MODEL, source.getData(DataComponentTypes.ITEM_MODEL));
        }
    }

    /** Спавнит один ItemDisplay без поворотов/смещений, с равномерным масштабом itemScale и без тени. */
    public static void spawn(World world, Location spawnLocation, ItemStack itemStack, float itemScale) {
        world.spawn(spawnLocation, ItemDisplay.class, entity -> {
            entity.setItemStack(itemStack);
            entity.setCustomName(SCAN_NAME);
            entity.setCustomNameVisible(false);

            entity.setShadowRadius(0f);
            entity.setShadowStrength(0f);

            Transformation transformation = new Transformation(
                    new Vector3f(0f, 0f, 0f),
                    new AxisAngle4f(0f, 0f, 0f, 1f),
                    new Vector3f(itemScale, itemScale, itemScale),
                    new AxisAngle4f(0f, 0f, 0f, 1f)
            );
            entity.setTransformation(transformation);
            entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
        });
    }
}
