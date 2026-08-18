package net.dvmn2.scanner.managers;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Простой парсер Minecraft-подобных селекторов сущностей:
 *
 * @s, @p, @a, @r, @e, @n[аргументы]
 * @n - ближайшая сущность любого типа (не только игрок, в отличие от @p).
 * Можно сузить через type=, например @n[type=zombie] найдёт ближайшего зомби.
 * <p>
 * Поддерживаемые аргументы (в квадратных скобках, через запятую):
 * type=<EntityType>      - тип сущности (например type=zombie), можно с "!" для исключения: type=!player
 * distance=<число>       - максимальное расстояние от отправителя команды
 * limit=<число>          - максимальное количество сущностей в результате
 * sort=nearest|furthest|random|arbitrary
 * name=<имя>             - точное совпадение имени (для игроков - ник)
 * gamemode=<режим>       - survival/creative/adventure/spectator (только для @a/@e с игроками)
 * tag=<scoreboard tag>   - наличие тега у сущности
 * uuid=<UUID>            - точное совпадение UUID сущности
 * <p>
 * Кроме селекторов, метод parse() также принимает "сырой" UUID
 * (например "f84c6a79-0a4e-45e0-879b-cd49ebd4c4e2") и найдёт сущность с этим
 * UUID среди загруженных сущностей (или игрока, даже если он в другом мире).
 * <p>
 * Также в аргументах селектора можно фильтровать по UUID: @e[uuid=<uuid>]
 * <p>
 * Пример: "@e[type=zombie,distance=10,limit=5,sort=nearest]"
 */
public class SelectorParser {

    private static final Pattern SELECTOR_PATTERN =
            Pattern.compile("^@([sparen])(?:\\[(.*)\\])?$", Pattern.CASE_INSENSITIVE);

    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    /**
     * Разбирает строку и возвращает список подходящих сущностей.
     *
     * @param sender   тот, кто выполнил команду (нужен для @s, @p, @r и точки отсчёта distance)
     * @param rawInput строка селектора, например "@a" или "@e[type=zombie,distance=5]"
     * @return список найденных сущностей (может быть пустым)
     */
    public static List<Entity> parse(CommandSender sender, String rawInput) {
        List<Entity> result = new ArrayList<>();
        if (rawInput == null || rawInput.isEmpty()) {
            return result;
        }

        String trimmed = rawInput.trim();
        Matcher matcher = SELECTOR_PATTERN.matcher(trimmed);

        // Если это не селектор - сначала проверяем, не UUID ли это
        if (!matcher.matches()) {
            if (UUID_PATTERN.matcher(trimmed).matches()) {
                Entity byUuid = findEntityByUuid(UUID.fromString(trimmed));
                if (byUuid != null) {
                    result.add(byUuid);
                }
                return result;
            }

            // иначе пробуем найти игрока по нику
            Player byName = Bukkit.getPlayerExact(trimmed);
            if (byName != null) {
                result.add(byName);
            }
            return result;
        }

        char type = Character.toLowerCase(matcher.group(1).charAt(0));
        String argsRaw = matcher.group(2);
        SelectorArgs args = SelectorArgs.parse(argsRaw);

        switch (type) {
            case 's': // сам отправитель
                if (sender instanceof Entity) {
                    result.add((Entity) sender);
                }
                break;

            case 'p': // ближайший игрок
                Player nearest = getSenderLocation(sender) != null
                        ? findNearestPlayer(sender, args)
                        : null;
                if (nearest != null) {
                    result.add(nearest);
                }
                break;

            case 'r': // случайный игрок
                List<Player> allPlayers = new ArrayList<>(Bukkit.getOnlinePlayers());
                applyFilters(allPlayers, sender, args);
                if (!allPlayers.isEmpty()) {
                    result.add(allPlayers.get((int) (Math.random() * allPlayers.size())));
                }
                break;

            case 'a': // все игроки
                List<Entity> players = new ArrayList<>(Bukkit.getOnlinePlayers());
                applyFilters(players, sender, args);
                result.addAll(players);
                break;

            case 'e': // все сущности
                List<Entity> entities = collectAllEntities(sender, args);
                applyFilters(entities, sender, args);
                result.addAll(entities);
                break;

            case 'n': // ближайшая сущность любого типа
                Entity nearestEntity = findNearestEntity(sender, args);
                if (nearestEntity != null) {
                    result.add(nearestEntity);
                }
                break;
        }

        // limit применяется в самом конце, после сортировки
        if (args.limit != null && args.limit >= 0 && result.size() > args.limit) {
            result = new ArrayList<>(result.subList(0, args.limit));
        }

        return result;
    }

    // ---------- Вспомогательные методы ----------

    private static Entity findEntityByUuid(UUID uuid) {
        // Игроков искать быстрее и надёжнее - работает, даже если они в другом мире
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            return player;
        }

        // Bukkit.getEntity(UUID) не всегда доступен/эффективен на старых версиях API,
        // поэтому дополнительно проходим по всем мирам вручную
        Entity direct = Bukkit.getEntity(uuid);
        if (direct != null) {
            return direct;
        }

        for (org.bukkit.World world : Bukkit.getWorlds()) {
            for (Entity e : world.getEntities()) {
                if (e.getUniqueId().equals(uuid)) {
                    return e;
                }
            }
        }

