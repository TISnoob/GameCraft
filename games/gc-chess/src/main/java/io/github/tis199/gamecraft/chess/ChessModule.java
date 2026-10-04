package io.github.tis199.gamecraft.chess;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;
import io.github.tis199.gamecraft.api.EngineConfig;
import io.github.tis199.gamecraft.api.GameAction;
import io.github.tis199.gamecraft.api.GameModuleContext;
import io.github.tis199.gamecraft.api.GameModuleDescriptor;
import io.github.tis199.gamecraft.api.GameSession;
import io.github.tis199.gamecraft.api.MenuOption;
import io.github.tis199.gamecraft.api.ModuleConfiguration;
import io.github.tis199.gamecraft.engine.ai.UciEngine;
import io.github.tis199.gamecraft.engine.module.AbstractGameModule;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/** Legal standard chess. Computer play requires the configured Stockfish-compatible UCI engine. */
public final class ChessModule extends AbstractGameModule {
    private static final Pattern SHA_256 = Pattern.compile("[a-fA-F0-9]{64}");
    private final Map<UUID, ChessMatch> matches = new ConcurrentHashMap<>();
    private final Logger logger = Logger.getLogger("GameCraft");
    private StockfishManager engines;
    private Path moduleData;
    private ModuleConfiguration moduleConfig;
    private String enginePath;
    private String engineUrl;
    private String engineSha256;
    private int maxThreads;
    private int maxMemoryMb;
    private int threads;
    private int hashMb;
    private int timeoutMs;

    @Override public GameModuleDescriptor descriptor() {
        return new GameModuleDescriptor("chess", "Chess", "0.2.0", 1,
                "Legal standard chess, human multiplayer, and Stockfish computer play.");
    }
    @Override public int minPlayers() { return 2; }
    @Override public int maxPlayers() { return 2; }
    @Override public List<String> supportedDifficulties() { return List.of("easy", "medium", "hard", "expert"); }
    @Override public boolean supportsComputer() { return true; }

    @Override
    public void onLoad(GameModuleContext context) {
        super.onLoad(context);
        moduleData = context.dataFolder();
        moduleConfig = context.configuration();
        enginePath = context.configuration().getString("engine.path").orElse("engines/stockfish-16");
        engineUrl = context.configuration().getString("engine.download-url").orElse("").trim();
        engineSha256 = context.configuration().getString("engine.download-sha256").orElse("").trim();
        Path configured = Path.of(enginePath);
        if (!configured.isAbsolute()) configured = moduleData.resolve(configured);
        if (!Files.isRegularFile(configured) && engineUrl.isBlank()) {
            throw new IllegalStateException("Stockfish is required for Chess. Install its executable at "
                    + configured.toAbsolutePath().normalize() + " or configure an HTTPS binary URL and SHA-256 in games/chess.yml.");
        }
        if (!engineUrl.isBlank() && !SHA_256.matcher(engineSha256).matches()) {
            throw new IllegalStateException("Chess engine downloads require the exact 64-character engine.download-sha256 checksum.");
        }

        int availableThreads = Math.max(1, Runtime.getRuntime().availableProcessors());
        maxThreads = Math.max(1, Math.min(availableThreads,
                context.configuration().getInt("engine.max-threads", 2)));
        maxMemoryMb = Math.max(16, Math.min(65_536,
                context.configuration().getInt("engine.max-memory-mb", 128)));
        threads = Math.max(1, Math.min(maxThreads,
                context.configuration().getInt("engine.options.threads", 2)));
        hashMb = Math.max(16, Math.min(maxMemoryMb,
                context.configuration().getInt("engine.options.hash-mb", 128)));
        timeoutMs = Math.max(1000, context.configuration().getInt("engine.timeout-ms", 15000));
        engines = new StockfishManager(logger);
    }

    @Override public void onSessionStart(GameSession session) {
        matches.put(session.sessionId(), new ChessMatch());
        session.broadcastMessage("<gold>♟ Chess is on!</gold> <gray>Click one of your pieces, then click a glowing legal destination.</gray>");
        prompt(session);
    }

