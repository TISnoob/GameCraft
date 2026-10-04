package io.github.tis199.gamecraft.uno;

import io.github.tis199.gamecraft.api.GameAction;
import io.github.tis199.gamecraft.api.GameModuleDescriptor;
import io.github.tis199.gamecraft.api.GameSession;
import io.github.tis199.gamecraft.api.MenuOption;
import io.github.tis199.gamecraft.engine.ai.BotPolicy;
import io.github.tis199.gamecraft.engine.module.AbstractGameModule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/** UNO rules with human/computer seats, colored wilds, draw/skip/reverse and UNO calls. */
public final class UnoModule extends AbstractGameModule {
    private final Map<UUID, UnoGame> games = new ConcurrentHashMap<>();

    @Override public GameModuleDescriptor descriptor() {
        return new GameModuleDescriptor("uno", "UNO", "0.1.0", 1,
                "Classic color and number card game for 2–10 players.");
    }
    @Override public int minPlayers() { return 2; }
    @Override public int maxPlayers() { return 10; }
    @Override public List<String> supportedDifficulties() { return List.of("easy", "medium", "hard", "expert"); }
    @Override public boolean supportsComputer() { return true; }

    @Override
    public void onSessionStart(GameSession session) {
        UnoGame game = new UnoGame(session.totalPlayers());
        games.put(session.sessionId(), game);
        session.broadcastMessage("UNO started with " + session.totalPlayers() + " seats. Click UNO before playing your second-to-last card.");
        prompt(session);
    }

    @Override
    public void onPlayerAction(GameAction action) {
        GameSession session = services.sessions().getPlayerSession(action.playerId()).orElse(null);
        if (session == null || !session.moduleId().equals("uno")) return;
        UnoGame game = games.get(session.sessionId());
        if (game == null) return;
        synchronized (game) {
            if (!action.playerId().equals(humanAt(session, game.turn))) return;
            String choice = action.data().getOrDefault("choice", "");
            if (choice.equals("uno")) {
                if (game.hands.get(game.turn).size() == 2) game.calledUno[game.turn] = true;
            } else if (choice.equals("draw")) {
                if (game.drewThisTurn) return;
                Card drawn = draw(game);
                if (drawn == null) {
                    tell(session, action.playerId(), "No cards are available to draw.");
                    game.drewThisTurn = true;
                    game.drawnIndex = -1;
                } else {
                    game.drewThisTurn = true;
                    game.drawnIndex = game.hands.get(game.turn).size() - 1;
                }
            } else if (choice.equals("pass")) {
                if (!game.drewThisTurn) return;
                finishTurn(session, game, 1);
            } else if (choice.startsWith("play:")) {
                int index = parse(choice.substring(5));
                List<Card> hand = game.hands.get(game.turn);
                if (index < 0 || index >= hand.size() || (game.drewThisTurn && index != game.drawnIndex)) return;
                Card card = hand.get(index);
                if (!canPlay(card, game)) {
                    tell(session, action.playerId(), "That card does not match the color or symbol.");
                    return;
                }
                if (card.color == Color.WILD) {
                    game.pendingWildIndex = index;
                    openColorMenu(session, action.playerId());
                    return;
                }
                playCard(session, game, index, card.color);
            } else if (choice.startsWith("wild:")) {
                Color color = parseColor(choice.substring(5));
                if (color == null || game.pendingWildIndex < 0) return;
                int index = game.pendingWildIndex;
                game.pendingWildIndex = -1;
                playCard(session, game, index, color);
            } else {
                return;
            }
        }
        prompt(session);
    }

    @Override public void onSessionEnd(GameSession session) { games.remove(session.sessionId()); closeScene(session); }

