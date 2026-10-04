package io.github.tis199.gamecraft.chinesecheckers;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/** Standard 121-hole Chinese Checkers star and legal single/hop-chain movement. */
public final class ChineseCheckersBoard {
    private static final int[][] DIRECTIONS = {{1, 0}, {0, 1}, {-1, 1}, {-1, 0}, {0, -1}, {1, -1}};
    private static final int[][] SEATS = {{0, 3}, {0, 2, 4}, {0, 1, 3, 4}, {0, 1, 2, 3, 4}, {0, 1, 2, 3, 4, 5}};
    private final Map<HexPosition, Integer> pieces = new HashMap<>();
    private final Set<HexPosition> validPositions = new HashSet<>();
    private final List<Set<HexPosition>> starts = new ArrayList<>();
    private final List<Set<HexPosition>> goals = new ArrayList<>();
    private final int playerCount;

    public ChineseCheckersBoard(int playerCount) {
        if (playerCount < 2 || playerCount > 6) throw new IllegalArgumentException("Chinese Checkers supports 2–6 players");
        this.playerCount = playerCount;
        initPositions();
        int[] seatDirections = SEATS[playerCount - 2];
        for (int player = 0; player < playerCount; player++) {
            int direction = seatDirections[player];
            Set<HexPosition> start = arm(direction);
            Set<HexPosition> goal = arm((direction + 3) % 6);
            starts.add(start);
            goals.add(goal);
            for (HexPosition pos : start) pieces.put(pos, player);
        }
    }

    public int playerCount() { return playerCount; }
    public Set<HexPosition> validPositions() { return Collections.unmodifiableSet(validPositions); }
    public Map<HexPosition, Integer> pieces() { return Collections.unmodifiableMap(pieces); }

    public List<HexPosition> legalDestinations(HexPosition from, int player) {
        if (!Integer.valueOf(player).equals(pieces.get(from))) return List.of();
        boolean alreadyInGoal = goals.get(player).contains(from);
        pieces.remove(from);
        Set<HexPosition> destinations = new HashSet<>();
        Set<HexPosition> visited = new HashSet<>();
        Queue<HexPosition> queue = new ArrayDeque<>();
        queue.add(from);
        visited.add(from);
        for (int[] dir : DIRECTIONS) {
            HexPosition step = from.neighbor(dir[0], dir[1]);
                    if (validPositions.contains(step) && !pieces.containsKey(step)
                            && (!alreadyInGoal || goals.get(player).contains(step))) destinations.add(step);
        }
        while (!queue.isEmpty()) {
            HexPosition current = queue.remove();
            for (int[] dir : DIRECTIONS) {
                HexPosition middle = current.neighbor(dir[0], dir[1]);
                HexPosition landing = current.neighbor(dir[0] * 2, dir[1] * 2);
                if (pieces.containsKey(middle) && validPositions.contains(landing)
                        && !pieces.containsKey(landing)
                        && (!alreadyInGoal || goals.get(player).contains(landing)) && visited.add(landing)) {
                    destinations.add(landing);
                    queue.add(landing);
                }
            }
        }
        pieces.put(from, player);
        destinations.remove(from);
        return destinations.stream().sorted((a, b) -> {
            int first = distanceToGoal(a, player);
            int second = distanceToGoal(b, player);
            int compared = Integer.compare(first, second);
            if (compared != 0) return compared;
            compared = Integer.compare(a.q(), b.q());
            return compared != 0 ? compared : Integer.compare(a.r(), b.r());
        }).toList();
    }

    public boolean isValidMove(HexPosition from, HexPosition to, int player) {
        return legalDestinations(from, player).contains(to);
    }

    public void move(HexPosition from, HexPosition to, int player) {
        if (!isValidMove(from, to, player)) throw new IllegalArgumentException("Illegal Chinese Checkers move");
        pieces.remove(from);
        pieces.put(to, player);
    }

    public List<Move> legalMoves(int player) {
        List<Move> result = new ArrayList<>();
        for (Map.Entry<HexPosition, Integer> entry : new ArrayList<>(pieces.entrySet())) {
            if (entry.getValue() != player) continue;
            for (HexPosition to : legalDestinations(entry.getKey(), player)) result.add(new Move(entry.getKey(), to));
        }
        return result;
    }

    public boolean hasPlayerWon(int player) {
        for (HexPosition goal : goals.get(player)) if (!Integer.valueOf(player).equals(pieces.get(goal))) return false;
        return true;
    }

    public int progressScore(int player) {
        int score = 0;
        for (Map.Entry<HexPosition, Integer> piece : pieces.entrySet()) {
            if (piece.getValue() == player) score -= distanceToGoal(piece.getKey(), player);
        }
        return score;
    }

    public int scoreMove(Move move, int player) {
        return distanceToGoal(move.from(), player) - distanceToGoal(move.to(), player);
    }

    private void initPositions() {
        for (int q = -4; q <= 4; q++) {
            for (int r = -4; r <= 4; r++) {
                if (Math.max(Math.max(Math.abs(q), Math.abs(r)), Math.abs(q + r)) <= 4) {
                    validPositions.add(new HexPosition(q, r));
                }
            }
        }
        for (int direction = 0; direction < 6; direction++) validPositions.addAll(arm(direction));
    }

    private static Set<HexPosition> arm(int direction) {
        Set<HexPosition> result = new HashSet<>();
        for (int depth = 1; depth <= 4; depth++) {
            for (int offset = -depth; offset < 0; offset++) {
                int q = 4 + depth;
                int r = offset;
                for (int rotate = 0; rotate < direction; rotate++) {
                    int nextQ = -r;
                    int nextR = q + r;
                    q = nextQ;
                    r = nextR;
                }
                result.add(new HexPosition(q, r));
            }
        }
        return result;
    }

    private int distanceToGoal(HexPosition position, int player) {
        int best = Integer.MAX_VALUE;
        for (HexPosition goal : goals.get(player)) best = Math.min(best, hexDistance(position, goal));
        return best;
    }

    private static int hexDistance(HexPosition a, HexPosition b) {
        int dq = a.q() - b.q();
        int dr = a.r() - b.r();
        return (Math.abs(dq) + Math.abs(dr) + Math.abs(dq + dr)) / 2;
    }

    public record Move(HexPosition from, HexPosition to) { }
}
