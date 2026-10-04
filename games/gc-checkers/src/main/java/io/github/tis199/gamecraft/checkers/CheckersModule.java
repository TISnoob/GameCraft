package io.github.tis199.gamecraft.checkers;

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

/** American checkers with complete legal move selection and a difficulty-scaled bot. */
public final class CheckersModule extends AbstractGameModule {
    private final Map<UUID, Match> matches = new ConcurrentHashMap<>();

    @Override public GameModuleDescriptor descriptor() {
        return new GameModuleDescriptor("checkers", "Checkers", "0.1.0", 1,
                "American checkers with compulsory captures, multi-jumps, and kings.");
    }
    @Override public int minPlayers() { return 2; }
    @Override public int maxPlayers() { return 2; }
    @Override public List<String> supportedDifficulties() { return List.of("easy", "medium", "hard", "expert"); }
    @Override public boolean supportsComputer() { return true; }

    @Override public void onSessionStart(GameSession session) {
        matches.put(session.sessionId(), new Match());
        session.broadcastMessage("Checkers started. Captures are mandatory; complete a full multi-jump in one move.");
        prompt(session);
    }

    @Override
    public void onPlayerAction(GameAction action) {
        GameSession session = services.sessions().getPlayerSession(action.playerId()).orElse(null);
        if (session == null || !session.moduleId().equals("checkers")) return;
        Match match = matches.get(session.sessionId());
        if (match == null) return;
        if ("draw-offer".equals(action.data().get("choice"))) {
            synchronized (match) {
                if (humanAt(session, 0) == null || humanAt(session, 1) == null) return;
                if (match.drawOfferedBy != null && !match.drawOfferedBy.equals(action.playerId())) {
                    match.drawOfferedBy = null;
                    session.broadcastMessage("<gold>½ The draw was accepted. The game is a draw.</gold>");
                    session.end(null);
                    return;
                }
                if (match.drawOfferedBy == null) {
                    match.drawOfferedBy = action.playerId();
                    session.broadcastMessage("<yellow>½ Draw offered. The other player can accept from the hotbar.</yellow>");
                }
            }
            return;
        }
        synchronized (match) {
            if (!action.playerId().equals(humanAt(session, match.turn))) return;
            int index = parse(action.data().getOrDefault("choice", ""));
            List<CheckersBoard.Move> legal = match.board.legalMoves(match.turn);
            if (index < 0 || index >= legal.size()) return;
            applyMove(session, match, legal.get(index));
        }
        prompt(session);
    }

    @Override public void onSessionEnd(GameSession session) { matches.remove(session.sessionId()); closeScene(session); }

    private void prompt(GameSession session) {
        Match match = matches.get(session.sessionId());
        if (match == null || session.state().name().equals("FINISHED")) return;
        List<CheckersBoard.Move> legal = match.board.legalMoves(match.turn);
        if (legal.isEmpty()) {
            UUID winner = humanAt(session, 1 - match.turn);
            if (winner == null) session.broadcastMessage("A computer won checkers!");
            session.end(winner);
            return;
        }
        if (!isHumanSeat(session, match.turn)) {
            renderScene(session, null, "Computer thinking · Checkers",
                    List.of(new MenuOption("board", "Checkers • Computer", boardLines(match.board, legal.size()))), "checkers");
            if (!match.botPending) {
                match.botPending = true;
                runBot(() -> botTurn(session, match));
            }
            return;
        }
        List<MenuOption> options = new ArrayList<>();
        options.add(new MenuOption("board", "Checkers • " + (match.turn == 0 ? "Red" : "Black"),
                boardLines(match.board, legal.size())));
        for (int i = 0; i < legal.size(); i++) {
            CheckersBoard.Move move = legal.get(i);
            options.add(new MenuOption("move:" + i, move.label(), List.of(
                    move.captures() > 0 ? "Capture " + move.captures() + " piece(s)" : "Move")));
        }
        openMenu(session, humanAt(session, match.turn), "Checkers", options, "checkers");
    }

    private void botTurn(GameSession session, Match match) {
        synchronized (match) {
            match.botPending = false;
            if (session.state().name().equals("FINISHED")) return;
            List<CheckersBoard.Move> legal = match.board.legalMoves(match.turn);
            if (legal.isEmpty()) {
                session.end(humanAt(session, 1 - match.turn));
                return;
            }
            int seat = match.turn;
            CheckersBoard.Move choice = BotPolicy.choose(legal, session.difficulty(), move -> {
                CheckersBoard.Square end = move.path().get(move.path().size() - 1);
                int advance = seat == 0 ? end.rank() : 7 - end.rank();
                return move.captures() * 100 + advance + (end.file() >= 2 && end.file() <= 5 ? 2 : 0);
            });
            applyMove(session, match, choice);
        }
        prompt(session);
    }

    private void applyMove(GameSession session, Match match, CheckersBoard.Move move) {
        int mover = match.turn;
        match.board.move(move, mover);
        if (move.captures() > 0) session.broadcastMessage("Seat " + (mover + 1) + " captured " + move.captures() + " piece(s).");
        if (!hasPieces(match.board, 1 - mover) || match.board.legalMoves(1 - mover).isEmpty()) {
            UUID winner = humanAt(session, mover);
            if (winner == null) session.broadcastMessage("A computer won checkers!");
            session.end(winner);
            return;
        }
        match.turn = 1 - match.turn;
    }

    private static boolean hasPieces(CheckersBoard board, int player) {
        for (int rank = 0; rank < 8; rank++) for (int file = 0; file < 8; file++) {
            CheckersPiece piece = board.getPiece(rank, file);
            if (piece != null && piece.isRed() == (player == 0)) return true;
        }
        return false;
    }

    private static List<String> boardLines(CheckersBoard board, int legalMoves) {
        List<String> lines = new ArrayList<>();
        lines.add("Legal moves: " + legalMoves + " • R/B are kings");
        for (int rank = 7; rank >= 0; rank--) {
            StringBuilder line = new StringBuilder().append(rank + 1).append(" | ");
            for (int file = 0; file < 8; file++) {
                CheckersPiece piece = board.getPiece(rank, file);
                line.append(piece == null ? '.' : switch (piece) {
                    case RED -> 'r';
                    case BLACK -> 'b';
                    case RED_KING -> 'R';
                    case BLACK_KING -> 'B';
                }).append(' ');
            }
            lines.add(line.append('|').toString());
        }
        lines.add("Files: a b c d e f g h");
        return lines;
    }

    private static int parse(String choice) {
        if (!choice.startsWith("move:")) return -1;
        try { return Integer.parseInt(choice.substring(5)); } catch (RuntimeException ignored) { return -1; }
    }

    private static final class Match {
        final CheckersBoard board = new CheckersBoard();
        int turn;
        boolean botPending;
        UUID drawOfferedBy;
    }
}
