package net.dvmn2.scannerplugin.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.EntitySelectorArgumentResolver;

import net.dvmn2.scannerplugin.Lang;
import net.dvmn2.scannerplugin.Permissions;
import net.dvmn2.scannerplugin.ScannerPlugin;
import net.dvmn2.scannerplugin.ScannerSettings;
import net.dvmn2.scannerplugin.scan.ScanManager;
import net.dvmn2.scannerplugin.scan.ScanRequest;
import net.dvmn2.scannerplugin.scan.ScanResult;
import net.dvmn2.scannerplugin.scan.ScanType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Supplier;

/**
 * The {@code /scanner} command (Brigadier).
 *
 * <pre>
 * /scanner particle|display &lt;scanner&gt; &lt;display&gt; [radius] [scannerScale] [displayScale] [delay]
 * /scanner stop
 * /scanner cancel
 * /scanner reload
 * </pre>
 *
 * <p>{@code scanner} is the entity the rays start from; {@code display} is the entity next to which the model is
 * built; {@code radius} is in real blocks; {@code scannerScale} is how many times the model is smaller than the
 * scanned area; {@code displayScale} splits each model block into {@code displayScale^3} cells; {@code delay} is the
 * pause between animation steps in seconds (fractions allowed). Omitted optional arguments take the defaults from
 * {@code config.yml}. {@code stop} halts running scans and keeps the spawned displays; {@code cancel} also removes
 * them.
 */
public final class ScannerCommand {

    private static final String ARG_SCANNER = "scanner";
    private static final String ARG_DISPLAY = "display";
    private static final String ARG_RADIUS = "radius";
    private static final String ARG_SCANNER_SCALE = "scannerScale";
    private static final String ARG_DISPLAY_SCALE = "displayScale";
    private static final String ARG_DELAY = "delay";

    private final ScannerPlugin plugin;
    private final ScanManager manager;
    private final Supplier<ScannerSettings> settings;

    public ScannerCommand(ScannerPlugin plugin, ScanManager manager, Supplier<ScannerSettings> settings) {
        this.plugin = plugin;
        this.manager = manager;
        this.settings = settings;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("scanner")
                .requires(src -> has(src, Permissions.SCAN) || has(src, Permissions.STOP)
                        || has(src, Permissions.RELOAD))
                .then(Commands.literal("particle")
                        .requires(src -> has(src, Permissions.SCAN))
                        .then(scanArguments(ScanType.PARTICLE)))
                .then(Commands.literal("display")
                        .requires(src -> has(src, Permissions.SCAN))
                        .then(scanArguments(ScanType.DISPLAY)))
                .then(Commands.literal("stop")
                        .requires(src -> has(src, Permissions.STOP))
                        .executes(this::stop))
                .then(Commands.literal("cancel")
                        .requires(src -> has(src, Permissions.STOP))
                        .executes(this::cancel))
                .then(Commands.literal("reload")
                        .requires(src -> has(src, Permissions.RELOAD))
                        .executes(this::reload))
                .build();
    }

    /** {@code <scanner> <display> [radius] [scannerScale] [displayScale] [delay]}, shared by both scan types. */
    private ArgumentBuilder<CommandSourceStack, ?> scanArguments(ScanType type) {
        RequiredArgumentBuilder<CommandSourceStack, Double> delay = Commands
                .argument(ARG_DELAY, DoubleArgumentType.doubleArg(
                        ScanRequest.MIN_DELAY_SECONDS, ScanRequest.MAX_DELAY_SECONDS))
                .executes(ctx -> run(ctx, type));
        RequiredArgumentBuilder<CommandSourceStack, Integer> displayScale = Commands
                .argument(ARG_DISPLAY_SCALE, IntegerArgumentType.integer(1))
                .executes(ctx -> run(ctx, type))
                .then(delay);
        RequiredArgumentBuilder<CommandSourceStack, Integer> scannerScale = Commands
                .argument(ARG_SCANNER_SCALE, IntegerArgumentType.integer(1))
                .executes(ctx -> run(ctx, type))
                .then(displayScale);
        RequiredArgumentBuilder<CommandSourceStack, Integer> radius = Commands
                .argument(ARG_RADIUS, IntegerArgumentType.integer(1))
                .executes(ctx -> run(ctx, type))
                .then(scannerScale);

        return Commands.argument(ARG_SCANNER, ArgumentTypes.entity())
                .then(Commands.argument(ARG_DISPLAY, ArgumentTypes.entity())
                        .executes(ctx -> run(ctx, type))
                        .then(radius));
    }

