package io.github.tis199.gamecraft.engine.module;

import io.github.tis199.gamecraft.api.GameCraftServices;
import io.github.tis199.gamecraft.api.GameModule;
import io.github.tis199.gamecraft.api.GameModuleContext;
import io.github.tis199.gamecraft.api.GameSession;
import io.github.tis199.gamecraft.api.MenuOption;

import java.util.List;
import java.util.UUID;

/** Shared world-scene and normalized-action helpers for built-in game modules. */
public abstract class AbstractGameModule implements GameModule {
    protected GameCraftServices services;

    @Override
    public void onLoad(GameModuleContext context) {
        services = context.services();
    }

    protected void openMenu(GameSession session, UUID playerId, String title,
                            List<MenuOption> options, String actionType) {
        if (playerId == null) return;
        renderScene(session, playerId, title, options, actionType);
    }

    /** Publishes authoritative board state to the installed GameCraft client mod. */
    protected void renderScene(GameSession session, String title, List<MenuOption> options, String actionType) {
        renderScene(session, null, title, options, actionType);
    }

    protected void renderScene(GameSession session, UUID privatePlayerId, String title,
                               List<MenuOption> options, String actionType) {
        services.boards().publish(session, privatePlayerId, title, options, actionType);
    }

    protected void closeScene(GameSession session) {
        services.boards().clear(session.sessionId());
    }

    protected void closeScenes() {
        if (services != null) {
            services.sessions().getActiveSessions().stream()
                    .filter(session -> session.moduleId().equals(descriptor().id()))
                    .forEach(session -> services.boards().clear(session.sessionId()));
        }
    }

    protected void tell(GameSession session, UUID playerId, String message) {
        if (playerId == null) {
            session.broadcastMessage(message);
            return;
        }
        services.scheduler().runForEntity(playerId, () -> {
            org.bukkit.entity.Player player = org.bukkit.Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                player.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                        .deserialize("<dark_gray>[</dark_gray><gold>✦ GC</gold><dark_gray>]</dark_gray> " + message));
            }
        }, () -> { });
    }

    protected void runBot(Runnable work) {
        services.scheduler().runAsync(work);
    }

    protected boolean isHumanSeat(GameSession session, int seat) {
        return humanAt(session, seat) != null;
    }

    protected UUID humanAt(GameSession session, int seat) {
        String order = session.properties().get("human-seat-order");
        if (order == null || order.isBlank()) {
            return seat >= 0 && seat < session.players().size() ? session.players().get(seat) : null;
        }
        String[] seats = order.split(",");
        for (int playerIndex = 0; playerIndex < seats.length && playerIndex < session.players().size(); playerIndex++) {
            try {
                if (Integer.parseInt(seats[playerIndex].trim()) == seat) return session.players().get(playerIndex);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
