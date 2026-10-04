package io.github.tis199.gamecraft.solitaire;

import io.github.tis199.gamecraft.api.GameAction;
import io.github.tis199.gamecraft.api.GameModuleDescriptor;
import io.github.tis199.gamecraft.api.GameSession;
import io.github.tis199.gamecraft.api.MenuOption;
import io.github.tis199.gamecraft.engine.module.AbstractGameModule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Klondike Solitaire, played through legal-move inventory choices. */
public final class SolitaireModule extends AbstractGameModule {
    private final Map<UUID, Board> games = new ConcurrentHashMap<>();

    @Override public GameModuleDescriptor descriptor() {
        return new GameModuleDescriptor("solitaire", "Solitaire", "0.1.0", 1,
                "Klondike Solitaire with draw-one or draw-three difficulty.");
    }
    @Override public int minPlayers() { return 1; }
    @Override public int maxPlayers() { return 1; }
    @Override public List<String> supportedDifficulties() { return List.of("easy", "medium", "hard", "expert"); }

    @Override
    public void onSessionStart(GameSession session) {
        games.put(session.sessionId(), new Board(session.difficulty()));
        session.broadcastMessage("Solitaire started. Move cards to build four suit foundations from Ace to King.");
        showBoard(session);
    }

    @Override
    public void onPlayerAction(GameAction action) {
        GameSession session = services.sessions().getPlayerSession(action.playerId()).orElse(null);
        if (session == null || !session.moduleId().equals("solitaire")) return;
        Board board = games.get(session.sessionId());
        if (board == null) return;
        synchronized (board) {
            String choice = action.data().getOrDefault("choice", "");
            if (choice.equals("draw")) {
                draw(board, session.difficulty().equals("hard") || session.difficulty().equals("expert") ? 3 : 1);
                board.selectedColumn = -2;
            } else if (choice.startsWith("flip:")) {
                int column = parse(choice, 1);
                if (column >= 0 && column < 7 && board.faceUpStart[column] == board.columns.get(column).size() - 1) {
                    board.faceUpStart[column]--;
                }
            } else if (choice.startsWith("select:")) {
                String[] parts = choice.split(":");
                if (parts.length != 3) return;
                board.selectedColumn = parse(parts[1], 0);
                board.selectedIndex = parse(parts[2], 0);
                if (board.selectedColumn < 0 || board.selectedColumn >= 7
                        || board.selectedIndex < board.faceUpStart[board.selectedColumn]
                        || board.selectedIndex >= board.columns.get(board.selectedColumn).size()
                        || !isValidRun(board.columns.get(board.selectedColumn), board.selectedIndex)) {
                    board.selectedColumn = -2;
                    return;
                }
                showTargets(session, board, action.playerId());
                return;
            } else if (choice.startsWith("wt:")) {
                int target = parse(choice, 1);
                if (!canMoveToColumn(top(board.waste), target, board)) return;
                board.columns.get(target).add(board.waste.remove(board.waste.size() - 1));
            } else if (choice.startsWith("wf:")) {
                int suit = parse(choice, 1);
                if (!canMoveToFoundation(top(board.waste), suit, board)) return;
                board.foundations.get(suit).add(board.waste.remove(board.waste.size() - 1));
            } else if (choice.startsWith("tf:")) {
                int column = parse(choice, 1);
                if (column < 0 || column >= 7) return;
                List<Card> pile = board.columns.get(column);
                Card card = top(pile);
                if (card == null || !canMoveToFoundation(card, card.suit, board)) return;
                pile.remove(pile.size() - 1);
                board.foundations.get(card.suit).add(card);
                reveal(column, board);
            } else if (choice.startsWith("to:")) {
                int target = parse(choice, 1);
                if (board.selectedColumn < 0 || target < 0 || target >= 7) return;
                List<Card> source = board.columns.get(board.selectedColumn);
                if (board.selectedIndex < board.faceUpStart[board.selectedColumn]
                        || board.selectedIndex >= source.size()
                        || !isValidRun(source, board.selectedIndex)) return;
                Card moving = source.get(board.selectedIndex);
                if (!canMoveToColumn(moving, target, board)) return;
                List<Card> run = new ArrayList<>(source.subList(board.selectedIndex, source.size()));
                source.subList(board.selectedIndex, source.size()).clear();
                board.columns.get(target).addAll(run);
                reveal(board.selectedColumn, board);
                board.selectedColumn = -2;
            } else if (choice.equals("cancel")) {
                board.selectedColumn = -2;
            } else {
                return;
            }
            if (board.foundations.stream().mapToInt(List::size).sum() == 52) {
                session.end(action.playerId());
                return;
            }
        }
        showBoard(session);
    }

    @Override public void onSessionEnd(GameSession session) { games.remove(session.sessionId()); closeScene(session); }

