package io.github.tis199.gamecraft.api;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface GameSessionManager {
    default GameSession createSession(String moduleId, List<UUID> players, String difficulty,
                                      GameLocation origin, boolean isComputerOpponent) {
        int totalPlayers = players.size() + (isComputerOpponent ? 1 : 0);
        return createSession(moduleId, players, difficulty, origin, isComputerOpponent, totalPlayers);
    }

    GameSession createSession(String moduleId, List<UUID> players, String difficulty,
                              GameLocation origin, boolean isComputerOpponent, int totalPlayers);

    default GameSession createSession(String moduleId, List<UUID> players, String difficulty,
                                      GameLocation origin, boolean isComputerOpponent, int totalPlayers,
                                      Map<String, String> properties) {
        return createSession(moduleId, players, difficulty, origin, isComputerOpponent, totalPlayers);
    }

    Optional<GameSession> getSession(UUID sessionId);

    Optional<GameSession> getPlayerSession(UUID playerId);

    List<GameSession> getActiveSessions();
}