    private int run(CommandContext<CommandSourceStack> ctx, ScanType type) {
        CommandSender sender = ctx.getSource().getSender();

        Entity scanner = resolveFirst(ctx, ARG_SCANNER);
        Entity display = resolveFirst(ctx, ARG_DISPLAY);
        if (scanner == null || display == null) {
            sender.sendMessage(Lang.get(Lang.Key.ENTITY_NOT_FOUND, sender));
            return 0;
        }

        ScannerSettings current = settings.get();
        ScanRequest request = new ScanRequest(type, scanner.getLocation(), display.getLocation(),
                intArg(ctx, ARG_RADIUS, current.defaultRadiusBlocks()),
                intArg(ctx, ARG_SCANNER_SCALE, current.defaultScannerScale()),
                intArg(ctx, ARG_DISPLAY_SCALE, current.defaultDisplayScale()),
                doubleArg(ctx, ARG_DELAY, current.defaultDelaySeconds()));

        ScanResult result = manager.begin(request);
        sender.sendMessage(messageFor(result, sender, current));
        return result == ScanResult.STARTED ? Command.SINGLE_SUCCESS : 0;
    }

    private int stop(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        int stopped = manager.stopAll();
        sender.sendMessage(Lang.get(Lang.Key.SCAN_STOPPED, sender,
                Placeholder.unparsed("count", String.valueOf(stopped))));
        return Command.SINGLE_SUCCESS;
    }

    private int cancel(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        int stopped = manager.stopAll();
        int removed = manager.removeAllDisplays();
        sender.sendMessage(Lang.get(Lang.Key.SCAN_CANCELLED, sender,
                Placeholder.unparsed("count", String.valueOf(stopped)),
                Placeholder.unparsed("removed", String.valueOf(removed))));
        return Command.SINGLE_SUCCESS;
    }

    private int reload(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        plugin.reloadAll();
        sender.sendMessage(Lang.get(Lang.Key.RELOADED, sender));
        return Command.SINGLE_SUCCESS;
    }

    private Component messageFor(ScanResult result, CommandSender sender, ScannerSettings current) {
        return switch (result) {
            case STARTED -> Lang.get(Lang.Key.SCAN_STARTED, sender,
                    Placeholder.unparsed("count", String.valueOf(manager.getActiveCount())));
            case WORLD_NOT_FOUND -> Lang.get(Lang.Key.WORLD_NOT_FOUND, sender);
            case TOO_MANY_SCANS -> Lang.get(Lang.Key.TOO_MANY_SCANS, sender,
                    Placeholder.unparsed("limit", String.valueOf(current.maxActiveScans())));
            case RADIUS_TOO_LARGE -> Lang.get(Lang.Key.RADIUS_TOO_LARGE, sender,
                    Placeholder.unparsed("limit", String.valueOf(current.maxRadiusBlocks())));
            case DISPLAY_SCALE_TOO_LARGE -> Lang.get(Lang.Key.DISPLAY_SCALE_TOO_LARGE, sender,
                    Placeholder.unparsed("limit", String.valueOf(current.maxDisplayScale())));
            case TOO_MANY_RAYS -> Lang.get(Lang.Key.TOO_MANY_RAYS, sender,
                    Placeholder.unparsed("limit", String.valueOf(current.maxRaysPerScan())));
        };
    }

    @Nullable
    private static Entity resolveFirst(CommandContext<CommandSourceStack> ctx, String name) {
        try {
            List<Entity> entities = ctx.getArgument(name, EntitySelectorArgumentResolver.class)
                    .resolve(ctx.getSource());
            return entities.isEmpty() ? null : entities.getFirst();
        } catch (CommandSyntaxException ex) {
            // A selector that cannot be resolved is reported to the sender as "no entity found".
            return null;
        }
    }

    private static int intArg(CommandContext<CommandSourceStack> ctx, String name, int fallback) {
        try {
            return IntegerArgumentType.getInteger(ctx, name);
        } catch (IllegalArgumentException ex) {
            // Brigadier throws this when the optional argument was not typed.
            return fallback;
        }
    }

    private static double doubleArg(CommandContext<CommandSourceStack> ctx, String name, double fallback) {
        try {
            return DoubleArgumentType.getDouble(ctx, name);
        } catch (IllegalArgumentException ex) {
            // Brigadier throws this when the optional argument was not typed.
            return fallback;
        }
    }

    private static boolean has(CommandSourceStack source, String permission) {
        return source.getSender().hasPermission(permission);
    }
}
