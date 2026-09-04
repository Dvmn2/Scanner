package net.dvmn2.scanner.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.EntitySelectorArgumentResolver;

import net.dvmn2.scanner.scan.RayDirections;
import net.dvmn2.scanner.scan.ScanDisplays;
import net.dvmn2.scanner.scan.ScanTask;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.joml.Vector3f;

import java.util.List;

/**
 * Реализация команды {@code /scan}, зарегистрированной через Brigadier
 * (Paper Commands API, актуально для Paper 1.20.6+ / 1.21.x).
 * <p>
 * Синтаксис команды:
 * <pre>
 *     /scan <scanner> <display> <maxRadius> <scannerScale> <displayScale> <delayTicks>
 * </pre>
 * <ul>
 *     <li><b>scanner</b> — сущность, вокруг которой ведётся сканирование блоков;</li>
 *     <li><b>display</b> — сущность, рядом с которой строится уменьшенная модель;</li>
 *     <li><b>maxRadius</b> — максимальный радиус сканирования в реальных блоках;</li>
 *     <li><b>scannerScale</b> — во сколько раз уменьшается модель относительно оригинала
 *         (переносит реальные координаты блоков в координаты дисплея);</li>
 *     <li><b>displayScale</b> — во сколько раз повышается плотность ItemDisplay-сущностей
 *         за счёт их уменьшения: {@code displayScale = 1} — один item-display на блок модели,
 *         {@code displayScale = 2} — модель делится на подсетку 2×2×2 (8 item-display на блок),
 *         {@code displayScale = N} — подсетка N×N×N (N³ item-display на блок);</li>
 *     <li><b>delayTicks</b> — интервал (в тиках) между шагами анимации скана.</li>
 * </ul>
 * <p>
 * Алгоритм работы: от сканера во все стороны выпускаются лучи, равномерно
 * покрывающие сферу (см. {@link RayDirections}). Сама пошаговая анимация
 * и спавн модели реализованы в {@link ScanTask}.
 */
public final class ScanCommand {

    // Ссылка на плагин нужна, чтобы планировать повторяющуюся задачу
    // через Bukkit Scheduler (BukkitRunnable#runTaskTimer).
    private final JavaPlugin plugin;

    // Ссылка на текущий выполняющийся скан. Нужна, чтобы:
    //  1) отменить предыдущий незавершённый скан при повторном вызове команды;
    //  2) корректно остановить скан при выключении плагина (см. Scanner#onDisable).
    private BukkitTask activeScanTask;

    private static final int DEFAULT_MAX_RADIUS = 10;
    private static final int DEFAULT_SCANNER_SCALE = 1;
    private static final int DEFAULT_DISPLAY_SCALE = 1;
    private static final int DEFAULT_DELAY_TICKS = 1;

