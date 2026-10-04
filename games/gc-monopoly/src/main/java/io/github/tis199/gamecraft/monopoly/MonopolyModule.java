package io.github.tis199.gamecraft.monopoly;

import io.github.tis199.gamecraft.api.GameAction;
import io.github.tis199.gamecraft.api.GameModuleDescriptor;
import io.github.tis199.gamecraft.api.GameSession;
import io.github.tis199.gamecraft.api.MenuOption;
import io.github.tis199.gamecraft.engine.module.AbstractGameModule;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.UUID;

/** A turn-based Monopoly ruleset with properties, rent, taxes, cards, and computer buyers. */
public final class MonopolyModule extends AbstractGameModule {
    private final Map<UUID, MonopolyGame> games = new ConcurrentHashMap<>();
    private static final Space[] BOARD = makeBoard();

    @Override public GameModuleDescriptor descriptor() {
        return new GameModuleDescriptor("monopoly", "Monopoly", "0.1.0", 1,
                "Buy properties, collect rent, and bankrupt the other players.");
    }
    @Override public int minPlayers() { return 2; }
    @Override public int maxPlayers() { return 6; }
    @Override public List<String> supportedDifficulties() { return List.of("easy", "medium", "hard", "expert"); }
    @Override public boolean supportsComputer() { return true; }

    @Override public void onSessionStart(GameSession session) {
        MonopolyGame game = new MonopolyGame(session.totalPlayers());
        games.put(session.sessionId(), game);
        session.broadcastMessage("Monopoly started. This lobby edition includes buying, rent, taxes, and event cards.");
        prompt(session);
    }

    @Override
    public void onPlayerAction(GameAction action) {
        GameSession session = services.sessions().getPlayerSession(action.playerId()).orElse(null);
        if (session == null || !session.moduleId().equals("monopoly")) return;
        MonopolyGame game = games.get(session.sessionId());
        if (game == null) return;
        synchronized (game) {
            if (!action.playerId().equals(humanAt(session, game.turn)) || game.bankrupt[game.turn]) return;
            String choice = action.data().getOrDefault("choice", "");
            if (game.phase == Phase.ROLL && choice.equals("roll")) {
                roll(session, game);
            } else if (game.phase == Phase.BUY && choice.equals("buy")) {
                buyProperty(session, game);
                game.phase = Phase.END;
            } else if (game.phase == Phase.BUY && choice.equals("skip")) {
                game.phase = Phase.END;
            } else if (game.phase == Phase.END && choice.equals("end")) {
                nextTurn(session, game);
            } else {
                return;
            }
        }
        prompt(session);
    }

    @Override public void onSessionEnd(GameSession session) { games.remove(session.sessionId()); closeScene(session); }

    private void prompt(GameSession session) {
        MonopolyGame game = games.get(session.sessionId());
        if (game == null || session.state().name().equals("FINISHED")) return;
        if (game.bankrupt[game.turn]) nextTurn(session, game);
        if (!isHumanSeat(session, game.turn)) {
            renderScene(session, null, "Computer turn · Monopoly", List.of(new MenuOption(
                    "status", "Monopoly • Computer", standings(session, game))), "monopoly");
            if (!game.botPending) {
                game.botPending = true;
                runBot(() -> botTurn(session, game));
            }
            return;
        }
        List<MenuOption> options = new ArrayList<>();
        String title = "Monopoly • $" + game.cash[game.turn];
        if (game.phase == Phase.ROLL) {
            List<String> status = standings(session, game);
            status.add("Position: " + BOARD[game.position[game.turn]].name);
            options.add(new MenuOption("roll", "Roll dice", status));
        } else if (game.phase == Phase.BUY) {
            Space landed = BOARD[game.position[game.turn]];
            options.add(new MenuOption("buy", "Buy " + landed.name + " • $" + landed.price,
                    standings(session, game)));
            options.add(new MenuOption("skip", "Do not buy", List.of()));
        } else {
            options.add(new MenuOption("end", "End turn", standings(session, game)));
        }
        openMenu(session, humanAt(session, game.turn), title, options, "monopoly");
    }

    private void botTurn(GameSession session, MonopolyGame game) {
        synchronized (game) {
            game.botPending = false;
            if (session.state().name().equals("FINISHED")) return;
            if (game.phase == Phase.ROLL) roll(session, game);
            if (game.phase == Phase.BUY) {
                int available = game.cash[game.turn] - BOARD[game.position[game.turn]].price;
                boolean buy = switch (session.difficulty()) {
                    case "easy" -> available > 350;
                    case "expert" -> available >= 0;
                    case "hard" -> available > 150;
                    default -> available > 250;
                };
                if (buy) buyProperty(session, game);
                game.phase = Phase.END;
            }
            if (game.phase == Phase.END) nextTurn(session, game);
        }
        prompt(session);
    }

