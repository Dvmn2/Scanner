package net.dvmn2.scanner.scan;

import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Строит единичные направления лучей, равномерно покрывающие сферу: кольцами
 * по широте, внутри каждого кольца — равномерно по долготе. Плотность
 * считается один раз от отображаемого радиуса, а не на каждом тике анимации.
 */
public final class RayDirections {

    // Целевое расстояние между соседними лучами на поверхности сферы, в
    // блоках уже уменьшенной модели. Меньше значение — плотнее сетка и
    // детальнее скан, но больше сущностей и нагрузка на сервер/клиент.
    private static final double POINT_SPACING = 1.0;

    private RayDirections() {
    }

    /**
     * @param maxDisplayRadius итоговый (уже уменьшенный scannerScale-ом) радиус модели
     */
    public static List<Vector3f> build(int maxDisplayRadius) {
        List<Vector3f> directions = new ArrayList<>();

        if (maxDisplayRadius <= 0) {
            // Модель настолько маленькая, что сканируем только точку сканера.
            directions.add(new Vector3f(0f, 0f, 0f));
            return directions;
        }

        int latitudeRings = Math.max(4, (int) Math.round((Math.PI * maxDisplayRadius) / POINT_SPACING));

        for (int lat = 0; lat <= latitudeRings; lat++) {
            double phi = (Math.PI * lat) / latitudeRings; // угол от полюса (0) до полюса (PI)
            double ringRadiusUnit = Math.sin(phi);
            double y = Math.cos(phi);

            // Точек в кольце пропорционально его длине окружности, чтобы
            // плотность была одинаковой у полюсов и у экватора.
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
