package io.github.tis199.gamecraft.engine.player;

public class EloCalculator {
    private static final int K_FACTOR = 32;

    public static int calculateNewRating(int playerRating, int opponentRating, double actualScore) {
        double expectedScore = 1.0 / (1.0 + Math.pow(10, (opponentRating - playerRating) / 400.0));
        return playerRating + (int) Math.round(K_FACTOR * (actualScore - expectedScore));
    }
}
