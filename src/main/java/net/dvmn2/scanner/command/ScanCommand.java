package net.dvmn2.scanner.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.EntitySelectorArgumentResolver;

import net.dvmn2.scanner.Lang;
import net.dvmn2.scanner.scan.RayDirections;
import net.dvmn2.scanner.scan.ScanDisplays;
import net.dvmn2.scanner.scan.ScanTask;
import net.dvmn2.scanner.scan.ScanType;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Реализация команды {@code /scan} через Brigadier (Paper Commands API).
 * <pre>
 *     /scan <particle|display> <scanner> <display> [maxRadius] [scannerScale] [displayScale] [delayTicks]
 *     /scan stop
 *     /scan cancel
 * </pre>
 * scanner — сущность, вокруг которой ведётся сканирование; display — сущность,
 * рядом с которой строится модель; maxRadius — радиус скана в реальных блоках;
 * scannerScale — во сколько раз уменьшается модель; displayScale — на сколько
 * подъячеек делится каждый блок модели (displayScale³ item-display на блок);
 * delayTicks — интервал между шагами анимации.
 * <p>
 * {@code stop} останавливает все активные сканы, не трогая уже заспавненные
 * item-display'и. {@code cancel} останавливает сканы и дополнительно удаляет
 * все item-display'и скана. Несколько сканов могут выполняться параллельно —
 * каждый вызов запускает независимый {@link ScanTask}, не отменяя предыдущие.
 */
public final class ScanCommand {

    private static final int DEFAULT_MAX_RADIUS = 10;
    private static final int DEFAULT_SCANNER_SCALE = 1;
    private static final int DEFAULT_DISPLAY_SCALE = 1;
    private static final int DEFAULT_DELAY_TICKS = 1;

    private final JavaPlugin plugin;

    // Список всех выполняющихся сканов — нужен, чтобы массово остановить их
    // по /scan stop, /scan cancel и при выключении плагина (Scanner#onDisable).
    private final List<BukkitTask> activeScanTasks = new ArrayList<>();