    private void roll(GameSession session, MonopolyGame game) {
        int dice = ThreadLocalRandom.current().nextInt(1, 7) + ThreadLocalRandom.current().nextInt(1, 7);
        int old = game.position[game.turn];
        int next = (old + dice) % BOARD.length;
        if (old + dice >= BOARD.length) {
            game.cash[game.turn] += 200;
            session.broadcastMessage("Seat " + (game.turn + 1) + " passed GO and collected $200.");
        }
        game.position[game.turn] = next;
        game.turns++;
        Space space = BOARD[next];
        session.broadcastMessage("Seat " + (game.turn + 1) + " rolled " + dice + " and landed on " + space.name + ".");
        if (space.kind == Kind.PROPERTY) {
            int owner = game.owner[next];
            if (owner == -1) game.phase = game.cash[game.turn] >= space.price ? Phase.BUY : Phase.END;
            else if (owner != game.turn && !game.bankrupt[owner]) {
                int rent = rentFor(game, next, dice);
                transfer(session, game, game.turn, owner, rent);
                game.phase = Phase.END;
            } else game.phase = Phase.END;
        } else if (space.kind == Kind.TAX) {
            payBank(session, game, game.turn, space.price);
            game.phase = Phase.END;
        } else if (space.kind == Kind.CHANCE || space.kind == Kind.COMMUNITY) {
            drawEvent(session, game, space.kind == Kind.CHANCE);
            game.phase = Phase.END;
        } else if (space.kind == Kind.JAIL && next == 30) {
            game.position[game.turn] = 10;
            game.phase = Phase.END;
            session.broadcastMessage("Seat " + (game.turn + 1) + " was sent to jail.");
        } else {
            game.phase = Phase.END;
        }
        checkWinner(session, game);
    }

    private void buyProperty(GameSession session, MonopolyGame game) {
        int square = game.position[game.turn];
        Space space = BOARD[square];
        if (space.kind != Kind.PROPERTY || game.owner[square] != -1 || game.cash[game.turn] < space.price) return;
        game.cash[game.turn] -= space.price;
        game.owner[square] = game.turn;
        session.broadcastMessage("Seat " + (game.turn + 1) + " bought " + space.name + ".");
    }

    private void payBank(GameSession session, MonopolyGame game, int debtor, int amount) {
        game.cash[debtor] -= amount;
        if (game.cash[debtor] < 0) bankrupt(session, game, debtor, -1);
        session.broadcastMessage("Seat " + (debtor + 1) + " paid $" + amount + ".");
    }

    private void transfer(GameSession session, MonopolyGame game, int debtor, int creditor, int amount) {
        int paid = Math.min(game.cash[debtor], amount);
        game.cash[debtor] -= paid;
        game.cash[creditor] += paid;
        if (paid < amount) bankrupt(session, game, debtor, creditor);
        session.broadcastMessage("Seat " + (debtor + 1) + " paid $" + paid + " rent to seat " + (creditor + 1) + ".");
    }

    private void bankrupt(GameSession session, MonopolyGame game, int seat, int creditor) {
        game.bankrupt[seat] = true;
        for (int square = 0; square < game.owner.length; square++) {
            if (game.owner[square] == seat) game.owner[square] = creditor;
        }
        session.broadcastMessage("Seat " + (seat + 1) + " is bankrupt.");
        checkWinner(session, game);
    }

    private void drawEvent(GameSession session, MonopolyGame game, boolean chance) {
        int card = ThreadLocalRandom.current().nextInt(chance ? 6 : 5);
        switch (card) {
            case 0 -> { game.position[game.turn] = 0; game.cash[game.turn] += 200; session.broadcastMessage("Collect $200 at GO."); }
            case 1 -> { game.cash[game.turn] += 50; session.broadcastMessage("Collect $50 from a lobby event."); }
            case 2 -> payBank(session, game, game.turn, 50);
            case 3 -> { game.position[game.turn] = 10; session.broadcastMessage("Go directly to jail."); }
            case 4 -> payBank(session, game, game.turn, 25);
            default -> { game.cash[game.turn] += 100; session.broadcastMessage("Collect $100."); }
        }
    }

    private void nextTurn(GameSession session, MonopolyGame game) {
        if (game.turns >= 300) {
            int winnerSeat = richest(game);
            UUID winner = humanAt(session, winnerSeat);
            if (winner == null) session.broadcastMessage("A computer won Monopoly on the turn limit.");
            session.end(winner);
            return;
        }
        game.phase = Phase.ROLL;
        for (int i = 1; i <= session.totalPlayers(); i++) {
            int candidate = (game.turn + i) % session.totalPlayers();
            if (!game.bankrupt[candidate]) {
                game.turn = candidate;
                return;
            }
        }
        checkWinner(session, game);
    }

    private void checkWinner(GameSession session, MonopolyGame game) {
        int remaining = 0;
        int winnerSeat = -1;
        for (int i = 0; i < game.bankrupt.length; i++) if (!game.bankrupt[i]) { remaining++; winnerSeat = i; }
        if (remaining == 1) {
            UUID winner = humanAt(session, winnerSeat);
            if (winner == null) session.broadcastMessage("A computer won Monopoly!");
            session.end(winner);
        }
    }

