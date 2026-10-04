package io.github.tis199.gamecraft.ludo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LudoBoardTest {
    @Test
    void startSquaresMatchTheTrackForEachSupportedPlayerCount() {
        for (int players = 2; players <= 4; players++) {
            LudoBoard board = new LudoBoard(players);
            for (int player = 0; player < players; player++) {
                assertEquals(player * LudoBoard.TRACK_LENGTH / players, board.startSquare(player));
            }
        }
    }

    @Test
    void twoPlayerTokenUsesTheOppositeHalfOfTheTrack() {
        LudoBoard board = new LudoBoard(2);

        assertTrue(board.legalTokens(1, 6).contains(0));
        board.move(1, 0, 6);

        assertEquals(26, board.trackSquare(1, 0));
    }
}
