package io.github.tis199.gamecraft.paper.command;

import io.github.tis199.gamecraft.api.MenuDefinition;
import io.github.tis199.gamecraft.api.MenuOption;
import io.github.tis199.gamecraft.paper.GameCraftPlugin;
import io.github.tis199.gamecraft.paper.furniture.FurnitureManager;
import io.github.tis199.gamecraft.paper.menu.InventoryMenuService;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class GameCraftCommand implements CommandExecutor, TabCompleter {
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private final GameCraftPlugin plugin;
    private final InventoryMenuService menus;
    private final FurnitureManager furniture;

    public GameCraftCommand(GameCraftPlugin plugin, InventoryMenuService menus, FurnitureManager furniture) {
        this.plugin = plugin;
        this.menus = menus;
        this.furniture = furniture;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            help(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "status" -> status(sender);
            case "modules" -> modules(sender);
            case "reload" -> reload(sender);
            case "enable" -> setModule(sender, args, true);
            case "disable" -> setModule(sender, args, false);
            case "menu" -> openMenu(sender);
            case "furniture" -> placeFurniture(sender, args);
            case "sit" -> sit(sender);
            case "stand" -> stand(sender);
            default -> help(sender);
        }
        return true;
    }

    private void status(CommandSender sender) {
        sender.sendMessage(MINI_MESSAGE.deserialize("<gold>GameCraft</gold> <gray>" + plugin.getDescription().getVersion()));
        sender.sendMessage(MINI_MESSAGE.deserialize("<gray>Server: <white>" + Bukkit.getName() + " "
                + Bukkit.getBukkitVersion()));
        sender.sendMessage(MINI_MESSAGE.deserialize("<gray>Loaded game modules: <white>"
                + plugin.moduleManager().loadedModules().size()));
        sender.sendMessage(MINI_MESSAGE.deserialize("<gray>AI service enabled: <white>"
                + plugin.aiService().isEnabled()));
    }

    private void modules(CommandSender sender) {
        List<String> enabled = plugin.moduleManager().enabledIds();
        List<String> loaded = plugin.moduleManager().loadedModules().stream()
                .map(module -> module.descriptor().id() + " " + module.descriptor().version()).toList();
        sender.sendMessage(MINI_MESSAGE.deserialize("<gold>Configured modules:</gold> <white>"
                + (enabled.isEmpty() ? "none" : String.join(", ", enabled))));
        sender.sendMessage(MINI_MESSAGE.deserialize("<gold>Loaded modules:</gold> <white>"
                + (loaded.isEmpty() ? "none" : String.join(", ", loaded))));
        if (enabled.isEmpty()) {
            sender.sendMessage(MINI_MESSAGE.deserialize("<gray>See docs/game-development/ for the module guide."));
        }
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("gamecraft.admin")) {
            deny(sender);
            return;
        }
        plugin.reloadGameCraft();
        sender.sendMessage(MINI_MESSAGE.deserialize("<green>GameCraft configuration reloaded.</green>"));
    }

    private void setModule(CommandSender sender, String[] args, boolean enabled) {
        if (!sender.hasPermission("gamecraft.admin")) {
            deny(sender);
            return;
        }
        if (args.length < 2 || !args[1].matches("[a-z][a-z0-9-]{1,31}")) {
            sender.sendMessage(MINI_MESSAGE.deserialize("<red>Usage: /gamecraft "
                    + (enabled ? "enable" : "disable") + " <module-id>"));
            return;
        }
        List<String> ids = new ArrayList<>(plugin.getConfig().getStringList("modules.enabled-games"));
        if (enabled && !ids.contains(args[1])) {
            ids.add(args[1]);
        } else if (!enabled) {
            ids.remove(args[1]);
        }
        plugin.getConfig().set("modules.enabled-games", ids);
        plugin.saveConfig();
        plugin.moduleManager().checkRegistryAsync();
        sender.sendMessage(MINI_MESSAGE.deserialize("<green>Updated enabled modules. Restart the server"
                + (enabled ? " after the module downloads" : " to unload the module") + ".</green>"));
    }

    private void openMenu(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This menu can only be opened by a player.");
            return;
        }
        List<MenuOption> options = plugin.moduleManager().loadedModules().stream()
                .map(module -> new MenuOption(module.descriptor().id(), module.descriptor().displayName(),
                        List.of(module.descriptor().description())))
                .toList();
        if (options.isEmpty()) {
            options = List.of(new MenuOption("no-games", "No games installed", List.of(
                    "GameCraft core is ready.", "Ask a developer to add a game module.")));
        }
        menus.open(player.getUniqueId(), new MenuDefinition("gamecraft:main", "GameCraft", options), option -> {
            if (option.equals("no-games")) {
                player.sendMessage("No game modules are installed yet.");
            } else {
                player.sendMessage("Game module selected: " + option);
            }
        });
    }

    private void placeFurniture(CommandSender sender, String[] args) {
        if (!sender.hasPermission("gamecraft.admin")) {
            deny(sender);
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Furniture placement requires an in-game player.");
            return;
        }
        if (args.length < 2 || !(args[1].equalsIgnoreCase("table") || args[1].equalsIgnoreCase("chair"))) {
            sender.sendMessage("Usage: /gamecraft furniture <table [width] [depth]|chair>");
            return;
        }
        Block target = player.getTargetBlockExact(6);
        if (target == null) {
            sender.sendMessage("Look at a block within 6 blocks first.");
            return;
        }
        Location placement = target.getLocation().add(0, 1, 0);
        if (args[1].equalsIgnoreCase("chair")) {
            furniture.placeChair(toGameLocation(placement));
            sender.sendMessage("GameCraft chair placed.");
            return;
        }
        int width = parseDimension(args, 2, 2);
        int depth = parseDimension(args, 3, 2);
        if (width < 1 || width > 16 || depth < 1 || depth > 16) {
            sender.sendMessage("Table width and depth must be between 1 and 16.");
            return;
        }
        furniture.placeTable(toGameLocation(placement), width, depth);
        sender.sendMessage("GameCraft table placed (" + width + " x " + depth + ").");
    }

    private void sit(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used in game.");
            return;
        }
        if (!furniture.sitNearest(player)) {
            sender.sendMessage("No GameCraft chair is nearby.");
        }
    }

    private void stand(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used in game.");
            return;
        }
        if (!furniture.stand(player.getUniqueId())) {
            sender.sendMessage("You are not sitting in a GameCraft chair.");
        }
    }

    private void help(CommandSender sender) {
        sender.sendMessage(MINI_MESSAGE.deserialize("<gold>GameCraft commands</gold>"));
        sender.sendMessage("/gamecraft menu | status | modules | sit | stand");
        if (sender.hasPermission("gamecraft.admin")) {
            sender.sendMessage("/gamecraft reload | enable <id> | disable <id>");
            sender.sendMessage("/gamecraft furniture <table [width] [depth]|chair>");
        }
    }

    private static int parseDimension(String[] args, int index, int defaultValue) {
        if (index >= args.length) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(args[index]);
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private static io.github.tis199.gamecraft.api.GameLocation toGameLocation(Location location) {
        World world = location.getWorld();
        return new io.github.tis199.gamecraft.api.GameLocation(world.getUID(), location.getX(), location.getY(), location.getZ());
    }

    private static void deny(CommandSender sender) {
        sender.sendMessage(MINI_MESSAGE.deserialize("<red>You do not have permission to do that.</red>"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("help", "menu", "status", "modules", "sit", "stand", "reload", "enable", "disable", "furniture")
                    .stream().filter(value -> value.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("furniture")) {
            return List.of("table", "chair").stream()
                    .filter(value -> value.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        return List.of();
    }
}
