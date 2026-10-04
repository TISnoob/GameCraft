package io.github.tis199.gamecraft.api;

import java.util.List;
import java.util.UUID;
import java.util.Map;

public interface GameSession {
    UUID sessionId();

    String moduleId();

    List<UUID> players();

    /** Total seats in the game, including computer-controlled seats. */
    int totalPlayers();

    String difficulty();

    GameLocation origin();

    boolean isComputerOpponent();

    /** Immutable launch options chosen in the room lobby (for example Ludo teams or seat order). */
    default Map<String, String> properties() {
        return Map.of();
    }

    GameSessionState state();

    void sendAction(GameAction action);

    void broadcastMessage(String message);

    void end(UUID winner);
}
