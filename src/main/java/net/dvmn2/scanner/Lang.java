package net.dvmn2.scanner;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Локализация плагина (ru/en). Язык задаётся в config.yml (settings.language):
 * "ru" / "en" — фиксированный язык; "auto" (по умолчанию) — язык определяется
 * по клиентской локали игрока (Player#locale()), для консоли — английский.
 */
public final class Lang {

    public enum Key {
        ENTITY_NOT_FOUND,
        WORLD_NOT_FOUND,
        SCAN_STARTED,
        SCAN_STOPPED,
        SCAN_CANCELLED,
        COMMAND_DESCRIPTION
    }

    private static final Map<Key, String> RU = new EnumMap<>(Key.class);
    private static final Map<Key, String> EN = new EnumMap<>(Key.class);

    static {
        RU.put(Key.ENTITY_NOT_FOUND, "§cСущность по указанному селектору не найдена.");
        RU.put(Key.WORLD_NOT_FOUND, "§cНе удалось определить мир сущностей.");
        RU.put(Key.SCAN_STARTED, "§aСкан запущен! Активных сканирований: ");
        RU.put(Key.SCAN_STOPPED, "§eОстановлено активных сканирований: ");
        RU.put(Key.SCAN_CANCELLED, "§eОстановлено сканирований: ");
        RU.put(Key.COMMAND_DESCRIPTION,
                "Сканирует блоки вокруг сущности-сканера и отображает их в виде уменьшенной модели из item-display");

        EN.put(Key.ENTITY_NOT_FOUND, "§cNo entity matches the given selector.");
        EN.put(Key.WORLD_NOT_FOUND, "§cCould not determine the world of the entities.");
        EN.put(Key.SCAN_STARTED, "§aScan started! Active scans: ");
        EN.put(Key.SCAN_STOPPED, "§eStopped active scans: ");
        EN.put(Key.SCAN_CANCELLED, "§eStopped scans: ");
        EN.put(Key.COMMAND_DESCRIPTION,
                "Scans blocks around a scanner entity and displays them as a small model made of item-displays");
    }

    /** "ru", "en" или "auto" — значение из config.yml. */
    private static volatile String configuredLanguage = "auto";

    private Lang() {
    }

    public static void setLanguage(String language) {
        configuredLanguage = (language == null || language.isBlank())
                ? "auto"
                : language.toLowerCase(Locale.ROOT);
    }

    /** Сообщение для конкретного отправителя команды (учитывает его локаль при language: auto). */
    public static String get(Key key, CommandSender sender) {
        return resolveTable(sender).getOrDefault(key, RU.get(key));
    }

    /**
     * Текст для мест без конкретного отправителя (например, описание команды,
     * регистрируемое один раз при старте плагина, — его нельзя показывать
     * по-разному для разных игроков). При "auto" по умолчанию используется английский.
     */
    public static String getStatic(Key key) {
        Map<Key, String> table = "ru".equals(configuredLanguage) ? RU : EN;
        return table.getOrDefault(key, EN.get(key));
    }

    private static Map<Key, String> resolveTable(CommandSender sender) {
        return switch (configuredLanguage) {
            case "ru" -> RU;
            case "en" -> EN;
            default -> autoResolve(sender); // "auto" или некорректное значение
        };
    }

    private static Map<Key, String> autoResolve(CommandSender sender) {
        if (sender instanceof Player player) {
            String langCode = player.locale().getLanguage();
            return "ru".equalsIgnoreCase(langCode) ? RU : EN;
        }
        return EN; // консоль/RCON — по умолчанию английский
    }
}
