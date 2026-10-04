package io.github.tis199.gamecraft.ludo;

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
import java.util.concurrent.ThreadLocalRandom;

/** Multiplayer Ludo with optional computer-controlled seats. */
public final class LudoModule extends AbstractGameModule {
    private final Map<UUID, LudoGame> games = new ConcurrentHashMap<>();

    @Override public GameModuleDescriptor descriptor() {
        return new GameModuleDescriptor("ludo", "Ludo", "0.1.0", 1,
                "Classic four-token Ludo for 2–4 players.");
    }
    @Override public int minPlayers() { return 2; }
    @Override public int maxPlayers() { return 4; }
    @Override public List<String> supportedDifficulties() { return List.of("easy", "medium", "hard", "expert"); }
    @Override public boolean supportsComputer() { return true; }

    @Override public void onSessionStart(GameSession session) {
        boolean teams = Boolean.parseBoolean(session.properties().getOrDefault("team-mode", "false"));
        games.put(session.sessionId(), new LudoGame(session.totalPlayers(), teams));
        session.broadcastMessage(teams
                ? "<gold>✦ Team Ludo is on!</gold> <red>Red + Blue</red> <gray>vs</gray> <yellow>Yellow + Green</yellow>. Roll a six to launch a token."
                : "<gold>✦ Ludo is on!</gold> <gray>Roll a six to launch a token. Exact rolls finish the race.</gray>");
        prompt(session);
    }

    @Override
    public void onPlayerAction(GameAction action) {
        GameSession session = services.sessions().getPlayerSession(action.playerId()).orElse(null);
        if (session == null || !session.moduleId().equals("ludo")) return;
        LudoGame game = games.get(session.sessionId());
        if (game == null) return;
        synchronized (game) {
            if (!action.playerId().equals(humanAt(session, game.turn))) return;
            String choice = action.data().getOrDefault("choice", "");
            if (choice.equals("roll") && game.dice == 0) {
                game.dice = ThreadLocalRandom.current().nextInt(1, 7);
                game.legal = game.board.legalTokens(game.turn, game.dice);
                if (game.legal.isEmpty()) {
                    tell(session, action.playerId(), "You rolled " + game.dice + "; no token can move.");
                    nextTurn(session, game, game.dice == 6);
                    return;
                }
            } else if (choice.startsWith("token:")) {
                int token = parse(choice.substring(6));
                if (!game.legal.contains(token)) return;
                move(session, game, token);
            } else {
                return;
            }
        }
        prompt(session);
    }

    @Override public void onSessionEnd(GameSession session) { games.remove(session.sessionId()); closeScene(session); }

    private void prompt(GameSession session) {
        LudoGame game = games.get(session.sessionId());
        if (game == null || session.state().name().equals("FINISHED")) return;
        if (!isHumanSeat(session, game.turn)) {
            renderScene(session, null, "Computer turn · Ludo", List.of(new MenuOption(
                    "status", "Ludo • seat " + (game.turn + 1), summary(game, session.totalPlayers()))), "ludo");
            if (!game.botPending) {
                game.botPending = true;
                runBot(() -> botTurn(session, game));
            }
            return;
        }
        UUID player = humanAt(session, game.turn);
        List<MenuOption> options = new ArrayList<>();
        options.add(new MenuOption("status", "Ludo • seat " + (game.turn + 1), summary(game, session.totalPlayers())));
        if (game.dice == 0) {
            options.add(new MenuOption("roll", "Roll the die", List.of()));
        } else {
            for (int token : game.legal) {
                String label = game.board.progress(game.turn, token) < 0
                        ? "Move token " + (token + 1) + " out of base"
                        : "Move token " + (token + 1) + " to " + (game.board.progress(game.turn, token) + game.dice);
                options.add(new MenuOption("token:" + token, label, List.of()));
            }
        }
        openMenu(session, player, "Ludo", options, "ludo");
    }

