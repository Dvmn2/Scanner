package net.dvmn2.scanner.scan;

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
     * Спавнит один ItemDisplay заданного материала в указанной точке,
     * с равномерным масштабом {@code itemScale} по всем осям и без
     * поворотов и смещений.
     * <p>
     * Тень отключена — она ничего не добавляет к результату скана,
     * но заметно дороже для клиента при большом количестве сущностей.
     */
    public static void spawn(World world, Location spawnLocation, Material material, float itemScale) {
        ItemStack itemStack = new ItemStack(material);

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
