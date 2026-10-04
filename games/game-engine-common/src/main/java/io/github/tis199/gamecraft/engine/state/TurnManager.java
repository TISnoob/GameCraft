package io.github.tis199.gamecraft.engine.state;

import java.util.List;
import java.util.UUID;

public class TurnManager {
    private final List<UUID> players;
    private int currentTurnIndex = 0;

    public TurnManager(List<UUID> players) {
        this.players = List.copyOf(players);
    }

    public UUID getCurrentPlayer() {
        return players.get(currentTurnIndex);
    }

    public void nextTurn() {
        currentTurnIndex = (currentTurnIndex + 1) % players.size();
    }

    public void previousTurn() {
        currentTurnIndex = (currentTurnIndex - 1 + players.size()) % players.size();
    }

    public boolean isPlayerTurn(UUID playerId) {
        return getCurrentPlayer().equals(playerId);
    }
}
