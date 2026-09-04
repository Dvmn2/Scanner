package net.dvmn2.scanner.scan;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.scheduler.BukkitRunnable;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Периодическая задача, реализующая пошаговую анимацию скана.
 * <p>
 * На каждом тике (с интервалом delayTicks, задаваемым при регистрации через
 * {@link #runTaskTimer}) все ещё "неразрешённые" лучи продвигаются на
 * {@link #RAY_STEP} блоков дальше от сканера и проверяют, не наткнулись ли
 * они на непустой блок. Как только все лучи разрешены или достигнут
 * maxRadius — задача сама себя отменяет.
 */
public final class ScanTask extends BukkitRunnable {

    // На сколько реальных блоков каждый луч продвигается вперёд за один тик
    // анимации. Меньшее значение делает скан более точным (не "перескакивает"
    // тонкие препятствия), но требует больше тиков для сканирования того же радиуса.
    private static final double RAY_STEP = 1.0;

    private final Location scannerBase;
    private final Location displayBase;
    private final World displayWorld;
    private final List<Vector3f> rayDirections;
    private final int maxRadius;
    private final int scannerScale;

    // Во сколько раз повышается плотность item-display'ев за счёт их
    // уменьшения: модель делится на подсетку displayScale³ ячеек на
    // каждый исходный блок дисплея (см. {@link #trySpawnFirstHit}).
    private final int displayScale;

    // Текущее реальное расстояние (в блоках) от сканера, на котором
    // проверяются все ещё активные лучи на этом шаге анимации.
    private double currentRealDistance = RAY_STEP;

    // Отмечает, какие лучи уже нашли свой первый непустой блок
    // и больше не нуждаются в проверке на следующих шагах.
    private final boolean[] resolved;

    // Ячейки подсетки дисплея (с учётом displayScale), в которых уже был
    // заспавнен ItemDisplay на этом скане — нужно, чтобы два разных луча,
    // попавших в одну и ту же ячейку модели после сжатия, не создавали
    // два наложенных друг на друга дисплея.
    private final Set<Long> occupiedDisplayCells = new HashSet<>();

    // Список индексов лучей, которые ещё нужно проверить на текущем
    // currentRealDistance, и позиция курсора в этом списке.
    // Пересобирается заново на каждом новом радиусе.
    private List<Integer> pending;
    private int pendingCursor;

    public ScanTask(Location scannerBase, Location displayBase, World displayWorld,
                     List<Vector3f> rayDirections, int maxRadius, int scannerScale, int displayScale) {
        this.scannerBase = scannerBase;
        this.displayBase = displayBase;
        this.displayWorld = displayWorld;
        this.rayDirections = rayDirections;
        this.maxRadius = maxRadius;
        this.scannerScale = scannerScale;
        this.displayScale = displayScale;
        this.resolved = new boolean[rayDirections.size()];
    }

    @Override
    public void run() {
        if (currentRealDistance > maxRadius) {
            cancel(); // достигли максимального радиуса — скан завершён
            return;
        }

        // Начинаем новый "проход" по текущему радиусу: собираем список
        // ещё не разрешённых лучей, которые нужно проверить на этом шаге.
        if (pending == null) {
            pending = new ArrayList<>();
            for (int i = 0; i < rayDirections.size(); i++) {
                if (!resolved[i]) {
                    pending.add(i);
                }
            }
            pendingCursor = 0;

            if (pending.isEmpty()) {
                cancel(); // все лучи уже нашли свои блоки — можно завершать досрочно
                return;
            }
        }

        // Проверяем все оставшиеся на этом шаге лучи за один вызов run(),
        // чтобы анимация продвигалась ровно на один радиус за один тик,
        // независимо от того, сколько лучей осталось активными.
        while (pendingCursor < pending.size()) {
            int rayIndex = pending.get(pendingCursor++);
            Vector3f direction = rayDirections.get(rayIndex);

            double dx = direction.x * currentRealDistance;
            double dy = direction.y * currentRealDistance;
            double dz = direction.z * currentRealDistance;

            if (trySpawnFirstHit(dx, dy, dz)) {
                resolved[rayIndex] = true;
            }
        }

        // Проход по текущему радиусу закончен — переходим к следующему.
        pending = null;
        currentRealDistance += RAY_STEP;
    }

    /**
     * Проверяет реальный блок на смещении (dx, dy, dz) от сканера.
     * Если это не воздух и у блока есть предметная форма — спавнит
     * ItemDisplay в соответствующей ячейке подсетки дисплея и
     * возвращает {@code true} (луч "нашёл" свой первый блок). Если
     * подходящего блока нет — возвращает {@code false}, и луч на
     * следующем тике продвинется дальше.
     * <p>
     * Сначала точка сжимается в scannerScale раз (как и раньше) —
     * это переводит реальные координаты в координаты модели. Затем,
     * уже внутри модели, точка дополнительно квантуется в ячейку
     * подсетки размером 1/displayScale блока: при displayScale = 1
     * это обычный блок модели (как раньше), при displayScale = N —
     * блок модели делится на N×N×N ячеек, в каждую из которых может
     * попасть свой отдельный, пропорционально уменьшенный item-display.
     */
    private boolean trySpawnFirstHit(double dx, double dy, double dz) {
        Location scanPoint = scannerBase.clone().add(dx, dy, dz);
        Block block = scanPoint.getBlock();
        Material material = block.getType();

        if (material.isAir() || !material.isItem()) {
            return false; // ничего не нашли — луч летит дальше
        }

        // Координаты точки в пространстве модели (после сжатия scannerScale),
        // относительно displayBase.
        double modelX = dx / scannerScale;
        double modelY = dy / scannerScale;
        double modelZ = dz / scannerScale;

        // Размер одной ячейки подсетки, в блоках модели.
        double cellSize = 1.0 / displayScale;

        // Индекс ячейки подсетки, в которую попадает точка.
        long cellX = (long) Math.floor(modelX / cellSize);
        long cellY = (long) Math.floor(modelY / cellSize);
        long cellZ = (long) Math.floor(modelZ / cellSize);

        long cellKey = cellKey(cellX, cellY, cellZ);

        if (!occupiedDisplayCells.add(cellKey)) {
            // Ячейка модели уже занята другим лучом — считаем луч разрешённым,
            // но не спавним дубликат сущности поверх уже существующей.
            return true;
        }

        // Центр ячейки подсетки в мировых координатах.
        Location spawnLocation = displayBase.clone().add(
                (cellX + 0.5) * cellSize,
                (cellY + 0.5) * cellSize,
                (cellZ + 0.5) * cellSize
        );

        // Базовый масштаб 2f соответствует размеру целого блока
        // (при displayScale = 1). При большем displayScale каждый
        // item-display пропорционально уменьшается, чтобы ровно
        // displayScale³ штук помещалось в объёме одного блока модели.
        float itemScale = 2f / displayScale;

        ScanDisplays.spawn(displayWorld, spawnLocation, material, itemScale);

        return true;
    }

    /**
     * Упаковывает координаты ячейки подсетки дисплея (уже с учётом
     * {@code displayScale}) в один {@code long} — для быстрого хранения
     * посещённых ячеек в {@link HashSet} без создания лишних объектов
     * {@link Location}/{@link Block} на каждое сравнение.
     * <p>
     * Ячейки считаются от точки дисплея, поэтому 21 бита на координату
     * с запасом хватает для любого разумного радиуса скана и displayScale.
     */
    private static long cellKey(long cellX, long cellY, long cellZ) {
        long x = cellX & 0x1FFFFFL; // 21 бит на координату X
        long y = cellY & 0x1FFFFFL; // 21 бит на координату Y
        long z = cellZ & 0x1FFFFFL; // 21 бит на координату Z
        return (x << 42) | (y << 21) | z;
    }
}
