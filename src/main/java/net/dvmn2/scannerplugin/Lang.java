package net.dvmn2.scannerplugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Player-facing messages in Russian and English (Adventure MiniMessage).
 *
 * <p>The language comes from {@code settings.language}: {@code ru}, {@code en}, or {@code auto}, which uses the
 * player's client locale and English for the console. Placeholders MUST be passed as {@link TagResolver}s.
 * This class holds the only static mutable state of the plugin (the current language).
 */
public final class Lang {

    public enum Key {
        ENTITY_NOT_FOUND,
        WORLD_NOT_FOUND,
        SCAN_STARTED,
        SCAN_STOPPED,
        SCAN_CANCELLED,
        TOO_MANY_SCANS,
        RADIUS_TOO_LARGE,
        DISPLAY_SCALE_TOO_LARGE,
        TOO_MANY_RAYS,
        RELOADED
    }

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final Map<Key, String> RU = new EnumMap<>(Key.class);
    private static final Map<Key, String> EN = new EnumMap<>(Key.class);

    private static volatile String language = "auto";

    static {
        put(Key.ENTITY_NOT_FOUND,
                "<red>Сущность по указанному селектору не найдена.",
                "<red>No entity matches the given selector.");
        put(Key.WORLD_NOT_FOUND,
                "<red>Не удалось определить мир сущностей.",
                "<red>Could not determine the world of the entities.");
        put(Key.SCAN_STARTED,
                "<green>Скан запущен! Активных сканирований: <count>",
                "<green>Scan started! Active scans: <count>");
        put(Key.SCAN_STOPPED,
                "<yellow>Остановлено активных сканирований: <count>",
                "<yellow>Stopped active scans: <count>");
        put(Key.SCAN_CANCELLED,
                "<yellow>Остановлено сканирований: <count>, удалено моделей: <removed>",
                "<yellow>Stopped scans: <count>, removed model parts: <removed>");
        put(Key.TOO_MANY_SCANS,
                "<red>Достигнут лимит одновременных сканирований: <limit>.",
                "<red>The limit of simultaneous scans is reached: <limit>.");
        put(Key.RADIUS_TOO_LARGE,
                "<red>Радиус скана не может превышать <limit> блоков.",
                "<red>The scan radius cannot exceed <limit> blocks.");
        put(Key.DISPLAY_SCALE_TOO_LARGE,
                "<red>Значение displayScale не может превышать <limit>.",
                "<red>The displayScale value cannot exceed <limit>.");
        put(Key.TOO_MANY_RAYS,
                "<red>Скан слишком велик: он выпустил бы более <limit> лучей. "
                        + "Уменьшите радиус или displayScale либо увеличьте scannerScale.",
                "<red>The scan is too large: it would cast more than <limit> rays. "
                        + "Reduce the radius or displayScale, or increase scannerScale.");
        put(Key.RELOADED,
                "<green>Конфигурация перезагружена.",
                "<green>Configuration reloaded.");
        verify();
    }

    private Lang() {
    }

    public static void setLanguage(String value) {
        language = value == null || value.isBlank() ? "auto" : value.toLowerCase(Locale.ROOT);
    }

    public static Component get(Key key, CommandSender sender, TagResolver... resolvers) {
        return MM.deserialize(table(sender).get(key), resolvers);
    }

    private static void put(Key key, String ru, String en) {
        RU.put(key, ru);
        EN.put(key, en);
    }

    private static void verify() {
        for (Key key : Key.values()) {
            if (!RU.containsKey(key) || !EN.containsKey(key)) {
                throw new IllegalStateException("Missing translation for " + key);
            }
        }
    }

    private static Map<Key, String> table(CommandSender sender) {
        return switch (language) {
            case "ru" -> RU;
            case "en" -> EN;
            default -> sender instanceof Player player
                    && "ru".equalsIgnoreCase(player.locale().getLanguage()) ? RU : EN;
        };
    }
}
