package io.github.tis199.gamecraft.paper.integration;

import io.github.tis199.gamecraft.paper.GameCraftPlugin;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;

public final class PlaceholderIntegration extends PlaceholderExpansion {
    private final GameCraftPlugin plugin;

    public PlaceholderIntegration(GameCraftPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "gamecraft";
    }

    @Override
    public String getAuthor() {
        return String.join(",", plugin.getDescription().getAuthors());
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onPlaceholderRequest(Player player, String params) {
        return switch (params.toLowerCase()) {
            case "modules_loaded" -> Integer.toString(plugin.moduleManager().loadedModules().size());
            case "player_sitting" -> Boolean.toString(player != null && player.getVehicle() != null);
            case "ai_enabled" -> Boolean.toString(plugin.aiService().isEnabled());
            case "active_games" -> Integer.toString(plugin.services().sessions().getActiveSessions().size());
            default -> "";
        };
    }
}