        return null;
    }

    /**
     * Определяет "точку отсчёта" для сендера:
     * - для сущности (игрок, моб и т.д.) - её текущая локация;
     * - для командного блока - координаты самого блока;
     * - для консоли - координаты 0,0,0 в главном мире сервера
     * (у консоли нет собственного мира, поэтому берём Bukkit.getWorlds().get(0)).
     */
    private static Location getSenderLocation(CommandSender sender) {
        if (sender instanceof Entity) {
            return ((Entity) sender).getLocation();
        }

        if (sender instanceof BlockCommandSender) {
            return ((BlockCommandSender) sender).getBlock().getLocation();
        }

        if (sender instanceof ConsoleCommandSender) {
            List<org.bukkit.World> worlds = Bukkit.getWorlds();
            if (!worlds.isEmpty()) {
                return new Location(worlds.get(0), 0, 0, 0);
            }
        }

        return null;
    }

    private static Player findNearestPlayer(CommandSender sender, SelectorArgs args) {
        Location loc = getSenderLocation(sender);
        if (loc == null) return null;

        List<Player> candidates = new ArrayList<>(Bukkit.getOnlinePlayers());
        applyFilters(candidates, sender, args);

        return candidates.stream()
                .min(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(loc)))
                .orElse(null);
    }

    private static Entity findNearestEntity(CommandSender sender, SelectorArgs args) {
        Location loc = getSenderLocation(sender);
        if (loc == null) return null;

        List<Entity> candidates = collectAllEntities(sender, args);
        applyFilters(candidates, sender, args);

        // сам отправитель не должен считаться "ближайшей сущностью" к самому себе
        candidates.remove(sender);

        return candidates.stream()
                .min(Comparator.comparingDouble(e -> e.getLocation().distanceSquared(loc)))
                .orElse(null);
    }

    private static List<Entity> collectAllEntities(CommandSender sender, SelectorArgs args) {
        List<Entity> all = new ArrayList<>();
        // Определяем мир через общую логику getSenderLocation - она уже умеет
        // доставать мир из Entity, командного блока и консоли (главный мир сервера).
        Location origin = getSenderLocation(sender);
        if (origin != null && origin.getWorld() != null) {
            all.addAll(origin.getWorld().getEntities());
        } else {
            Bukkit.getWorlds().forEach(w -> all.addAll(w.getEntities()));
        }
        return all;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Entity> void applyFilters(List<T> entities, CommandSender sender, SelectorArgs args) {
        Location origin = getSenderLocation(sender);

        entities.removeIf(e -> {
            // фильтр по типу
            if (args.type != null) {
                boolean matches = e.getType() == args.type;
                if (args.excludeType ? matches : !matches) {
                    return true;
                }
            }

            // фильтр по имени
            if (args.name != null) {
                String entityName = e instanceof Player ? ((Player) e).getName() : e.getCustomName();
                if (entityName == null || !entityName.equals(args.name)) {
                    return true;
                }
            }

            // фильтр по тегу scoreboard
            if (args.tag != null) {
                if (!e.getScoreboardTags().contains(args.tag)) {
                    return true;
                }
            }

            // фильтр по UUID
            if (args.uuid != null) {
                if (!e.getUniqueId().equals(args.uuid)) {
                    return true;
                }
            }

            // фильтр по геймоду (только игроки)
            if (args.gamemode != null) {
                if (!(e instanceof Player) || ((Player) e).getGameMode() != args.gamemode) {
                    return true;
                }
            }

            // фильтр по расстоянию
            if (args.distance != null && origin != null) {
                double distSq = e.getLocation().distanceSquared(origin);
                if (distSq > args.distance * args.distance) {
                    return true;
                }
            }

            return false;
        });

        // сортировка
        if (args.sort != null && origin != null) {
            switch (args.sort) {
                case "nearest":
                    entities.sort(Comparator.comparingDouble(e -> e.getLocation().distanceSquared(origin)));
                    break;
                case "furthest":
                    entities.sort((a, b) -> Double.compare(
                            b.getLocation().distanceSquared(origin),
                            a.getLocation().distanceSquared(origin)));
                    break;
                case "random":
                    java.util.Collections.shuffle(entities);
                    break;
                // "arbitrary" - оставляем как есть
            }
        }
    }

    /**
     * Разобранные аргументы селектора вида [key=value,key=value,...]
     */
    private static class SelectorArgs {
        EntityType type;
        boolean excludeType;
        Double distance;
        Integer limit;
        String sort;
        String name;
        String tag;
        GameMode gamemode;
        UUID uuid;

        static SelectorArgs parse(String raw) {
            SelectorArgs args = new SelectorArgs();
            if (raw == null || raw.isEmpty()) {
                return args;
            }

            for (String pair : raw.split(",")) {
                String[] kv = pair.split("=", 2);
                if (kv.length != 2) continue;

                String key = kv[0].trim().toLowerCase();
                String value = kv[1].trim();

                switch (key) {
                    case "type":
                        if (value.startsWith("!")) {
                            args.excludeType = true;
                            value = value.substring(1);
                        }
                        try {
                            args.type = EntityType.valueOf(value.toUpperCase());
                        } catch (IllegalArgumentException ignored) {
                        }
                        break;

                    case "distance":
                        try {
                            args.distance = Double.parseDouble(value);
                        } catch (NumberFormatException ignored) {
                        }
                        break;

                    case "limit":
                        try {
                            args.limit = Integer.parseInt(value);
                        } catch (NumberFormatException ignored) {
                        }
                        break;

                    case "sort":
                        args.sort = value.toLowerCase();
                        break;

                    case "name":
                        args.name = value;
                        break;

                    case "tag":
                        args.tag = value;
                        break;

                    case "gamemode":
                        try {
                            args.gamemode = GameMode.valueOf(value.toUpperCase());
                        } catch (IllegalArgumentException ignored) {
                        }
                        break;

                    case "uuid":
                        try {
                            args.uuid = UUID.fromString(value);
                        } catch (IllegalArgumentException ignored) {
                        }
                        break;
                }
            }

            return args;
        }
    }
}