    public ScanCommand(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Отменяет текущий выполняющийся скан, если он ещё активен.
     * Безопасно вызывать, даже если скан уже завершён или не запускался.
     */
    public void cancelActiveScan() {
        if (activeScanTask != null && !activeScanTask.isCancelled()) {
            activeScanTask.cancel();
        }
    }

    /**
     * Строит дерево Brigadier-команды {@code /scan}.
     * Регистрируется в {@code Scanner#onEnable} через
     * {@code getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, ...)}.
     */
    public LiteralCommandNode<CommandSourceStack> create() {
        return Commands.literal("scan")
                // Команду может выполнять только тот, у кого есть право scan.admin
                // (обычно выдаётся операторам через права-плагин или permissions.yml).
                .requires(source -> source.getSender().hasPermission("scan.admin"))
                .then(Commands.argument("scanner", ArgumentTypes.entity())
                        .then(Commands.argument("display", ArgumentTypes.entity())
                                .executes(ctx -> run(ctx,
                                        DEFAULT_MAX_RADIUS, DEFAULT_SCANNER_SCALE,
                                        DEFAULT_DISPLAY_SCALE, DEFAULT_DELAY_TICKS))
                                .then(Commands.argument("maxRadius", IntegerArgumentType.integer(1))
                                        .executes(ctx -> run(ctx,
                                                IntegerArgumentType.getInteger(ctx, "maxRadius"),
                                                DEFAULT_SCANNER_SCALE, DEFAULT_DISPLAY_SCALE, DEFAULT_DELAY_TICKS))
                                        .then(Commands.argument("scannerScale", IntegerArgumentType.integer(1))
                                                .executes(ctx -> run(ctx,
                                                        IntegerArgumentType.getInteger(ctx, "maxRadius"),
                                                        IntegerArgumentType.getInteger(ctx, "scannerScale"),
                                                        DEFAULT_DISPLAY_SCALE, DEFAULT_DELAY_TICKS))
                                                .then(Commands.argument("displayScale", IntegerArgumentType.integer(1))
                                                        .executes(ctx -> run(ctx,
                                                                IntegerArgumentType.getInteger(ctx, "maxRadius"),
                                                                IntegerArgumentType.getInteger(ctx, "scannerScale"),
                                                                IntegerArgumentType.getInteger(ctx, "displayScale"),
                                                                DEFAULT_DELAY_TICKS))
                                                        .then(Commands.argument("delayTicks", IntegerArgumentType.integer(1))
                                                                .executes(ctx -> run(ctx,
                                                                        IntegerArgumentType.getInteger(ctx, "maxRadius"),
                                                                        IntegerArgumentType.getInteger(ctx, "scannerScale"),
                                                                        IntegerArgumentType.getInteger(ctx, "displayScale"),
                                                                        IntegerArgumentType.getInteger(ctx, "delayTicks")))
                                                        )
                                                )
                                        )
                                )
                        )
                )
                .build();
    }

    /**
     * Точка входа при выполнении команды: разбирает аргументы, готовит
     * общие данные (координаты, направления лучей) и запускает
     * периодическую задачу {@link ScanTask}, которая ведёт сам скан.
     */
    private int run(CommandContext<CommandSourceStack> ctx,
                    int maxRadius, int scannerScale, int displayScale, int delayTicks) throws CommandSyntaxException {
        CommandSender sender = ctx.getSource().getSender();

        EntitySelectorArgumentResolver scannerResolver =
                ctx.getArgument("scanner", EntitySelectorArgumentResolver.class);
        EntitySelectorArgumentResolver displayResolver =
                ctx.getArgument("display", EntitySelectorArgumentResolver.class);

        Entity scannerEntity = scannerResolver.resolve(ctx.getSource()).getFirst();
        Entity displayEntity = displayResolver.resolve(ctx.getSource()).getFirst();

        // Реальный скан идёт до maxRadius, но на дисплее модель сжимается
        // в scannerScale раз — это одновременно уменьшает видимый размер модели
        // и количество заспавненных ItemDisplay-сущностей.
        int maxDisplayRadius = (int) Math.max(0, Math.round(maxRadius / (double) scannerScale));

        // Снимаем координаты сущностей один раз, в момент запуска команды.
        // Скан идёт от зафиксированной точки, даже если сканер или дисплей
        // потом сдвинутся, — иначе анимация "плыла" бы вместе с игроком.
        Location scannerBase = scannerEntity.getLocation().clone();
        Location displayBase = displayEntity.getLocation().clone();
        World scannerWorld = scannerBase.getWorld();
        World displayWorld = displayBase.getWorld();

        if (scannerWorld == null || displayWorld == null) {
            sender.sendMessage(ChatColor.RED + "Не удалось определить мир сущностей.");
            return 0;
        }

        // Фиксированный набор направлений (единичных векторов), равномерно
        // покрывающих сферу. Плотность считается один раз, от отображаемого
        // радиуса, а не пересчитывается на каждом тике анимации.
        // Умножаем радиус на displayScale, чтобы плотность лучей была
        // достаточной для заполнения более мелкой подсетки item-display'ев
        // (иначе несколько лучей продолжали бы попадать в одну и ту же
        // укрупнённую ячейку, и displayScale не давал бы видимого эффекта).
        List<Vector3f> rayDirections = RayDirections.build(maxDisplayRadius * displayScale);

        // Если уже идёт предыдущий скан — останавливаем его перед запуском нового,
        // чтобы две анимации не спавнили сущности одновременно.
        cancelActiveScan();

        // Удаляем все ItemDisplay-сущности, оставшиеся от предыдущего скана,
        // прежде чем начинать спавнить новые.
        ScanDisplays.removeAll(displayWorld);

        sender.sendMessage(ChatColor.GREEN + "Скан запущен!");

        ScanTask task = new ScanTask(scannerBase, displayBase, displayWorld,
                rayDirections, maxRadius, scannerScale, displayScale);

        activeScanTask = task.runTaskTimer(plugin, 0L, delayTicks);

        return Command.SINGLE_SUCCESS;
    }
}
