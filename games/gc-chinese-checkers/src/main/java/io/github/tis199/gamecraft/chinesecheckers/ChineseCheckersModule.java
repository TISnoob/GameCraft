package io.github.tis199.gamecraft.chinesecheckers;

import io.github.tis199.gamecraft.api.GameAction;
import io.github.tis199.gamecraft.api.GameModuleDescriptor;
import io.github.tis199.gamecraft.api.GameSession;
import io.github.tis199.gamecraft.api.MenuOption;
import io.github.tis199.gamecraft.engine.ai.BotPolicy;
import io.github.tis199.gamecraft.engine.module.AbstractGameModule;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Chinese Checkers with 2–6 seats and a distance-greedy computer player. */
public final class ChineseCheckersModule extends AbstractGameModule {
    private final Map<UUID, StarGame> games = new ConcurrentHashMap<>();

    @Override public GameModuleDescriptor descriptor() {
        return new GameModuleDescriptor("chinese-checkers", "Chinese Checkers", "0.1.0", 1,
                "Chinese Checkers on a 121-hole star board for 2–6 players.");
    }
    @Override public int minPlayers() { return 2; }
    @Override public int maxPlayers() { return 6; }
    @Override public List<String> supportedDifficulties() { return List.of("easy", "medium", "hard", "expert"); }
    @Override public boolean supportsComputer() { return true; }

    @Override public void onSessionStart(GameSession session) {
        games.put(session.sessionId(), new StarGame(session.totalPlayers()));
        session.broadcastMessage("Chinese Checkers started. Move into the opposite point of the star.");
        prompt(session);
    }

    @Override
    public void onPlayerAction(GameAction action) {
        GameSession session = services.sessions().getPlayerSession(action.playerId()).orElse(null);
        if (session == null || !session.moduleId().equals("chinese-checkers")) return;
        StarGame game = games.get(session.sessionId());
        if (game == null) return;
        synchronized (game) {
            if (!action.playerId().equals(humanAt(session, game.turn))) return;
            String choice = action.data().getOrDefault("choice", "");
            if (choice.equals("back")) {
                game.selected = null;
            } else if (choice.equals("pass")) {
                if (!game.board.legalMoves(game.turn).isEmpty()) return;
                nextTurn(session, game);
            } else if (choice.startsWith("piece:")) {
                game.selected = parsePosition(choice.substring(6));
                if (game.selected == null || !Integer.valueOf(game.turn).equals(game.board.pieces().get(game.selected))) return;
                promptMoves(session, game, action.playerId());
                return;
            } else if (choice.startsWith("to:")) {
                HexPosition target = parsePosition(choice.substring(3));
                if (game.selected == null || target == null || !game.board.isValidMove(game.selected, target, game.turn)) return;
                game.board.move(game.selected, target, game.turn);
                game.selected = null;
                if (game.board.hasPlayerWon(game.turn)) {
                    session.end(humanAt(session, game.turn));
                    return;
                }
                nextTurn(session, game);
            } else {
                return;
            }
        }
        prompt(session);
    }

    @Override public void onSessionEnd(GameSession session) { games.remove(session.sessionId()); closeScene(session); }

    private void prompt(GameSession session) {
        StarGame game = games.get(session.sessionId());
        if (game == null || session.state().name().equals("FINISHED")) return;
        if (!isHumanSeat(session, game.turn)) {
            renderScene(session, null, "Computer thinking · Chinese Checkers",
                    List.of(new MenuOption("status", "Chinese Checkers • seat " + (game.turn + 1),
                            boardSummary(game.board))), "chinese-checkers");
            if (!game.botPending) {
                game.botPending = true;
                runBot(() -> botTurn(session, game));
            }
            return;
        }
        UUID player = humanAt(session, game.turn);
        if (game.selected != null) {
            promptMoves(session, game, player);
            return;
        }
        List<MenuOption> options = new ArrayList<>();
        options.add(new MenuOption("status", "Chinese Checkers • seat " + (game.turn + 1),
                boardSummary(game.board)));
        for (Map.Entry<HexPosition, Integer> piece : game.board.pieces().entrySet()) {
            if (piece.getValue() == game.turn) {
                options.add(new MenuOption("piece:" + encode(piece.getKey()),
                        "Piece at " + encode(piece.getKey()), List.of("Select piece")));
            }
        }
        if (game.board.legalMoves(game.turn).isEmpty()) {
            options.add(new MenuOption("pass", "No moves • Pass turn", List.of()));
        }
        openMenu(session, player, "Chinese Checkers", options, "chinese-checkers");
    }

    private void promptMoves(GameSession session, StarGame game, UUID player) {
        if (game.selected == null) return;
        List<HexPosition> destinations = game.board.legalDestinations(game.selected, game.turn);
        List<MenuOption> options = new ArrayList<>();
        options.add(new MenuOption("status", "Chinese Checkers • seat " + (game.turn + 1),
                boardSummary(game.board)));
        options.add(new MenuOption("back", "From " + encode(game.selected), List.of("Choose a legal landing hole")));
        for (HexPosition destination : destinations) {
            options.add(new MenuOption("to:" + encode(destination), "Move to " + encode(destination), List.of()));
        }
        openMenu(session, player, "Choose landing hole", options, "chinese-checkers");
    }

    private void botTurn(GameSession session, StarGame game) {
        synchronized (game) {
            game.botPending = false;
            if (session.state().name().equals("FINISHED")) return;
            List<ChineseCheckersBoard.Move> moves = game.board.legalMoves(game.turn);
            if (moves.isEmpty()) {
                nextTurn(session, game);
            } else {
                int seat = game.turn;
                ChineseCheckersBoard.Move move = BotPolicy.choose(moves, session.difficulty(),
                        candidate -> game.board.scoreMove(candidate, seat));
                game.board.move(move.from(), move.to(), seat);
                if (game.board.hasPlayerWon(seat)) {
                    UUID winner = humanAt(session, seat);
                    if (winner == null) session.broadcastMessage("A computer won Chinese Checkers!");
                    session.end(winner);
                    return;
                }
                nextTurn(session, game);
            }
        }
        prompt(session);
    }

    private static void nextTurn(GameSession session, StarGame game) {
        game.selected = null;
        game.turn = (game.turn + 1) % session.totalPlayers();
    }

    private static HexPosition parsePosition(String value) {
        try {
            String[] parts = value.split(",");
            return parts.length == 2 ? new HexPosition(Integer.parseInt(parts[0]), Integer.parseInt(parts[1])) : null;
        } catch (RuntimeException ignored) { return null; }
    }

    private static String encode(HexPosition position) { return position.q() + "," + position.r(); }

    private static List<String> boardSummary(ChineseCheckersBoard board) {
        List<String> lines = new ArrayList<>();
        lines.add("Choose one of your pieces; its opposite point is the goal.");
        for (int seat = 0; seat < board.playerCount(); seat++) {
            int player = seat;
            String positions = board.pieces().entrySet().stream()
                    .filter(entry -> entry.getValue() == player)
                    .map(entry -> encode(entry.getKey())).sorted().reduce((a, b) -> a + "  " + b).orElse("none");
            lines.add("Seat " + (seat + 1) + " pieces: " + positions);
        }
        return lines;
    }

    private static final class StarGame {
        final ChineseCheckersBoard board;
        int turn;
        HexPosition selected;
        boolean botPending;
        StarGame(int players) { board = new ChineseCheckersBoard(players); }
    }
}