    private void botTurn(GameSession session, LudoGame game) {
        synchronized (game) {
            game.botPending = false;
            if (session.state().name().equals("FINISHED")) return;
            if (game.dice == 0) {
                game.dice = ThreadLocalRandom.current().nextInt(1, 7);
                game.legal = game.board.legalTokens(game.turn, game.dice);
            }
            if (game.legal.isEmpty()) {
                nextTurn(session, game, game.dice == 6);
            } else {
                int seat = game.turn;
                int token = BotPolicy.choose(game.legal, session.difficulty(),
                        candidate -> scoreToken(game.board, seat, candidate, game.dice));
                move(session, game, token);
            }
        }
        prompt(session);
    }

    private void move(GameSession session, LudoGame game, int token) {
        int seat = game.turn;
        int dice = game.dice;
        int captures = game.board.move(seat, token, dice);
        if (game.board.hasPlayerWon(seat)) {
            UUID winner = humanAt(session, seat);
            if (game.board.teamMode()) {
                session.broadcastMessage(game.board.teamOf(seat) == 0
                        ? "<gold>🏆 Red + Blue win Team Ludo!</gold>"
                        : "<gold>🏆 Yellow + Green win Team Ludo!</gold>");
            } else {
                session.broadcastMessage(winner == null ? "<gold>🏆 A computer won Ludo!</gold>"
                        : "<gold>🏆 " + (new String[]{"Red", "Yellow", "Blue", "Green"}[seat]) + " wins Ludo!</gold>");
            }
            session.end(winner);
            return;
        }
        if (captures > 0) session.broadcastMessage("<gold>✦ Seat " + (seat + 1) + " captured " + captures + " token(s)!</gold>");
        boolean extraRoll = dice == 6 || captures > 0;
        nextTurn(session, game, extraRoll);
    }

    private void nextTurn(GameSession session, LudoGame game, boolean extraRoll) {
        if (!extraRoll) game.turn = (game.turn + 1) % session.totalPlayers();
        game.dice = 0;
        game.legal = List.of();
    }

    private static int scoreToken(LudoBoard board, int seat, int token, int dice) {
        int current = board.progress(seat, token);
        if (current < 0) return 60;
        int score = current + dice;
        if (current + dice == LudoBoard.FINISH_PROGRESS) score += 100;
        if (current + dice < LudoBoard.TRACK_LENGTH) {
            int destination = (board.startSquare(seat) + current + dice) % LudoBoard.TRACK_LENGTH;
            for (int other = 0; other < board.playerCount(); other++) {
                if (other == seat || (board.teamMode() && board.teamOf(other) == board.teamOf(seat))) continue;
                for (int piece = 0; piece < 4; piece++) {
                    if (board.progress(other, piece) >= 0 && board.progress(other, piece) < LudoBoard.TRACK_LENGTH
                            && board.trackSquare(other, piece) == destination) score += 50;
                }
            }
        }
        return score;
    }

    private static List<String> summary(LudoGame game, int players) {
        List<String> lines = new ArrayList<>();
        String[] colors = {"Red", "Yellow", "Blue", "Green"};
        for (int seat = 0; seat < players; seat++) {
            String team = game.board.teamMode() ? (game.board.teamOf(seat) == 0 ? " • Team A" : " • Team B") : "";
            lines.add((seat == game.turn ? "> " : "") + colors[seat] + team + ": " + game.tokenSummary(seat));
        }
        lines.add(game.dice == 0 ? "Roll the die" : "Dice: " + game.dice);
        return lines;
    }

    private static int parse(String value) {
        try { return Integer.parseInt(value); } catch (RuntimeException ignored) { return -1; }
    }

    private static final class LudoGame {
        final LudoBoard board;
        int turn;
        int dice;
        List<Integer> legal = List.of();
        boolean botPending;
        LudoGame(int players, boolean teamMode) { board = new LudoBoard(players, teamMode); }
        String tokenSummary(int player) {
            List<String> states = new ArrayList<>();
            for (int token = 0; token < 4; token++) {
                int progress = board.progress(player, token);
                states.add(progress < 0 ? "base" : progress == LudoBoard.FINISH_PROGRESS ? "finished" : Integer.toString(progress));
            }
            return String.join(", ", states);
        }
    }
}
