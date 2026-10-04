package io.github.tis199.gamecraft.ludo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Rules model for classic four-token Ludo on a 52-square shared track. */
public final class LudoBoard {
    public static final int TRACK_LENGTH = 52;
    public static final int FINISH_PROGRESS = 56;
    private static final int[] SAFE = {0, 8, 13, 21, 26, 34, 39, 47};
    private final int playerCount;
    private final boolean teamMode;
    private final int[][] progress;

    public LudoBoard(int playerCount) {
        this(playerCount, false);
    }

    public LudoBoard(int playerCount, boolean teamMode) {
        if (playerCount < 2 || playerCount > 4) throw new IllegalArgumentException("Ludo supports 2–4 players");
        if (teamMode && playerCount != 4) throw new IllegalArgumentException("Team Ludo needs exactly four players");
        this.playerCount = playerCount;
        this.teamMode = teamMode;
        this.progress = new int[playerCount][4];
        reset();
    }

    public void reset() {
        for (int[] player : progress) Arrays.fill(player, -1);
    }

    public int playerCount() { return playerCount; }
    public int progress(int player, int token) { return progress[player][token]; }
    public LudoToken getTokenState(int player, int token) {
        int value = progress[player][token];
        if (value < 0) return LudoToken.IN_BASE;
        if (value >= FINISH_PROGRESS) return LudoToken.FINISHED;
        if (value >= TRACK_LENGTH) return LudoToken.IN_HOME_COLUMN;
        return LudoToken.ON_BOARD;
    }
    public int getTokenPosition(int player, int token) { return progress[player][token]; }
    public int startSquare(int player) { return player * TRACK_LENGTH / playerCount; }
    public int trackSquare(int player, int token) { return (startSquare(player) + progress[player][token]) % TRACK_LENGTH; }

    public List<Integer> legalTokens(int player, int dice) {
        List<Integer> moves = new ArrayList<>();
        for (int token = 0; token < 4; token++) {
            int current = progress[player][token];
            if (current == -1 ? dice == 6 : current < FINISH_PROGRESS && current + dice <= FINISH_PROGRESS) {
                moves.add(token);
            }
        }
        return moves;
    }

    /** Moves a token and returns the number of opposing tokens captured. */
    public int move(int player, int token, int dice) {
        if (!legalTokens(player, dice).contains(token)) throw new IllegalArgumentException("Illegal Ludo move");
        int current = progress[player][token];
        if (current == -1) {
            progress[player][token] = 0;
            return captureAt(player, startSquare(player));
        }
        progress[player][token] = current + dice;
        if (progress[player][token] >= FINISH_PROGRESS) return 0;
        if (progress[player][token] >= TRACK_LENGTH) return 0;
        return captureAt(player, trackSquare(player, token));
    }

    private int captureAt(int player, int square) {
        for (int safe : SAFE) if (safe == square) return 0;
        for (int seat = 0; seat < playerCount; seat++) if (startSquare(seat) == square) return 0;
        int captured = 0;
        for (int other = 0; other < playerCount; other++) {
            if (other == player || areTeammates(player, other)) continue;
            for (int token = 0; token < 4; token++) {
                if (progress[other][token] >= 0 && progress[other][token] < TRACK_LENGTH
                        && trackSquare(other, token) == square) {
                    progress[other][token] = -1;
                    captured++;
                }
            }
        }
        return captured;
    }

    public boolean hasPlayerWon(int player) {
        if (teamMode) {
            int partner = (player + 2) % 4;
            return finishedTokens(player) + finishedTokens(partner) == 8;
        }
        return finishedTokens(player) == 4;
    }

    public boolean teamMode() {
        return teamMode;
    }

    public int teamOf(int player) {
        return teamMode ? Math.floorMod(player, 2) : player;
    }

    private int finishedTokens(int player) {
        int count = 0;
        for (int token = 0; token < 4; token++) {
            if (progress[player][token] == FINISH_PROGRESS) count++;
        }
        return count;
    }

    private boolean areTeammates(int first, int second) {
        return teamMode && first != second && teamOf(first) == teamOf(second);
    }
}
