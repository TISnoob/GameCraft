package io.github.tis199.gamecraft.api;

import java.util.List;
import java.util.UUID;

/** Publishes authoritative game state to the GameCraft client renderer. */
public interface GameBoardService {
    /** Sends a board update and the action IDs currently accepted by the game module. */
    void publish(GameSession session, UUID privatePlayerId, String title, List<MenuOption> options, String actionType);

    /** Removes a finished board from nearby clients. */
    void clear(UUID sessionId);
}
