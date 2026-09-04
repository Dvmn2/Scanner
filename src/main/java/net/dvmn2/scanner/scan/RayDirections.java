package net.dvmn2.scanner.scan;

import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Утилита для построения набора единичных (нормированных) направлений лучей,
 * равномерно покрывающих сферу.
 * <p>
 * Точки распределяются кольцами по широте (сверху вниз), а внутри каждого
 * кольца — равномерно по долготе. Плотность считается один раз, от
 * отображаемого радиуса, а не пересчитывается на каждом тике анимации.
 */
public final class RayDirections {

    // Примерное расстояние между соседними лучами на поверхности сферы,
    // в блоках отображаемой (уже уменьшенной) модели. Чем меньше значение —
    // тем плотнее сетка лучей и тем детальнее скан, но тем больше сущностей
    // будет заспавнено и тем дороже это для сервера/клиента.
    private static final double POINT_SPACING = 1.0;

    private RayDirections() {
        // Утилитный класс — создание экземпляров не предполагается.
    }

    /**
     * Строит единичные направления лучей, равномерно покрывающие сферу,
     * с плотностью, рассчитанной под конечный отображаемый радиус.
     *
     * @param maxDisplayRadius итоговый (уже уменьшенный scannerScale-ом) радиус модели
     */
    public static List<Vector3f> build(int maxDisplayRadius) {
        List<Vector3f> directions = new ArrayList<>();

        if (maxDisplayRadius <= 0) {
            // Особый случай: модель настолько маленькая, что сканируем
            // только саму точку сканера, без построения сферы направлений.
            directions.add(new Vector3f(0f, 0f, 0f));
            return directions;
        }

        // Количество "широтных" колец от полюса до полюса. Чем больше
        // радиус — тем больше колец нужно, чтобы сохранить плотность POINT_SPACING.
        int latitudeRings = Math.max(4, (int) Math.round((Math.PI * maxDisplayRadius) / POINT_SPACING));

        for (int lat = 0; lat <= latitudeRings; lat++) {
            // phi — угол от верхнего полюса (0) до нижнего полюса (PI).
            double phi = (Math.PI * lat) / latitudeRings;
            double ringRadiusUnit = Math.sin(phi); // радиус текущего кольца (в единичной сфере)
            double y = Math.cos(phi);              // высота текущего кольца

            // Число точек в кольце пропорционально его реальной длине окружности,
            // чтобы плотность точек была примерно одинаковой по всей сфере
            // (у полюсов колец меньше, у экватора — больше).
            int pointsInRing = Math.max(1,
                    (int) Math.round((2 * Math.PI * (ringRadiusUnit * maxDisplayRadius)) / POINT_SPACING));

            for (int i = 0; i < pointsInRing; i++) {
                double theta = (2 * Math.PI * i) / pointsInRing;
                double x = ringRadiusUnit * Math.cos(theta);
                double z = ringRadiusUnit * Math.sin(theta);
                directions.add(new Vector3f((float) x, (float) y, (float) z));
            }
        }

        return directions;
    }
}
