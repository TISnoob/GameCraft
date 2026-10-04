package io.github.tis199.gamecraft.engine.ai;

import java.util.List;
import java.util.Objects;

public abstract class MiniMax<GameState, Move> {

    public Move findBestMove(GameState state, int depth, boolean isMaximizingPlayer) {
        if (depth < 1) {
            throw new IllegalArgumentException("Search depth must be at least one");
        }
        List<Move> moves = getPossibleMoves(state);
        if (moves.isEmpty()) {
            return null;
        }
        Move best = moves.get(0);
        double bestScore = isMaximizingPlayer ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        double alpha = Double.NEGATIVE_INFINITY;
        double beta = Double.POSITIVE_INFINITY;
        for (Move move : moves) {
            GameState next = Objects.requireNonNull(applyMove(state, move), "applyMove returned null");
            double score = search(next, depth - 1, !isMaximizingPlayer, alpha, beta);
            if ((isMaximizingPlayer && score > bestScore) || (!isMaximizingPlayer && score < bestScore)) {
                bestScore = score;
                best = move;
            }
            if (isMaximizingPlayer) {
                alpha = Math.max(alpha, bestScore);
            } else {
                beta = Math.min(beta, bestScore);
            }
        }
        return best;
    }

    private double search(GameState state, int depth, boolean maximizing, double alpha, double beta) {
        if (depth == 0) {
            return evaluateState(state);
        }
        List<Move> moves = getPossibleMoves(state);
        if (moves.isEmpty()) {
            return evaluateState(state);
        }
        if (maximizing) {
            double best = Double.NEGATIVE_INFINITY;
            for (Move move : moves) {
                best = Math.max(best, search(applyMove(state, move), depth - 1, false, alpha, beta));
                alpha = Math.max(alpha, best);
                if (beta <= alpha) break;
            }
            return best;
        }
        double best = Double.POSITIVE_INFINITY;
        for (Move move : moves) {
            best = Math.min(best, search(applyMove(state, move), depth - 1, true, alpha, beta));
            beta = Math.min(beta, best);
            if (beta <= alpha) break;
        }
        return best;
    }

    protected abstract List<Move> getPossibleMoves(GameState state);
    protected abstract double evaluateState(GameState state);
    protected abstract GameState applyMove(GameState state, Move move);
}
