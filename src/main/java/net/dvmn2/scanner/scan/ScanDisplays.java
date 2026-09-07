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

/**
 * Утилита для создания и удаления ItemDisplay-сущностей, из которых
 * собирается уменьшенная 3D-модель результата скана.
 */
public final class ScanDisplays {

    // Метка (custom name), которой помечаются все ItemDisplay-сущности,
    // созданные во время скана. По ней их же потом легко найти и удалить
    // перед следующим запуском команды.
    public static final String SCAN_NAME = "scan";

    private ScanDisplays() {
        // Утилитный класс — создание экземпляров не предполагается.
    }

    /**
     * Удаляет все ItemDisplay-сущности, помеченные тегом скана
     * ({@link #SCAN_NAME}), в указанном мире. Вызывается перед стартом
     * нового скана, чтобы старая модель не оставалась висеть рядом с новой.
     */
    public static void removeAll(World world) {
        for (ItemDisplay display : world.getEntitiesByClass(ItemDisplay.class)) {
            if (SCAN_NAME.equals(display.getCustomName())) {
                display.remove();
            }
        }
    }

    /**
     * Строит {@link ItemStack} материала {@code material} для спавна в
     * модели, при необходимости перенося на него "визуальные" компоненты
     * (имя, custom_model_data, item_model) с исходного предмета
     * {@code renderSource} — это нужно, чтобы ресурспак применял к
     * заспавненному item-display'ю ту же кастомную модель/оверрайд, что и
     * у оригинального предмета (например, у "scannable"-дисплея,
     * привязанного к Interaction-сущности).
     * <p>
     * Если {@code renderSource == null} (обычный блок из мира без
     * кастомных компонентов) — возвращается просто чистый
     * {@code new ItemStack(material)}, как и раньше.
     */
    public static ItemStack buildItemStack(Material material, ItemStack renderSource) {
        ItemStack itemStack = new ItemStack(material);

        if (renderSource != null) {
            copyRenderComponents(renderSource, itemStack);
        }

        return itemStack;
    }

    /**
     * Переносит с {@code source} на {@code target} только те компоненты,
     * которые влияют на визуальное отображение предмета ресурспаком:
     * {@code minecraft:custom_name}, {@code minecraft:custom_model_data}
     * и {@code minecraft:item_model}. Остальные компоненты (например,
     * служебный {@code custom_data} плагинов, генерирующих ресурспак)
     * намеренно не копируются — они не нужны для отображения и могут
     * тянуть за собой лишние данные.
     * <p>
     * Компонент копируется, только если он реально присутствует на
     * {@code source} — иначе на предмете модели останется дефолтное
     * значение материала, как и должно быть.
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

    /**
     * Спавнит один ItemDisplay с заданным (уже полностью собранным, включая
     * визуальные компоненты — см. {@link #buildItemStack}) item stack'ом,
     * с равномерным масштабом {@code itemScale} по всем осям и без
     * поворотов и смещений.
     * <p>
     * Тень отключена — она ничего не добавляет к результату скана,
     * но заметно дороже для клиента при большом количестве сущностей.
     */
    public static void spawn(World world, Location spawnLocation, ItemStack itemStack, float itemScale) {
        world.spawn(spawnLocation, ItemDisplay.class, entity -> {
            entity.setItemStack(itemStack);
            entity.setCustomName(SCAN_NAME);
            entity.setCustomNameVisible(false);

            entity.setShadowRadius(0f);
            entity.setShadowStrength(0f);

            Transformation transformation = new Transformation(
                    new Vector3f(0f, 0f, 0f),        // translation — без смещения
                    new AxisAngle4f(0f, 0f, 0f, 1f),  // left rotation — без поворота
                    new Vector3f(itemScale, itemScale, itemScale), // scale — под размер ячейки
                    new AxisAngle4f(0f, 0f, 0f, 1f)   // right rotation — без поворота
            );
            entity.setTransformation(transformation);
            entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
        });
    }
}