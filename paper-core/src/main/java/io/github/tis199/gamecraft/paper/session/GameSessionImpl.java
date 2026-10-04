package io.github.tis199.gamecraft.paper.session;

import io.github.tis199.gamecraft.api.GameAction;
import io.github.tis199.gamecraft.api.GameLocation;
import io.github.tis199.gamecraft.api.GameModule;
import io.github.tis199.gamecraft.api.GameScheduler;
import io.github.tis199.gamecraft.api.GameSession;
import io.github.tis199.gamecraft.api.GameSessionState;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Thread-safe session shell. Game rules and turn ownership remain module-owned. */
public final class GameSessionImpl implements GameSession {
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private final UUID sessionId;
    private final GameModule module;
    private final List<UUID> players;
    private final int totalPlayers;
    private final String difficulty;
    private final GameLocation origin;
    private final boolean computerOpponent;
    private final Map<String, String> properties;
    private final GameScheduler scheduler;
    private final GameSessionManagerImpl manager;
    private volatile GameSessionState state = GameSessionState.IN_PROGRESS;

    public GameSessionImpl(GameSessionManagerImpl manager, GameScheduler scheduler, UUID sessionId,
                           GameModule module, List<UUID> players, int totalPlayers, String difficulty,
                           GameLocation origin, boolean computerOpponent, Map<String, String> properties) {
        this.manager = manager;
        this.scheduler = scheduler;
        this.sessionId = sessionId;
        this.module = module;
        this.players = List.copyOf(players);
        this.totalPlayers = totalPlayers;
        this.difficulty = difficulty;
        this.origin = origin;
        this.computerOpponent = computerOpponent;
        this.properties = Map.copyOf(properties);
    }

    @Override public UUID sessionId() { return sessionId; }
    @Override public String moduleId() { return module.descriptor().id(); }
    @Override public List<UUID> players() { return players; }
    @Override public int totalPlayers() { return totalPlayers; }
    @Override public String difficulty() { return difficulty; }
    @Override public GameLocation origin() { return origin; }
    @Override public boolean isComputerOpponent() { return computerOpponent; }
    @Override public Map<String, String> properties() { return properties; }
    @Override public GameSessionState state() { return state; }

    @Override
    public synchronized void sendAction(GameAction action) {
        if (state != GameSessionState.IN_PROGRESS || !players.contains(action.playerId())) {
            return;
        }
        try {
            module.onPlayerAction(action);
        } catch (RuntimeException exception) {
            manager.logModuleFailure(moduleId(), "process player action", exception);
            end(null);
        }
    }

    @Override
    public void broadcastMessage(String message) {
        var component = MINI.deserialize("<dark_gray>[</dark_gray><gold>✦ GC</gold><dark_gray>]</dark_gray> " + message);
        for (UUID playerId : players) {
            scheduler.runForEntity(playerId, () -> {
                org.bukkit.entity.Player player = org.bukkit.Bukkit.getPlayer(playerId);
                if (player != null && player.isOnline()) {
                    player.sendMessage(component);
                }
            }, () -> { });
        }
    }

    @Override
    public synchronized void end(UUID winner) {
        if (state == GameSessionState.FINISHED || state == GameSessionState.CANCELLED) {
            return;
        }
        state = GameSessionState.FINISHED;
        if (winner != null) {
            int seat = players.indexOf(winner);
            broadcastMessage(seat < 0 ? "GameCraft: game finished."
                    : "GameCraft: game finished. Winner: player " + (seat + 1));
        }
        try {
            module.onSessionEnd(this);
        } catch (RuntimeException exception) {
            manager.logModuleFailure(moduleId(), "end session", exception);
        } finally {
            manager.removeSession(sessionId);
        }
    }

    void cancel() {
        state = GameSessionState.CANCELLED;
        try {
            module.onSessionEnd(this);
        } catch (RuntimeException exception) {
            manager.logModuleFailure(moduleId(), "cancel session", exception);
        } finally {
            manager.removeSession(sessionId);
        }
    }
}