    private void prompt(GameSession session) {
        UnoGame game = games.get(session.sessionId());
        if (game == null || session.state().name().equals("FINISHED")) return;
        if (humanAt(session, game.turn) == null) {
            renderScene(session, null, "UNO · Computer turn",
                    List.of(new MenuOption("status", "Top: " + game.top() + " • Color: " + game.activeColor,
                            List.of("Seat " + (game.turn + 1) + " is playing", "Cards in hand: "
                                    + game.hands.get(game.turn).size()))), "uno");
            if (game.botPending) return;
            game.botPending = true;
            runBot(() -> botTurn(session, game));
            return;
        }
        UUID player = humanAt(session, game.turn);
        if (player == null) return;
        List<MenuOption> options = new ArrayList<>();
        options.add(new MenuOption("status", "Top: " + game.top() + " • Color: " + game.activeColor,
                List.of("Your hand: " + game.hands.get(game.turn).size(), "Seat " + (game.turn + 1) + " / " + session.totalPlayers())));
        if (game.hands.get(game.turn).size() == 2 && !game.calledUno[game.turn]) {
            options.add(new MenuOption("uno", "Call UNO", List.of("Call before playing your next card")));
        }
        List<Card> hand = game.hands.get(game.turn);
        for (int i = 0; i < hand.size(); i++) {
            Card card = hand.get(i);
            boolean selectable = (!game.drewThisTurn || i == game.drawnIndex) && canPlay(card, game);
            options.add(new MenuOption((selectable ? "play:" : "hand:") + i,
                    (selectable ? "Play " : "Hold ") + card.label(),
                    List.of("Card " + (i + 1) + " of " + hand.size())));
        }
        if (!game.drewThisTurn) {
            options.add(new MenuOption("draw", "Draw a card", List.of()));
        } else {
            options.add(new MenuOption("pass", "End turn", List.of()));
        }
        openMenu(session, player, "UNO", options, "uno");
    }

    private void openColorMenu(GameSession session, UUID player) {
        List<MenuOption> options = List.of(
                new MenuOption("wild:red", "Choose Red", List.of()),
                new MenuOption("wild:yellow", "Choose Yellow", List.of()),
                new MenuOption("wild:green", "Choose Green", List.of()),
                new MenuOption("wild:blue", "Choose Blue", List.of()));
        openMenu(session, player, "Choose a color", options, "uno");
    }

    private void playCard(GameSession session, UnoGame game, int index, Color chosenColor) {
        List<Card> hand = game.hands.get(game.turn);
        if (index < 0 || index >= hand.size()) return;
        Card card = hand.remove(index);
        game.discard.add(card);
        game.activeColor = card.color == Color.WILD ? chosenColor : card.color;
        if (hand.isEmpty()) {
            UUID winner = humanAt(session, game.turn);
            if (winner != null) session.end(winner);
            else {
                session.broadcastMessage("A computer won UNO!");
                session.end(null);
            }
            return;
        }
        if (hand.size() == 1 && !game.calledUno[game.turn]) {
            draw(game);
            draw(game);
            session.broadcastMessage("Seat " + (game.turn + 1) + " forgot to call UNO and draws two.");
        }
        game.calledUno[game.turn] = false;
        int steps = card.kind.equals("SKIP") || card.kind.equals("DRAW2") || card.kind.equals("WILD4") ? 2 : 1;
        if (card.kind.equals("REVERSE")) {
            game.direction *= -1;
            if (session.totalPlayers() == 2) steps = 2;
        }
        if (card.kind.equals("DRAW2")) drawForNext(game, 2);
        if (card.kind.equals("WILD4")) drawForNext(game, 4);
        finishTurn(session, game, steps);
    }

    private void finishTurn(GameSession session, UnoGame game, int steps) {
        game.calledUno[game.turn] = false;
        game.turn = Math.floorMod(game.turn + game.direction * steps, session.totalPlayers());
        game.drewThisTurn = false;
        game.drawnIndex = -1;
        game.pendingWildIndex = -1;
        prompt(session);
    }

    private void drawForNext(UnoGame game, int count) {
        int next = Math.floorMod(game.turn + game.direction, game.hands.size());
        for (int i = 0; i < count; i++) drawTo(game, next);
    }

    private void botTurn(GameSession session, UnoGame game) {
        synchronized (game) {
            if (session.state().name().equals("FINISHED")) return;
            game.botPending = false;
            List<Card> hand = game.hands.get(game.turn);
            List<Integer> legal = new ArrayList<>();
            for (int i = 0; i < hand.size(); i++) if (canPlay(hand.get(i), game)) legal.add(i);
            if (legal.isEmpty()) {
                Card drawn = draw(game);
                if (drawn == null) {
                    game.turn = Math.floorMod(game.turn + game.direction, session.totalPlayers());
                    game.drewThisTurn = false;
                    game.drawnIndex = -1;
                    prompt(session);
                    return;
                }
                int last = hand.size() - 1;
                if (!canPlay(hand.get(last), game)) {
                    game.turn = Math.floorMod(game.turn + game.direction, session.totalPlayers());
                    prompt(session);
                    return;
                }
                legal = List.of(last);
            }
            int seat = game.turn;
            int chosen = BotPolicy.choose(legal, session.difficulty(), index -> cardScore(hand.get(index), game));
            Card card = hand.get(chosen);
            if (hand.size() == 2) game.calledUno[seat] = true;
            Color color = card.color == Color.WILD ? mostCommonColor(hand) : card.color;
            playCard(session, game, chosen, color);
        }
        prompt(session);
    }