    @Override
    public void onPlayerAction(GameAction action) {
        GameSession session = services.sessions().getPlayerSession(action.playerId()).orElse(null);
        if (session == null || !session.moduleId().equals("chess")) return;
        ChessMatch match = matches.get(session.sessionId());
        if (match == null) return;
        if ("draw-offer".equals(action.data().get("choice"))) {
            synchronized (match) {
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
        boolean changed = false;
        synchronized (match) {
            Side side = match.board.getSideToMove();
            if (!action.playerId().equals(humanAt(session, seat(side)))) return;
            String choice = action.data().getOrDefault("choice", "");
            if (choice.startsWith("square:")) {
                Square square = parseSquare(choice.substring("square:".length()));
                if (square == null) return;
                Piece piece = match.board.getPiece(square);
                if (match.selected == square) {
                    match.selected = null;
                    match.pendingPromotion = null;
                } else if (piece != Piece.NONE && piece.getPieceSide() == side) {
                    match.selected = square;
                    match.pendingPromotion = null;
                } else {
                    match.selected = null;
                    match.pendingPromotion = null;
                }
                changed = true;
            } else if (choice.startsWith("destination:")) {
                Square destination = parseSquare(choice.substring("destination:".length()));
                if (match.selected == null || destination == null) return;
                List<Move> candidates = legalFrom(match.board, match.selected).stream()
                        .filter(move -> move.getTo() == destination).toList();
                if (candidates.isEmpty()) return;
                if (candidates.size() == 1) {
                    applyMove(session, match, candidates.get(0));
                } else {
                    match.pendingPromotion = destination;
                }
                changed = true;
            } else if (choice.startsWith("promote:")) {
                if (match.selected == null || match.pendingPromotion == null) return;
                String piece = choice.substring("promote:".length());
                Move promotion = legalFrom(match.board, match.selected).stream()
                        .filter(move -> move.getTo() == match.pendingPromotion)
                        .filter(move -> move.getPromotion() != Piece.NONE)
                        .filter(move -> move.getPromotion().getFenSymbol().equalsIgnoreCase(piece))
                        .findFirst().orElse(null);
                if (promotion == null) return;
                applyMove(session, match, promotion);
                changed = true;
            }
        }
        if (changed) prompt(session);
    }

    @Override public void onSessionEnd(GameSession session) { matches.remove(session.sessionId()); closeScene(session); }

    @Override
    public void onDisable() {
        if (engines != null) engines.closeAll();
        matches.clear();
        closeScenes();
    }

    private void prompt(GameSession session) {
        ChessMatch match = matches.get(session.sessionId());
        if (match == null || session.state().name().equals("FINISHED")) return;
        Side side = match.board.getSideToMove();
        int seat = seat(side);
        if (!isHumanSeat(session, seat)) {
            String turnName = side == Side.WHITE ? "White" : "Black";
            renderScene(session, null, "Stockfish is thinking · " + turnName,
                    List.of(new MenuOption("status", turnName + " to move", boardLines(match.board))), "chess");
            if (!match.botPending) {
                match.botPending = true;
                requestComputerMove(session, match);
            }
            return;
        }

        List<MenuOption> options = new ArrayList<>();
        String turnName = side == Side.WHITE ? "White" : "Black";
        options.add(new MenuOption("status", turnName + " to move", boardLines(match.board)));
        if (match.pendingPromotion != null) {
            options.add(new MenuOption("promote:q", "Promote to Queen", List.of("Recommended")));
            options.add(new MenuOption("promote:r", "Promote to Rook", List.of()));
            options.add(new MenuOption("promote:b", "Promote to Bishop", List.of()));
            options.add(new MenuOption("promote:n", "Promote to Knight", List.of()));
        } else {
            if (match.selected != null) {
                options.add(new MenuOption("selected:" + toLower(match.selected),
                        "Selected " + toLower(match.selected), List.of()));
            }
            for (int rank = 1; rank <= 8; rank++) {
                for (char file = 'a'; file <= 'h'; file++) {
                    Square square = parseSquare("" + file + rank);
                    Piece piece = match.board.getPiece(square);
                    if (piece != Piece.NONE && piece.getPieceSide() == side) {
                        options.add(new MenuOption("square:" + ("" + file + rank),
                                piece.getPieceType().name() + " on " + file + rank,
                                List.of("Select this piece")));
                    }
                }
            }
            if (match.selected != null) {
                for (Move move : legalFrom(match.board, match.selected)) {
                    options.add(new MenuOption("destination:" + toLower(move.getTo()),
                            "Legal move to " + toLower(move.getTo()), List.of("Glowing marker on the board")));
                }
            }
        }
        renderScene(session, humanAt(session, seat), turnName + " to move", options, "chess");
    }

    private void requestComputerMove(GameSession session, ChessMatch match) {
        services.scheduler().runAsync(() -> {
            String best = null;
            String position;
            synchronized (match) { position = match.board.getFen(); }
            Exception failure = null;
            try {
                UciEngine engine = engines.getOrCreateEngine("stockfish", engineConfig());
                int defaultTime = switch (session.difficulty()) {
                    case "easy" -> 500;
                    case "hard" -> 3000;
                    case "expert" -> 5000;
                    default -> 1500;
                };
                int moveTime = Math.min(60_000, Math.max(100, moduleConfig.getInt(
                        "engine.difficulty." + session.difficulty() + ".move-time-ms", defaultTime)));
                best = engine.compute(position, "go movetime " + moveTime).toCompletableFuture()
                        .get(timeoutMs + 1000L, TimeUnit.MILLISECONDS);
            } catch (Exception exception) {
                failure = exception;
            }
            String engineMove = best;
            Exception engineFailure = failure;
            services.scheduler().runGlobal(() -> {
                synchronized (match) {
                    if (session.state().name().equals("FINISHED")) return;
                    match.botPending = false;
                    if (engineFailure != null) {
                        logger.severe("Stockfish failed during Chess; ending the match instead of fabricating a move: "
                                + engineFailure.getMessage());
                        session.broadcastMessage("<red>Stockfish stopped responding. This Chess match has been ended safely.</red>");
                        session.end(null);
                        return;
                    }
                    if (!match.board.getFen().equals(position)) {
                        logger.warning("Discarding a stale Stockfish result for Chess session " + session.sessionId());
                        return;
                    }
                    Move move = findLegal(match.board, engineMove);
                    if (move == null) {
                        logger.severe("Stockfish returned no legal move ('" + engineMove + "'); ending the Chess match.");
                        session.broadcastMessage("<red>Stockfish returned an invalid move. This Chess match has been ended safely.</red>");
                        session.end(null);
                        return;
                    }
                    String played = move.toString();
                    applyMove(session, match, move);
                    if (session.state().name().equals("IN_PROGRESS")) {
                        session.broadcastMessage("The computer played " + moveLabel(played) + ".");
                    }
                }
                prompt(session);
            });
        });
    }

    private EngineConfig engineConfig() {
        Path path = Path.of(enginePath);
        if (!path.isAbsolute()) path = moduleData.resolve(path);
        return new EngineConfig(path.normalize().toString(), engineUrl, engineSha256,
                Map.of("Threads", Integer.toString(threads), "Hash", Integer.toString(hashMb)),
                maxThreads, maxMemoryMb, timeoutMs);
    }

    private void applyMove(GameSession session, ChessMatch match, Move move) {
        match.board.doMove(move);
        match.selected = null;
        match.pendingPromotion = null;
        finishIfOver(session, match);
    }

    private void finishIfOver(GameSession session, ChessMatch match) {
        if (match.board.isMated()) {
            Side winnerSide = match.board.getSideToMove() == Side.WHITE ? Side.BLACK : Side.WHITE;
            UUID winner = humanAt(session, seat(winnerSide));
            session.broadcastMessage(winner == null ? "The computer won by checkmate." : "Checkmate!");
            session.end(winner);
        } else if (match.board.isDraw() || match.board.isStaleMate()) {
            session.broadcastMessage("Chess ended in a draw.");
            session.end(null);
        }
    }

    private static Move findLegal(Board board, String uci) {
        if (uci == null || uci.isBlank()) return null;
        for (Move move : board.legalMoves()) if (move.toString().equalsIgnoreCase(uci)) return move;
        return null;
    }

    private static List<Move> legalFrom(Board board, Square square) {
        return board.legalMoves().stream().filter(move -> move.getFrom() == square).toList();
    }

    private static Square parseSquare(String value) {
        if (value == null || !value.matches("(?i)[a-h][1-8]")) return null;
        try { return Square.valueOf(value.toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    private static String toLower(Square square) { return square.toString().toLowerCase(java.util.Locale.ROOT); }
    private static int seat(Side side) { return side == Side.WHITE ? 0 : 1; }

    private static List<String> boardLines(Board board) {
        List<String> lines = new ArrayList<>();
        for (int rank = 8; rank >= 1; rank--) {
            StringBuilder line = new StringBuilder().append(rank).append(" | ");
            for (char file = 'a'; file <= 'h'; file++) {
                Piece piece = board.getPiece(Square.valueOf(("" + file + rank).toUpperCase(java.util.Locale.ROOT)));
                line.append(piece == Piece.NONE ? '.' : piece.getFenSymbol()).append(' ');
            }
            lines.add(line.append('|').toString());
        }
        lines.add("Files: a b c d e f g h • Click a piece, then a glowing destination.");
        return lines;
    }

    private static String moveLabel(String uci) {
        if (uci == null || uci.length() < 4) return "Legal move";
        return uci.substring(0, 2).toUpperCase() + " → " + uci.substring(2, 4).toUpperCase()
                + (uci.length() == 5 ? " = " + uci.substring(4).toUpperCase() : "");
    }

    private static final class ChessMatch {
        final Board board = new Board();
        Square selected;
        Square pendingPromotion;
        boolean botPending;
        UUID drawOfferedBy;
    }
}
