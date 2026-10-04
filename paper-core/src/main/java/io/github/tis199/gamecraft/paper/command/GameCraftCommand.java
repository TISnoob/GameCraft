package io.github.tis199.gamecraft.paper.command;

import io.github.tis199.gamecraft.api.GameLocation;
import io.github.tis199.gamecraft.api.GameModule;
import io.github.tis199.gamecraft.api.GameSession;
import io.github.tis199.gamecraft.api.MenuDefinition;
import io.github.tis199.gamecraft.api.MenuOption;
import io.github.tis199.gamecraft.paper.GameCraftPlugin;
import io.github.tis199.gamecraft.paper.furniture.FurnitureManager;
import io.github.tis199.gamecraft.paper.menu.InventoryMenuService;
import io.github.tis199.gamecraft.paper.session.GameRoomManager;
import io.github.tis199.gamecraft.paper.session.GameSessionManagerImpl;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Player command and chest-menu setup flow. Active matches remain rendered in-world. */
public final class GameCraftCommand implements CommandExecutor, TabCompleter {
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private final GameCraftPlugin plugin;
    private final FurnitureManager furniture;
    private final GameSessionManagerImpl sessions;
    private final InventoryMenuService menus;
    private final GameRoomManager rooms;
    private final Map<UUID, SetupDraft> drafts = new ConcurrentHashMap<>();

    public GameCraftCommand(GameCraftPlugin plugin, FurnitureManager furniture, GameSessionManagerImpl sessions,
                            InventoryMenuService menus, GameRoomManager rooms) {
        this.plugin = plugin;
        this.furniture = furniture;
        this.sessions = sessions;
        this.menus = menus;
        this.rooms = rooms;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            help(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "status" -> status(sender);
            case "modules", "games" -> modules(sender);
            case "reload" -> reload(sender);
            case "enable" -> setModule(sender, args, true);
            case "disable" -> setModule(sender, args, false);
            case "menu" -> openMenu(sender);
            case "furniture" -> placeFurniture(sender, args);
            case "sit" -> sit(sender);
            case "stand" -> stand(sender);
            case "play" -> play(sender, args);
            case "invite" -> invite(sender, args);
            case "accept" -> accept(sender, args);
            case "start" -> roomAction(sender, rooms::start);
            case "cancel" -> roomAction(sender, rooms::cancel);
            case "room" -> roomStatus(sender);
            case "forfeit" -> forfeit(sender);
            default -> help(sender);
        }
        return true;
    }

    private void status(CommandSender sender) {
        tell(sender, "<gold><bold>✦ GAMECRAFT</bold></gold> <dark_gray>•</dark_gray> <gray>"
                + plugin.getDescription().getVersion() + "</gray>");
        tell(sender, "<gray>Server:</gray> <white>" + safe(Bukkit.getName()) + " "
                + safe(Bukkit.getBukkitVersion()) + "</white>");
        tell(sender, "<gray>Installed game modules:</gray> <aqua>" + plugin.moduleManager().loadedModules().size() + "</aqua>");
        tell(sender, "<gray>AI service:</gray> " + (plugin.aiService().isEnabled() ? "<green>online</green>" : "<dark_gray>off</dark_gray>"));
    }

