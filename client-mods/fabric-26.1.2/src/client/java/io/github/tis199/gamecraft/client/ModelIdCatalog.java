package io.github.tis199.gamecraft.client;

import net.minecraft.resources.Identifier;

import java.util.List;

/** Generated from the client mod's bundled GameCraft item-model assets. */
public final class ModelIdCatalog {
    private static final String[] MODEL_NAMES = new String[] {
            "chair-checkers", "chair-chess", "chair-chinese-checkers", "chair-ludo", "chair-monopoly", "chair-solitaire", "chair-sudoku", "chair-uno",
            "checkers-black-king", "checkers-black-man", "checkers-red-king", "checkers-red-man",
            "chess-black-bishop", "chess-black-king", "chess-black-knight", "chess-black-pawn", "chess-black-queen", "chess-black-rook",
            "chess-move-marker", "chess-selected-marker", "chess-white-bishop", "chess-white-king", "chess-white-knight", "chess-white-pawn", "chess-white-queen", "chess-white-rook",
            "chinese-blue", "chinese-green", "chinese-orange", "chinese-purple", "chinese-red", "chinese-yellow", "dice", "gamecraft-control",
            "ludo-blue", "ludo-green", "ludo-red", "ludo-yellow", "monopoly-hotel", "monopoly-house", "monopoly-token-blue", "monopoly-token-green",
            "monopoly-token-purple", "monopoly-token-red", "monopoly-token-white", "monopoly-token-yellow",
            "solitaire-deck", "sudoku-1", "sudoku-2", "sudoku-3", "sudoku-4", "sudoku-5", "sudoku-6", "sudoku-7", "sudoku-8", "sudoku-9",
            "table-checkers", "table-chess", "table-chinese-checkers", "table-leg", "table-ludo", "table-monopoly", "table-solitaire", "table-sudoku", "table-uno", "table-wood",
            "uno-call", "uno-draw-pile", "uno-pass"
    };

    private ModelIdCatalog() { }

    public static List<Identifier> all() {
        java.util.ArrayList<Identifier> result = new java.util.ArrayList<>(MODEL_NAMES.length + 106);
        for (String name : MODEL_NAMES) result.add(Identifier.fromNamespaceAndPath("gamecraft", name));
        for (int card = 0; card < 54; card++) result.add(Identifier.fromNamespaceAndPath("gamecraft", "item/uno-card-" + card));
        for (int card = 0; card < 52; card++) result.add(Identifier.fromNamespaceAndPath("gamecraft", "item/solitaire-card-" + card));
        return List.copyOf(result);
    }
}