    public ScanCommand(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Останавливает все выполняющиеся сканирования (item-display'и не удаляются).
     *
     * @return количество реально остановленных сканов
     */
    public int stopAllScans() {
        int stopped = 0;
        for (BukkitTask task : activeScanTasks) {
            if (!task.isCancelled()) {
                task.cancel();
                stopped++;
            }
        }
        activeScanTasks.clear();
        return stopped;
    }

    public LiteralCommandNode<CommandSourceStack> create() {
        return Commands.literal("scan")
                .requires(source -> source.getSender().hasPermission("scan.admin"))
                .then(Commands.literal("particle").then(scanArguments(ScanType.PARTICLE)))
                .then(Commands.literal("display").then(scanArguments(ScanType.DISPLAY)))
                .then(Commands.literal("stop").executes(this::stop))
                .then(Commands.literal("cancel").executes(this::cancel))
                .build();
    }

    /**
     * Общее поддерево аргументов {@code <scanner> <display> [maxRadius] [scannerScale]
     * [displayScale] [delayTicks]}, одинаковое для particle и display. Каждый следующий
     * числовой аргумент опционален и подставляет значение по умолчанию, если не указан.
     */
    private ArgumentBuilder<CommandSourceStack, ?> scanArguments(ScanType type) {
        return Commands.argument("scanner", ArgumentTypes.entity())
                .then(Commands.argument("display", ArgumentTypes.entity())
                        .executes(ctx -> run(ctx, type,
                                DEFAULT_MAX_RADIUS, DEFAULT_SCANNER_SCALE,
                                DEFAULT_DISPLAY_SCALE, DEFAULT_DELAY_TICKS))
                        .then(Commands.argument("maxRadius", IntegerArgumentType.integer(1))
                                .executes(ctx -> run(ctx, type,
                                        IntegerArgumentType.getInteger(ctx, "maxRadius"),
                                        DEFAULT_SCANNER_SCALE, DEFAULT_DISPLAY_SCALE, DEFAULT_DELAY_TICKS))
                                .then(Commands.argument("scannerScale", IntegerArgumentType.integer(1))
                                        .executes(ctx -> run(ctx, type,
                                                IntegerArgumentType.getInteger(ctx, "maxRadius"),
                                                IntegerArgumentType.getInteger(ctx, "scannerScale"),
                                                DEFAULT_DISPLAY_SCALE, DEFAULT_DELAY_TICKS))
                                        .then(Commands.argument("displayScale", IntegerArgumentType.integer(1))
                                                .executes(ctx -> run(ctx, type,
                                                        IntegerArgumentType.getInteger(ctx, "maxRadius"),
                                                        IntegerArgumentType.getInteger(ctx, "scannerScale"),
                                                        IntegerArgumentType.getInteger(ctx, "displayScale"),
                                                        DEFAULT_DELAY_TICKS))
                                                .then(Commands.argument("delayTicks", IntegerArgumentType.integer(1))
                                                        .executes(ctx -> run(ctx, type,
                                                                IntegerArgumentType.getInteger(ctx, "maxRadius"),
                                                                IntegerArgumentType.getInteger(ctx, "scannerScale"),
                                                                IntegerArgumentType.getInteger(ctx, "displayScale"),
                                                                IntegerArgumentType.getInteger(ctx, "delayTicks")))
                                                )
                                        )
                                )
                        )
                );
    }

    private int stop(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        int stopped = stopAllScans();
        sender.sendMessage(Lang.get(Lang.Key.SCAN_STOPPED, sender) + stopped);
        return Command.SINGLE_SUCCESS;
    }

    private int cancel(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        int stopped = stopAllScans();
        for (World world : plugin.getServer().getWorlds()) {
            ScanDisplays.removeAll(world);
        }
        sender.sendMessage(Lang.get(Lang.Key.SCAN_CANCELLED, sender) + stopped);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Разбирает аргументы и запускает {@link ScanTask}, добавляя её в список
     * активных сканов (не отменяя уже выполняющиеся — так поддерживаются
     * параллельные сканирования).
     */
    private int run(CommandContext<CommandSourceStack> ctx, ScanType type,
                     int maxRadius, int scannerScale, int displayScale, int delayTicks) throws CommandSyntaxException {
        CommandSender sender = ctx.getSource().getSender();

        EntitySelectorArgumentResolver scannerResolver =
                ctx.getArgument("scanner", EntitySelectorArgumentResolver.class);
        EntitySelectorArgumentResolver displayResolver =
                ctx.getArgument("display", EntitySelectorArgumentResolver.class);

        List<Entity> scannerEntities = scannerResolver.resolve(ctx.getSource());
        List<Entity> displayEntities = displayResolver.resolve(ctx.getSource());

        // БАГ в исходнике: .getFirst() на пустом списке кидал NoSuchElementException,
        // если селектор (например, конкретное имя игрока) не находил ни одной сущности.
        // Теперь это отдельно проверяется и выдаёт понятное локализованное сообщение.
        if (scannerEntities.isEmpty() || displayEntities.isEmpty()) {
            sender.sendMessage(Lang.get(Lang.Key.ENTITY_NOT_FOUND, sender));
            return 0;
        }

        Entity scannerEntity = scannerEntities.get(0);
        Entity displayEntity = displayEntities.get(0);

        // Скан идёт до maxRadius, но модель сжимается в scannerScale раз —
        // это одновременно уменьшает её видимый размер и число заспавненных сущностей.
        int maxDisplayRadius = (int) Math.max(0, Math.round(maxRadius / (double) scannerScale));

        // Координаты фиксируются один раз при запуске — иначе анимация "плыла"
        // бы вместе с движением сканера/дисплея.
        Location scannerBase = scannerEntity.getLocation().clone();
        Location displayBase = displayEntity.getLocation().clone();
        World scannerWorld = scannerBase.getWorld();
        World displayWorld = displayBase.getWorld();

        if (scannerWorld == null || displayWorld == null) {
            sender.sendMessage(Lang.get(Lang.Key.WORLD_NOT_FOUND, sender));
            return 0;
        }

        // Радиус умножается на displayScale, чтобы плотности лучей хватало
        // для заполнения более мелкой подсетки item-display'ев.
        List<Vector3f> rayDirections = RayDirections.build(maxDisplayRadius * displayScale);

        activeScanTasks.removeIf(BukkitTask::isCancelled);

        sender.sendMessage(Lang.get(Lang.Key.SCAN_STARTED, sender) + (activeScanTasks.size() + 1));

        ScanTask task = new ScanTask(scannerBase, displayBase, displayWorld,
                rayDirections, maxRadius, scannerScale, displayScale, type);

        activeScanTasks.add(task.runTaskTimer(plugin, 0L, delayTicks));

        return Command.SINGLE_SUCCESS;
    }
}