    private void showBoard(GameSession session) {
        Board board = games.get(session.sessionId());
        if (board == null || session.players().isEmpty()) return;
        List<MenuOption> options = new ArrayList<>();
        options.add(new MenuOption("draw", "Stock: " + board.stock.size() + " • Waste: " + cardText(top(board.waste)),
                List.of("Click to draw", "Foundations: " + board.foundations.stream().mapToInt(List::size).sum() + "/52")));
        if (!board.waste.isEmpty()) {
            addIf(options, "wf:" + top(board.waste).suit, "Waste → foundation", canMoveToFoundation(top(board.waste), top(board.waste).suit, board));
            for (int target = 0; target < 7; target++) {
                addIf(options, "wt:" + target, "Waste → column " + (target + 1), canMoveToColumn(top(board.waste), target, board));
            }
        }
        for (int column = 0; column < 7; column++) {
            List<Card> pile = board.columns.get(column);
            if (pile.isEmpty()) {
                options.add(new MenuOption("col:" + column, "Column " + (column + 1) + " • empty", List.of("King can be placed here")));
                continue;
            }
            if (board.faceUpStart[column] == pile.size() - 1) {
                options.add(new MenuOption("flip:" + column, "Flip column " + (column + 1), List.of("Reveal the next card")));
            }
            for (int index = board.faceUpStart[column]; index < pile.size(); index++) {
                Card card = pile.get(index);
                options.add(new MenuOption("select:" + column + ":" + index,
                        "Column " + (column + 1) + " • " + cardText(card),
                        List.of("Select this card and the cards below it")));
            }
            Card card = top(pile);
            addIf(options, "tf:" + column, "Column " + (column + 1) + " → foundation",
                    canMoveToFoundation(card, card.suit, board));
        }
        openMenu(session, session.players().get(0), "Solitaire • " + session.difficulty(), options, "solitaire");
    }

    private void showTargets(GameSession session, Board board, UUID player) {
        List<MenuOption> options = new ArrayList<>();
        Card card = board.columns.get(board.selectedColumn).get(board.selectedIndex);
        for (int target = 0; target < 7; target++) {
            if (target != board.selectedColumn
                    && isValidRun(board.columns.get(board.selectedColumn), board.selectedIndex)
                    && canMoveToColumn(card, target, board)) {
                options.add(new MenuOption("to:" + target, "Move to column " + (target + 1), List.of()));
            }
        }
        if (board.selectedIndex == board.columns.get(board.selectedColumn).size() - 1
                && canMoveToFoundation(card, card.suit, board)) {
            options.add(new MenuOption("tf:" + board.selectedColumn, "Move to foundation", List.of()));
        }
        options.add(new MenuOption("cancel", "Cancel", List.of()));
        openMenu(session, player, "Move " + cardText(card), options, "solitaire");
    }

    private static void addIf(List<MenuOption> list, String id, String title, boolean valid) {
        if (valid) list.add(new MenuOption(id, title, List.of()));
    }

    private static boolean canMoveToColumn(Card card, int target, Board board) {
        if (card == null || target < 0 || target >= 7) return false;
        List<Card> pile = board.columns.get(target);
        return pile.isEmpty() ? card.rank == 13
                : card.color() != top(pile).color() && card.rank == top(pile).rank - 1;
    }

    private static boolean isValidRun(List<Card> source, int start) {
        if (start < 0 || start >= source.size()) return false;
        for (int index = start + 1; index < source.size(); index++) {
            Card previous = source.get(index - 1);
            Card current = source.get(index);
            if (current.color() == previous.color() || current.rank != previous.rank - 1) return false;
        }
        return true;
    }

    private static boolean canMoveToFoundation(Card card, int suit, Board board) {
        if (card == null || suit != card.suit) return false;
        List<Card> pile = board.foundations.get(suit);
        return card.rank == pile.size() + 1;
    }

    private static Card top(List<Card> pile) { return pile.isEmpty() ? null : pile.get(pile.size() - 1); }

    private static String cardText(Card card) { return card == null ? "empty" : card.rankText() + card.suitText(); }

    private static void reveal(int column, Board board) {
        if (board.faceUpStart[column] > 0 && board.faceUpStart[column] >= board.columns.get(column).size()) {
            board.faceUpStart[column]--;
        }
    }

    private static void draw(Board board, int count) {
        if (board.stock.isEmpty()) {
            while (!board.waste.isEmpty()) board.stock.add(board.waste.remove(board.waste.size() - 1));
            return;
        }
        for (int i = 0; i < count && !board.stock.isEmpty(); i++) {
            board.waste.add(board.stock.remove(board.stock.size() - 1));
        }
    }

    private static int parse(String value, int index) {
        try { return Integer.parseInt(value.split(":")[index]); }
        catch (RuntimeException ignored) { return -1; }
    }

    private record Card(int suit, int rank) {
        boolean color() { return suit == 1 || suit == 3; }
        String rankText() { return switch (rank) { case 1 -> "A"; case 11 -> "J"; case 12 -> "Q"; case 13 -> "K"; default -> Integer.toString(rank); }; }
        String suitText() { return switch (suit) { case 0 -> "♣"; case 1 -> "♦"; case 2 -> "♠"; default -> "♥"; }; }
    }

    private static final class Board {
        final List<Card> stock = new ArrayList<>();
        final List<Card> waste = new ArrayList<>();
        final List<List<Card>> columns = new ArrayList<>();
        final List<List<Card>> foundations = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        final int[] faceUpStart = new int[7];
        int selectedColumn = -2;
        int selectedIndex;

        Board(String difficulty) {
            List<Card> deck = new ArrayList<>();
            for (int suit = 0; suit < 4; suit++) for (int rank = 1; rank <= 13; rank++) deck.add(new Card(suit, rank));
            Collections.shuffle(deck);
            for (int column = 0; column < 7; column++) {
                List<Card> pile = new ArrayList<>();
                for (int row = 0; row <= column; row++) pile.add(deck.remove(deck.size() - 1));
                columns.add(pile);
                faceUpStart[column] = column;
            }
            stock.addAll(deck);
        }
    }
}