    private static int cardScore(Card card, UnoGame game) {
        int score = card.color == Color.WILD ? 10 : 0;
        score += switch (card.kind) { case "WILD4" -> 8; case "DRAW2", "SKIP", "REVERSE" -> 5; default -> 0; };
        return score + (card.color == game.activeColor ? 1 : 0);
    }

    private static Color mostCommonColor(List<Card> hand) {
        Map<Color, Integer> counts = new EnumMap<>(Color.class);
        for (Card card : hand) if (card.color != Color.WILD) counts.merge(card.color, 1, Integer::sum);
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(Color.RED);
    }

    private static boolean canPlay(Card card, UnoGame game) {
        return card.color == Color.WILD || card.color == game.activeColor || card.kind.equals(game.topKind());
    }

    private static Card draw(UnoGame game) {
        if (game.drawPile.isEmpty()) {
            if (game.discard.size() <= 1) return null;
            Card top = game.discard.remove(game.discard.size() - 1);
            game.drawPile.addAll(game.discard);
            game.discard.clear();
            game.discard.add(top);
            Collections.shuffle(game.drawPile);
        }
        return game.drawPile.isEmpty() ? null : drawTo(game, game.turn);
    }

    private static Card drawTo(UnoGame game, int seat) {
        if (game.drawPile.isEmpty()) {
            if (game.discard.size() <= 1) return null;
            Card top = game.discard.remove(game.discard.size() - 1);
            game.drawPile.addAll(game.discard);
            game.discard.clear();
            game.discard.add(top);
            Collections.shuffle(game.drawPile);
        }
        if (game.drawPile.isEmpty()) return null;
        Card card = game.drawPile.remove(game.drawPile.size() - 1);
        game.hands.get(seat).add(card);
        return card;
    }

    private static Color parseColor(String value) {
        try { return Color.valueOf(value.toUpperCase(java.util.Locale.ROOT)); }
        catch (RuntimeException ignored) { return null; }
    }

    private static int parse(String value) {
        try { return Integer.parseInt(value); } catch (RuntimeException ignored) { return -1; }
    }

    private enum Color { RED, YELLOW, GREEN, BLUE, WILD }
    private record Card(Color color, String kind) {
        String label() { return color == Color.WILD ? kind : color.name() + " " + kind; }
    }

    private static final class UnoGame {
        final List<Card> drawPile = new ArrayList<>();
        final List<Card> discard = new ArrayList<>();
        final List<List<Card>> hands = new ArrayList<>();
        final boolean[] calledUno;
        int turn;
        int direction = 1;
        int drawnIndex = -1;
        int pendingWildIndex = -1;
        boolean drewThisTurn;
        boolean botPending;
        Color activeColor;

        UnoGame(int players) {
            calledUno = new boolean[players];
            for (Color color : List.of(Color.RED, Color.YELLOW, Color.GREEN, Color.BLUE)) {
                for (int value = 0; value <= 9; value++) {
                    drawPile.add(new Card(color, Integer.toString(value)));
                    if (value > 0) drawPile.add(new Card(color, Integer.toString(value)));
                }
                for (String action : List.of("SKIP", "REVERSE", "DRAW2")) {
                    drawPile.add(new Card(color, action));
                    drawPile.add(new Card(color, action));
                }
            }
            for (int i = 0; i < 4; i++) {
                drawPile.add(new Card(Color.WILD, "WILD"));
                drawPile.add(new Card(Color.WILD, "WILD4"));
            }
            Collections.shuffle(drawPile);
            for (int i = 0; i < players; i++) hands.add(new ArrayList<>());
            for (int i = 0; i < 7; i++) for (int p = 0; p < players; p++) drawTo(this, p);
            Card first = drawPile.remove(drawPile.size() - 1);
            if (first.color == Color.WILD) {
                activeColor = Color.values()[ThreadLocalRandom.current().nextInt(4)];
            } else {
                activeColor = first.color;
            }
            discard.add(first);
        }

        Card top() { return discard.get(discard.size() - 1); }
        String topKind() { return top().kind; }
    }
}
