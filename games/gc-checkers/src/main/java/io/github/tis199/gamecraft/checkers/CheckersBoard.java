package io.github.tis199.gamecraft.checkers;

import java.util.ArrayList;
import java.util.List;

/** American checkers rules: mandatory captures, multi-jumps, kings, and promotion. */
public final class CheckersBoard {
    private final CheckersPiece[][] board = new CheckersPiece[8][8];

    public CheckersBoard() { reset(); }

    public void reset() {
        for (int rank = 0; rank < 8; rank++) {
            for (int file = 0; file < 8; file++) {
                board[rank][file] = null;
                if ((rank + file) % 2 == 1) {
                    if (rank < 3) board[rank][file] = CheckersPiece.RED;
                    else if (rank > 4) board[rank][file] = CheckersPiece.BLACK;
                }
            }
        }
    }

    public CheckersPiece getPiece(int rank, int file) {
        if (!inside(rank, file)) return null;
        return board[rank][file];
    }

    public List<Move> legalMoves(int player) {
        List<Move> captures = new ArrayList<>();
        List<Move> steps = new ArrayList<>();
        for (int rank = 0; rank < 8; rank++) {
            for (int file = 0; file < 8; file++) {
                CheckersPiece piece = board[rank][file];
                if (piece == null || piece.isRed() != (player == 0)) continue;
                collectCaptures(rank, file, piece, new ArrayList<>(List.of(new Square(rank, file))), captures);
                collectSteps(rank, file, piece, steps);
            }
        }
        return captures.isEmpty() ? steps : captures;
    }

    public void move(Move move, int player) {
        if (!legalMoves(player).contains(move)) throw new IllegalArgumentException("Illegal checkers move");
        Square start = move.path.get(0);
        CheckersPiece piece = board[start.rank][start.file];
        board[start.rank][start.file] = null;
        for (int i = 1; i < move.path.size(); i++) {
            Square previous = move.path.get(i - 1);
            Square next = move.path.get(i);
            if (Math.abs(next.rank - previous.rank) == 2) {
                board[(next.rank + previous.rank) / 2][(next.file + previous.file) / 2] = null;
            }
        }
        Square destination = move.path.get(move.path.size() - 1);
        if (piece == CheckersPiece.RED && destination.rank == 7) piece = CheckersPiece.RED_KING;
        if (piece == CheckersPiece.BLACK && destination.rank == 0) piece = CheckersPiece.BLACK_KING;
        board[destination.rank][destination.file] = piece;
    }

    public int evaluate(int player) {
        int score = 0;
        for (int rank = 0; rank < 8; rank++) {
            for (int file = 0; file < 8; file++) {
                CheckersPiece piece = board[rank][file];
                if (piece == null) continue;
                int sign = piece.isRed() == (player == 0) ? 1 : -1;
                score += sign * (piece.isKing() ? 180 : 100);
                if (!piece.isKing()) score += sign * (piece.isRed() ? rank * 2 : (7 - rank) * 2);
                if (file == 0 || file == 7) score += sign * 3;
            }
        }
        return score;
    }

    private void collectSteps(int rank, int file, CheckersPiece piece, List<Move> result) {
        int direction = piece.isRed() ? 1 : -1;
        for (int side : new int[]{-1, 1}) {
            for (int vertical : piece.isKing() ? new int[]{-1, 1} : new int[]{direction}) {
                int r = rank + vertical;
                int c = file + side;
                if (inside(r, c) && board[r][c] == null) {
                    result.add(new Move(List.of(new Square(rank, file), new Square(r, c))));
                }
            }
        }
    }

    private void collectCaptures(int rank, int file, CheckersPiece piece, List<Square> path,
                                 List<Move> result) {
        boolean found = false;
        int direction = piece.isRed() ? 1 : -1;
        for (int side : new int[]{-1, 1}) {
            for (int vertical : piece.isKing() ? new int[]{-1, 1} : new int[]{direction}) {
                int jumpedRank = rank + vertical;
                int jumpedFile = file + side;
                int landingRank = rank + 2 * vertical;
                int landingFile = file + 2 * side;
                if (!inside(landingRank, landingFile) || board[landingRank][landingFile] != null
                        || board[jumpedRank][jumpedFile] == null
                        || board[jumpedRank][jumpedFile].isRed() == piece.isRed()) continue;
                found = true;
                CheckersPiece captured = board[jumpedRank][jumpedFile];
                board[rank][file] = null;
                board[jumpedRank][jumpedFile] = null;
                CheckersPiece moved = promote(piece, landingRank);
                board[landingRank][landingFile] = moved;
                path.add(new Square(landingRank, landingFile));
                if (moved.isKing() && !piece.isKing()) {
                    result.add(new Move(List.copyOf(path)));
                } else {
                    collectCaptures(landingRank, landingFile, moved, path, result);
                }
                path.remove(path.size() - 1);
                board[landingRank][landingFile] = null;
                board[jumpedRank][jumpedFile] = captured;
                board[rank][file] = piece;
            }
        }
        if (!found && path.size() > 1) result.add(new Move(List.copyOf(path)));
    }

    private static CheckersPiece promote(CheckersPiece piece, int rank) {
        if (piece == CheckersPiece.RED && rank == 7) return CheckersPiece.RED_KING;
        if (piece == CheckersPiece.BLACK && rank == 0) return CheckersPiece.BLACK_KING;
        return piece;
    }

    private static boolean inside(int rank, int file) { return rank >= 0 && rank < 8 && file >= 0 && file < 8; }

    public record Square(int rank, int file) { }
    public record Move(List<Square> path) {
        public Move { path = List.copyOf(path); }
        public int captures() {
            if (path.size() < 2) return 0;
            return Math.abs(path.get(1).rank - path.get(0).rank) == 2 ? path.size() - 1 : 0;
        }
        public String label() {
            return path.stream().map(square -> "" + (char) ('a' + square.file) + (square.rank + 1))
                    .reduce((a, b) -> a + (captures() > 0 ? " × " : " → ") + b).orElse("");
        }
    }
}