    private static int richest(MonopolyGame game) {
        int best = 0;
        int bestWorth = Integer.MIN_VALUE;
        for (int seat = 0; seat < game.cash.length; seat++) {
            if (game.bankrupt[seat]) continue;
            int worth = game.cash[seat];
            for (int square = 0; square < game.owner.length; square++) if (game.owner[square] == seat) worth += BOARD[square].price;
            if (worth > bestWorth) { bestWorth = worth; best = seat; }
        }
        return best;
    }

    private static List<String> standings(GameSession session, MonopolyGame game) {
        List<String> lines = new ArrayList<>();
        for (int seat = 0; seat < session.totalPlayers(); seat++) {
            String position = BOARD[game.position[seat]].name;
            lines.add("Seat " + (seat + 1) + (game.bankrupt[seat] ? " • bankrupt" : " • $" + game.cash[seat])
                    + " • " + position + " [#" + game.position[seat] + "]");
        }
        return lines;
    }

    private static int rentFor(MonopolyGame game, int square, int dice) {
        Space space = BOARD[square];
        int ownedInGroup = 0;
        int propertiesInGroup = 0;
        for (int i = 0; i < BOARD.length; i++) {
            if (BOARD[i].kind != Kind.PROPERTY || BOARD[i].group != space.group) continue;
            propertiesInGroup++;
            if (game.owner[i] == game.owner[square]) ownedInGroup++;
        }
        if (space.group == 9) return space.rent * (1 << Math.max(0, ownedInGroup - 1));
        if (space.group == 10) return dice * (ownedInGroup >= 2 ? 10 : 4);
        return space.rent * (propertiesInGroup == ownedInGroup ? 2 : 1);
    }

    private static Space[] makeBoard() {
        String[] names = {"GO", "Mediterranean Avenue", "Community Chest", "Baltic Avenue", "Income Tax", "Reading Railroad", "Oriental Avenue", "Chance", "Vermont Avenue", "Connecticut Avenue", "Jail / Just Visiting", "St. Charles Place", "Electric Company", "States Avenue", "Virginia Avenue", "Pennsylvania Railroad", "St. James Place", "Community Chest", "Tennessee Avenue", "New York Avenue", "Free Parking", "Kentucky Avenue", "Chance", "Indiana Avenue", "Illinois Avenue", "B. & O. Railroad", "Atlantic Avenue", "Ventnor Avenue", "Water Works", "Marvin Gardens", "Go To Jail", "Pacific Avenue", "North Carolina Avenue", "Community Chest", "Pennsylvania Avenue", "Short Line", "Chance", "Park Place", "Luxury Tax", "Boardwalk"};
        int[] prices = new int[40];
        int[] rents = new int[40];
        int[] groups = new int[40];
        int[][] props = {{1,60,2,1},{3,60,4,1},{6,100,6,2},{8,100,6,2},{9,120,8,2},{11,140,10,3},{13,140,10,3},{14,160,12,3},{16,180,14,4},{18,180,14,4},{19,200,16,4},{21,220,18,5},{23,220,18,5},{24,240,20,5},{26,260,22,6},{27,260,22,6},{29,280,24,6},{31,300,26,7},{32,300,26,7},{34,320,28,7},{37,350,35,8},{39,400,50,8},{5,200,25,9},{15,200,25,9},{25,200,25,9},{35,200,25,9},{12,150,15,10},{28,150,15,10}};
        for (int[] property : props) { prices[property[0]] = property[1]; rents[property[0]] = property[2]; groups[property[0]] = property[3]; }
        prices[4] = 200;
        prices[38] = 100;
        Space[] result = new Space[40];
        for (int i = 0; i < 40; i++) {
            Kind kind = groups[i] > 0 ? Kind.PROPERTY : i == 4 || i == 38 ? Kind.TAX
                    : i == 7 || i == 22 || i == 36 ? Kind.CHANCE
                    : i == 2 || i == 17 || i == 33 ? Kind.COMMUNITY
                    : i == 10 || i == 30 ? Kind.JAIL : Kind.SPECIAL;
            result[i] = new Space(names[i], prices[i], rents[i], groups[i], kind);
        }
        return result;
    }

    private enum Kind { PROPERTY, TAX, CHANCE, COMMUNITY, JAIL, SPECIAL }
    private enum Phase { ROLL, BUY, END }
    private record Space(String name, int price, int rent, int group, Kind kind) { }

    private static final class MonopolyGame {
        final int[] cash;
        final int[] position;
        final int[] owner = new int[40];
        final boolean[] bankrupt;
        int turn;
        int turns;
        Phase phase = Phase.ROLL;
        boolean botPending;
        MonopolyGame(int players) {
            cash = new int[players];
            java.util.Arrays.fill(cash, 1500);
            position = new int[players];
            bankrupt = new boolean[players];
            java.util.Arrays.fill(owner, -1);
        }
    }
}