    private void modules(CommandSender sender) {
        List<GameModule> loaded = plugin.moduleManager().loadedModules();
        tell(sender, "<gold><bold>✦ GAME LIBRARY</bold></gold>");
        if (loaded.isEmpty()) {
            tell(sender, "<gray>No game packs installed yet. Browse the developer guide under <yellow>docs/game-development</yellow>.</gray>");
            return;
        }
        loaded.stream().sorted(java.util.Comparator.comparing(module -> module.descriptor().displayName()))
                .forEach(module -> tell(sender, "<aqua>◆ " + safe(module.descriptor().displayName()) + "</aqua> "
                        + "<dark_gray>v" + safe(module.descriptor().version()) + "</dark_gray> <gray>— "
                        + safe(module.descriptor().description()) + "</gray>"));
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("gamecraft.admin")) {
            deny(sender);
            return;
        }
        plugin.reloadGameCraft();
        tell(sender, "<green>✓ GameCraft settings reloaded.</green>");
    }

    private void setModule(CommandSender sender, String[] args, boolean enabled) {
        if (!sender.hasPermission("gamecraft.admin")) {
            deny(sender);
            return;
        }
        if (args.length < 2 || !args[1].matches("[a-z][a-z0-9-]{1,31}")) {
            tell(sender, "<red>Usage: /gc " + (enabled ? "enable" : "disable") + " ‹module-id›</red>");
            return;
        }
        List<String> ids = new ArrayList<>(plugin.getConfig().getStringList("modules.enabled-games"));
        if (enabled && !ids.contains(args[1])) ids.add(args[1]);
        else if (!enabled) ids.remove(args[1]);
        plugin.getConfig().set("modules.enabled-games", ids);
        plugin.saveConfig();
        plugin.moduleManager().checkRegistryAsync();
        tell(sender, "<green>✓ Updated game packs. Restart after a download or unload to activate the change.</green>");
    }

    private void openMenu(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            tell(sender, "<yellow>Open <gold>/gc play</gold> in-game to browse the games.</yellow>");
            return;
        }
        play(player, new String[]{"play"});
    }

    private void play(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            tell(sender, "<yellow>Game rooms are created in-game with <gold>/gc play</gold>.</yellow>");
            return;
        }
        if (!player.hasPermission("gamecraft.use")) {
            deny(player);
            return;
        }
        // No arguments intentionally opens the chest menu. Keep a compact command path for console/admin setups.
        if (args.length == 1) {
            openGamePicker(player);
            return;
        }
        legacyPlay(player, args);
    }

    private void openGamePicker(Player player) {
        List<GameModule> available = plugin.moduleManager().loadedModules().stream()
                .sorted(java.util.Comparator.comparing(module -> module.descriptor().displayName())).toList();
        if (available.isEmpty()) {
            tell(player, "<gold><bold>✦ GAMECRAFT</bold></gold> <gray>No games are installed yet.</gray>");
            tell(player, "<gray>Server owners can add game packs from the configured GameCraft release manifest.</gray>");
            return;
        }
        List<MenuOption> options = available.stream().map(module -> new MenuOption(
                "game:" + module.descriptor().id(), "<yellow><bold>" + safe(module.descriptor().displayName()) + "</bold></yellow>",
                List.of("<gray>" + safe(module.descriptor().description()) + "</gray>",
                        "<dark_gray>Players: " + module.minPlayers() + "–" + module.maxPlayers() + "</dark_gray>"))).toList();
        menus.open(player.getUniqueId(), new MenuDefinition("game-picker", "<gold><bold>✦ Pick a game</bold></gold>", options),
                choice -> onGameMenuChoice(player.getUniqueId(), choice));
    }

    private void onGameMenuChoice(UUID playerId, String choice) {
        if (!choice.startsWith("game:")) return;
        GameModule module = plugin.moduleManager().getModule(choice.substring(5)).orElse(null);
        Player player = Bukkit.getPlayer(playerId);
        if (module == null || player == null) {
            tell(player, "<red>That game pack is no longer available.</red>");
            return;
        }
        SetupDraft draft = new SetupDraft(module);
        drafts.put(playerId, draft);
        List<MenuOption> options = new ArrayList<>();
        if (module.minPlayers() <= 1) options.add(option("mode:solo", "<green>Solo adventure</green>", "Play on your own."));
        if (module.supportsComputer()) options.add(option("mode:computer", "<light_purple>Challenge the computer</light_purple>", "Choose a difficulty and side."));
        if (module.maxPlayers() >= 2) options.add(option("mode:room", "<aqua>Host a room</aqua>", "Invite friends and choose your seats."));
        if (options.isEmpty()) {
            drafts.remove(playerId);
            tell(player, "<red>This game pack does not provide a playable mode yet.</red>");
            return;
        }
        openMenu(playerId, "<gold>✦ " + safe(module.descriptor().displayName()) + " • Mode</gold>", options,
                selected -> onModeChoice(playerId, selected));
    }

    private void onModeChoice(UUID playerId, String choice) {
        SetupDraft draft = drafts.get(playerId);
        if (draft == null) return;
        if (choice.equals("mode:solo")) {
            draft.mode = "solo";
            draft.capacity = 1;
            chooseDifficulty(playerId, draft);
        } else if (choice.equals("mode:computer")) {
            draft.mode = "computer";
            choosePlayerCount(playerId, draft, draft.module.minPlayers(), draft.module.maxPlayers(), "computer-count:");
        } else if (choice.equals("mode:room")) {
            draft.mode = "room";
            choosePlayerCount(playerId, draft, Math.max(2, draft.module.minPlayers()), draft.module.maxPlayers(), "room-count:");
        }
    }

    private void choosePlayerCount(UUID playerId, SetupDraft draft, int minimum, int maximum, String prefix) {
        List<MenuOption> options = new ArrayList<>();
        for (int count = minimum; count <= maximum; count++) {
            options.add(option(prefix + count, "<yellow>" + count + (count == 1 ? " player" : " players") + "</yellow>",
                    draft.mode.equals("computer") ? "Total seats, including you and the computer." : "Room seats, including you."));
        }
        openMenu(playerId, "<gold>✦ Choose the table size</gold>", options, selected -> {
            try {
                draft.capacity = Integer.parseInt(selected.substring(prefix.length()));
            } catch (RuntimeException ignored) {
                return;
            }
            if (draft.mode.equals("computer") && draft.capacity < draft.module.minPlayers()) {
                tell(Bukkit.getPlayer(playerId), "<red>That game needs more seats.</red>");
                return;
            }
            if (draft.module.descriptor().id().equals("ludo") && draft.mode.equals("room") && draft.capacity == 4) {
                chooseLudoTeams(playerId, draft);
            } else {
                chooseSeat(playerId, draft);
            }
        });
    }

    private void chooseLudoTeams(UUID playerId, SetupDraft draft) {
        List<MenuOption> options = List.of(
                option("teams:no", "<aqua>Every player for themselves</aqua>", "Classic free-for-all Ludo."),
                option("teams:yes", "<gold>2 vs 2 team Ludo</gold>", "Red + blue against yellow + green."));
        openMenu(playerId, "<gold>✦ Choose your Ludo style</gold>", options, selected -> {
            draft.teamMode = selected.equals("teams:yes");
            chooseSeat(playerId, draft);
        });
    }

    private void chooseSeat(UUID playerId, SetupDraft draft) {
        String id = draft.module.descriptor().id();
        int choiceCount = draft.mode.equals("computer") ? draft.capacity : draft.capacity;
        if (!(id.equals("chess") || id.equals("ludo"))) {
            draft.hostSeat = 0;
            chooseDifficulty(playerId, draft);
            return;
        }
        List<MenuOption> options = new ArrayList<>();
        for (int seat = 0; seat < choiceCount; seat++) {
            String color = id.equals("chess") ? (seat == 0 ? "White" : "Black")
                    : switch (seat) { case 0 -> "Red"; case 1 -> "Yellow"; case 2 -> "Blue"; default -> "Green"; };
            options.add(option("seat:" + seat, "<yellow>Play as " + color + "</yellow>", "Your chosen side / color."));
        }
        options.add(option("seat:random", "<light_purple>Surprise me</light_purple>", "Pick a side automatically."));
        openMenu(playerId, "<gold>✦ Choose a side or color</gold>", options, selected -> {
            if (selected.endsWith("random")) {
                draft.hostSeat = java.util.concurrent.ThreadLocalRandom.current().nextInt(choiceCount);
            } else {
                try { draft.hostSeat = Integer.parseInt(selected.substring(5)); }
                catch (RuntimeException ignored) { draft.hostSeat = 0; }
            }
            chooseDifficulty(playerId, draft);
        });
    }

    private void chooseDifficulty(UUID playerId, SetupDraft draft) {
        List<String> difficulties = draft.module.supportedDifficulties();
        if (difficulties.isEmpty()) difficulties = List.of("normal");
        List<MenuOption> options = difficulties.stream().map(value -> option("difficulty:" + value,
                "<aqua>" + capitalize(value) + "</aqua>", difficultyHint(value))).toList();
        openMenu(playerId, "<gold>✦ Choose a difficulty</gold>", options, selected -> {
            draft.difficulty = selected.substring("difficulty:".length());
            finishSetup(playerId, draft);
        });
    }

    private void finishSetup(UUID playerId, SetupDraft draft) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) {
            drafts.remove(playerId);
            return;
        }
        if (draft.mode.equals("room")) {
            try {
                rooms.create(player, draft.module, draft.capacity, draft.difficulty, draft.teamMode, draft.hostSeat);
                drafts.remove(playerId);
            } catch (IllegalArgumentException | IllegalStateException exception) {
                tell(player, exception.getMessage());
            }
            return;
        }
        try {
            GameLocation origin = gameOrigin(player, draft.module.descriptor().id());
            Map<String, String> properties = new HashMap<>();
            if (draft.mode.equals("computer")) properties.put("human-seat-order", Integer.toString(draft.hostSeat));
            GameSession session = sessions.createSession(draft.module.descriptor().id(), List.of(playerId),
                    draft.difficulty, origin, draft.mode.equals("computer"), draft.capacity, properties);
            session.broadcastMessage("<gold>✦ Your " + safe(draft.module.descriptor().displayName())
                    + " match is live!</gold> <gray>Nearby players can watch the board.</gray>");
            drafts.remove(playerId);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            tell(player, "<red>" + safe(exception.getMessage()) + "</red>");
        }
    }

    private void legacyPlay(Player player, String[] args) {
        if (args.length < 3) {
            openGamePicker(player);
            return;
        }
        GameModule module = plugin.moduleManager().getModule(args[1].toLowerCase(Locale.ROOT)).orElse(null);
        if (module == null) {
            tell(player, "<red>Game not found.</red> <gray>Use <yellow>/gc play</yellow> to browse installed games.</gray>");
            return;
        }
        String mode = args[2].toLowerCase(Locale.ROOT);
        String difficulty = args.length > 3 ? args[3].toLowerCase(Locale.ROOT) : "medium";
        try {
            if (mode.equals("multiplayer") || mode.equals("multi") || mode.equals("pvp")) {
                tell(player, "<yellow>Use <gold>/gc play</gold> to host a room, then invite friends with <gold>/gc invite</gold>.</yellow>");
                return;
            }
            boolean computer = mode.equals("computer") || mode.equals("ai") || mode.equals("cpu");
            int count = mode.equals("solo") ? 1 : (args.length > 4 ? Integer.parseInt(args[4]) : 2);
            Map<String, String> properties = new HashMap<>();
            if (computer && args.length > 5 && args[5].matches("[01]")) properties.put("human-seat-order", args[5]);
            GameSession session = sessions.createSession(module.descriptor().id(), List.of(player.getUniqueId()),
                    difficulty, gameOrigin(player, module.descriptor().id()), computer, count, properties);
            session.broadcastMessage("<gold>✦ " + safe(module.descriptor().displayName()) + " match started!</gold>");
        } catch (NumberFormatException exception) {
            tell(player, "<red>Player count must be a number.</red>");
        } catch (IllegalArgumentException | IllegalStateException exception) {
            tell(player, "<red>" + safe(exception.getMessage()) + "</red>");
        }
    }

    private GameLocation gameOrigin(Player player, String gameId) {
        return furniture.findOrPlaceGameTable(gameId, player);
    }

    private void placeFurniture(CommandSender sender, String[] args) {
        if (!sender.hasPermission("gamecraft.admin")) {
            deny(sender);
            return;
        }
        if (!(sender instanceof Player player)) {
            tell(sender, "<yellow>Furniture placement requires an in-game player.</yellow>");
            return;
        }
        if (args.length < 2 || !(args[1].equalsIgnoreCase("table") || args[1].equalsIgnoreCase("chair"))) {
            tell(sender, "<yellow>Usage: /gc furniture table ‹game› | chair [‹game›]</yellow>");
            return;
        }
        String gameId = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : null;
        if (args[1].equalsIgnoreCase("table") && gameId == null) {
            tell(player, "<yellow>Every game has its own footprint. Choose one: "
                    + String.join(", ", plugin.moduleManager().loadedModules().stream().map(m -> m.descriptor().id()).toList()) + "</yellow>");
            return;
        }
        Block target = player.getTargetBlockExact(6);
        if (target == null) {
            tell(player, "<yellow>Look at a block within six blocks first.</yellow>");
            return;
        }
        Location placement = target.getLocation().add(0, 1, 0);
        GameLocation point = toGameLocation(placement);
        try {
            if (args[1].equalsIgnoreCase("chair")) {
                if (gameId != null) furniture.placeGameChair(gameId, point);
                else furniture.placeChair(point);
                tell(player, "<green>✦ Chair placed. Right-click it to take a seat.</green>");
            } else {
                furniture.placeGameTable(gameId, point);
                tell(player, "<green>✦ " + safe(capitalize(gameId)) + " table placed!</green> "
                        + "<gray>Its custom-size board will anchor here.</gray>");
            }
        } catch (IllegalArgumentException exception) {
            tell(player, "<red>" + safe(exception.getMessage()) + "</red>");
        }
    }

    private void sit(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            tell(sender, "<yellow>This command can only be used in-game.</yellow>");
            return;
        }
        if (!furniture.sitNearest(player)) tell(player, "<gray>No GameCraft chair is nearby.</gray>");
    }

    private void stand(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            tell(sender, "<yellow>This command can only be used in-game.</yellow>");
            return;
        }
        if (!furniture.stand(player.getUniqueId())) tell(player, "<gray>You are already on your feet.</gray>");
    }

    private void invite(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player) || args.length < 2) {
            tell(sender, "<yellow>Usage: /gc invite ‹player›</yellow>");
            return;
        }
        try { rooms.invite(player, args[1]); }
        catch (IllegalArgumentException | IllegalStateException exception) { tell(player, exception.getMessage()); }
    }

    private void accept(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            tell(sender, "<yellow>Accept invitations in-game.</yellow>");
            return;
        }
        try { rooms.accept(player, args.length > 1 ? args[1] : null); }
        catch (IllegalArgumentException | IllegalStateException exception) { tell(player, exception.getMessage()); }
    }

    private void roomAction(CommandSender sender, java.util.function.Consumer<Player> action) {
        if (!(sender instanceof Player player)) {
            tell(sender, "<yellow>This action is only available in-game.</yellow>");
            return;
        }
        try { action.accept(player); }
        catch (IllegalArgumentException | IllegalStateException exception) { tell(player, exception.getMessage()); }
    }

    private void roomStatus(CommandSender sender) {
        if (sender instanceof Player player) rooms.show(player);
        else tell(sender, "<yellow>Room status is available in-game.</yellow>");
    }

    private void forfeit(CommandSender sender) {
        if (!(sender instanceof Player player)) return;
        sessions.getPlayerSession(player.getUniqueId()).ifPresentOrElse(session -> {
            session.end(null);
            tell(player, "<gray>Game ended. Better luck next round!</gray>");
        }, () -> tell(player, "<gray>You're not in an active game.</gray>"));
    }

    private void help(CommandSender sender) {
        tell(sender, "<gold><bold>✦ GAMECRAFT</bold></gold> <dark_gray>• games come alive in-world</dark_gray>");
        tell(sender, "<yellow>/gc play</yellow> <gray>Browse games and create a room</gray>");
        tell(sender, "<yellow>/gc invite ‹player›</yellow> <gray>Invite a friend • /gc accept [host]</gray>");
        tell(sender, "<yellow>/gc start</yellow> <gray>Start your full room • /gc room • /gc cancel</gray>");
        tell(sender, "<yellow>/gc status</yellow> <gray>View active modules • /gc sit • /gc stand • /gc forfeit</gray>");
        if (sender.hasPermission("gamecraft.admin")) {
            tell(sender, "<yellow>/gc furniture table ‹game›</yellow> <gray>Place the matching 3D table</gray>");
            tell(sender, "<yellow>/gc furniture chair [‹game›] • /gc reload • /gc enable|disable ‹id›</yellow>");
        }
    }

    private void openMenu(UUID playerId, String title, List<MenuOption> options,
                          java.util.function.Consumer<String> callback) {
        menus.open(playerId, new MenuDefinition("gamecraft-setup", title, options), callback);
    }

    private static MenuOption option(String id, String title, String description) {
        return new MenuOption(id, title, List.of("<gray>" + safe(description) + "</gray>"));
    }

    private static String difficultyHint(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "easy" -> "A relaxed, friendly round.";
            case "medium", "normal" -> "The classic balance.";
            case "hard" -> "Bring your best moves.";
            case "expert" -> "A serious challenge.";
            default -> "Set the pace for your match.";
        };
    }

    private static String capitalize(String value) {
        if (value == null || value.isBlank()) return "GameCraft";
        return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1).replace('-', ' ');
    }

    private static GameLocation toGameLocation(Location location) {
        return new GameLocation(location.getWorld().getUID(), location.getX(), location.getY(), location.getZ());
    }

    private static void deny(CommandSender sender) {
        tell(sender, "<red>✕ You don't have permission for that.</red>");
    }

    private static void tell(CommandSender sender, String message) {
        if (sender != null && message != null) sender.sendMessage(MINI.deserialize(message));
    }

    private static String safe(String text) {
        return text == null ? "" : MINI.escapeTags(text);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("help", "play", "menu", "status", "modules", "invite", "accept", "start", "room",
                            "cancel", "sit", "stand", "reload", "enable", "disable", "furniture", "forfeit")
                    .stream().filter(value -> value.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args[0].equalsIgnoreCase("invite") || args[0].equalsIgnoreCase("accept")) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(args[args.length - 1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args[0].equalsIgnoreCase("furniture") && args.length == 2) return List.of("table", "chair");
        if (args[0].equalsIgnoreCase("furniture") && args.length == 3) {
            return plugin.moduleManager().loadedModules().stream().map(module -> module.descriptor().id())
                    .filter(id -> id.startsWith(args[2].toLowerCase(Locale.ROOT))).toList();
        }
        if (args[0].equalsIgnoreCase("play") && args.length == 2) {
            return plugin.moduleManager().loadedModules().stream().map(module -> module.descriptor().id())
                    .filter(id -> id.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        return List.of();
    }

    private static final class SetupDraft {
        final GameModule module;
        String mode;
        String difficulty = "medium";
        int capacity;
        int hostSeat;
        boolean teamMode;

        SetupDraft(GameModule module) { this.module = module; }
    }
}
