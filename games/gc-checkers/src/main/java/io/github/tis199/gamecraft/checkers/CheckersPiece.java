package io.github.tis199.gamecraft.checkers;

public enum CheckersPiece {
    RED, RED_KING, BLACK, BLACK_KING;

    public boolean isRed() {
        return this == RED || this == RED_KING;
    }

    public boolean isKing() {
        return this == RED_KING || this == BLACK_KING;
    }
}
