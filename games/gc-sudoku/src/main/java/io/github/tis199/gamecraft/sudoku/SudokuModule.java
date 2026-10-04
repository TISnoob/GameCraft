package io.github.tis199.gamecraft.sudoku;

import io.github.tis199.gamecraft.api.GameAction;
import io.github.tis199.gamecraft.api.GameModuleDescriptor;
import io.github.tis199.gamecraft.api.GameSession;
import io.github.tis199.gamecraft.api.MenuOption;
import io.github.tis199.gamecraft.engine.module.AbstractGameModule;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Sudoku puzzle engine. Multiplayer is a shared-board turn race. */
public final class SudokuModule extends AbstractGameModule {
    private final Map<UUID, Puzzle> puzzles = new ConcurrentHashMap<>();

    @Override public GameModuleDescriptor descriptor() {
        return new GameModuleDescriptor("sudoku", "Sudoku", "0.1.0", 1,
                "Sudoku puzzles with selectable difficulty and an optional multiplayer race.");
    }
    @Override public int minPlayers() { return 1; }
    @Override public int maxPlayers() { return 9; }
    @Override public List<String> supportedDifficulties() { return List.of("easy", "medium", "hard", "expert"); }

    @Override
    public void onSessionStart(GameSession session) {
        puzzles.put(session.sessionId(), new Puzzle(session.difficulty()));
        session.broadcastMessage("Sudoku started. Fill the shared board; the last correct placement wins.");
        prompt(session);
    }

    @Override
    public void onPlayerAction(GameAction action) {
        GameSession session = services.sessions().getPlayerSession(action.playerId()).orElse(null);
        if (session == null || !session.moduleId().equals("sudoku")) return;
        Puzzle puzzle = puzzles.get(session.sessionId());
        if (puzzle == null) return;
        synchronized (puzzle) {
            if (!isCurrentHuman(session, puzzle, action.playerId())) return;
            String choice = action.data().getOrDefault("choice", "");
            if (puzzle.screen == Screen.ROW && choice.startsWith("r")) {
                puzzle.selectedRow = number(choice);
                puzzle.screen = Screen.CELL;
            } else if (puzzle.screen == Screen.CELL && choice.equals("rback")) {
                puzzle.screen = Screen.ROW;
            } else if (puzzle.screen == Screen.CELL && choice.startsWith("c")) {
                int column = number(choice);
                if (puzzle.selectedRow < 0 || column < 0 || column > 8) return;
                if (puzzle.given[puzzle.selectedRow][column]) {
                    tell(session, action.playerId(), "That cell is part of the puzzle clue.");
                    puzzle.screen = Screen.ROW;
                } else if (puzzle.board[puzzle.selectedRow][column] != 0) {
                    tell(session, action.playerId(), "That cell already has a correct number.");
                    puzzle.screen = Screen.ROW;
                } else {
                    puzzle.selectedColumn = column;
                    puzzle.screen = Screen.VALUE;
                }
            } else if (puzzle.screen == Screen.VALUE && choice.startsWith("v")) {
                int value = number(choice);
                int row = puzzle.selectedRow;
                int column = puzzle.selectedColumn;
                if (row < 0 || column < 0 || value < 1 || value > 9) return;
                if (puzzle.board[row][column] != 0) {
                    puzzle.screen = Screen.ROW;
                } else if (value != puzzle.solution[row][column]) {
                    tell(session, action.playerId(), "That number does not fit this puzzle. Try another.");
                    puzzle.screen = Screen.VALUE;
                } else {
                    puzzle.board[row][column] = value;
                    puzzle.moves++;
                    puzzle.currentSeat = (puzzle.currentSeat + 1) % session.totalPlayers();
                    puzzle.screen = Screen.ROW;
                    if (puzzle.moves >= puzzle.emptyCells) {
                        session.end(action.playerId());
                        return;
                    }
                }
            } else {
                return;
            }
        }
        prompt(session);
    }

    @Override
    public void onSessionEnd(GameSession session) {
        puzzles.remove(session.sessionId());
        closeScene(session);
    }

    private void prompt(GameSession session) {
        Puzzle puzzle = puzzles.get(session.sessionId());
        if (puzzle == null || session.state().name().equals("FINISHED")) return;
        UUID player = humanAt(session, puzzle.currentSeat);
        if (player == null) return;
        List<MenuOption> options = new ArrayList<>();
        if (puzzle.screen == Screen.ROW) {
            options.add(new MenuOption("board", "Sudoku • " + puzzle.difficulty, List.of(
                    "Turn: " + (puzzle.currentSeat + 1) + "/" + session.totalPlayers(),
                    "Choose a row to edit.")));
            options.set(0, new MenuOption("board", "Sudoku • " + puzzle.difficulty, boardLines(puzzle)));
            for (int row = 0; row < 9; row++) {
                options.add(new MenuOption("r" + row, "Row " + (row + 1), List.of(rowText(puzzle, row))));
            }
        } else if (puzzle.screen == Screen.CELL) {
            options.add(new MenuOption("board", "Row " + (puzzle.selectedRow + 1), boardLines(puzzle)));
            for (int column = 0; column < 9; column++) {
                int value = puzzle.board[puzzle.selectedRow][column];
                options.add(new MenuOption("c" + column, "Cell " + (column + 1) + (value == 0 ? " • empty" : " • " + value),
                        List.of(puzzle.given[puzzle.selectedRow][column] ? "Clue" : "Select this cell")));
            }
            options.add(new MenuOption("rback", "Choose another row", List.of()));
        } else {
            options.add(new MenuOption("board", "Cell " + (puzzle.selectedRow + 1) + ", "
                    + (puzzle.selectedColumn + 1), boardLines(puzzle)));
            for (int value = 1; value <= 9; value++) {
                options.add(new MenuOption("v" + value, "Enter " + value, List.of()));
            }
        }
        openMenu(session, player, "Sudoku", options, "sudoku");
    }

    private boolean isCurrentHuman(GameSession session, Puzzle puzzle, UUID player) {
        return player.equals(humanAt(session, puzzle.currentSeat));
    }

    private static String rowText(Puzzle puzzle, int row) {
        StringBuilder result = new StringBuilder();
        for (int value : puzzle.board[row]) result.append(value == 0 ? "· " : value + " ");
        return result.toString().trim();
    }

    private static List<String> boardLines(Puzzle puzzle) {
        List<String> lines = new ArrayList<>();
        for (int row = 0; row < 9; row++) lines.add("Grid r" + row + ": " + rowText(puzzle, row));
        return lines;
    }

    private static int number(String value) {
        try { return Integer.parseInt(value.substring(1)); }
        catch (RuntimeException ignored) { return -1; }
    }

    private enum Screen { ROW, CELL, VALUE }

    private static final class Puzzle {
        final String difficulty;
        final int[][] board = new int[9][9];
        final int[][] solution = new int[9][9];
        final boolean[][] given = new boolean[9][9];
        final int emptyCells;
        int moves;
        int currentSeat;
        int selectedRow = -1;
        int selectedColumn = -1;
        Screen screen = Screen.ROW;

        Puzzle(String difficulty) {
            this.difficulty = difficulty;
            int holes = switch (difficulty) {
                case "easy" -> 35;
                case "hard" -> 52;
                case "expert" -> 58;
                default -> 45;
            };
            int shift = java.util.concurrent.ThreadLocalRandom.current().nextInt(9);
            for (int row = 0; row < 9; row++) {
                for (int column = 0; column < 9; column++) {
                    solution[row][column] = (row * 3 + row / 3 + column + shift) % 9 + 1;
                    board[row][column] = solution[row][column];
                }
            }
            List<Integer> cells = new ArrayList<>();
            for (int i = 0; i < 81; i++) cells.add(i);
            java.util.Collections.shuffle(cells);
            for (int i = 0; i < holes; i++) board[cells.get(i) / 9][cells.get(i) % 9] = 0;
            for (int row = 0; row < 9; row++) {
                for (int column = 0; column < 9; column++) given[row][column] = board[row][column] != 0;
            }
            emptyCells = holes;
        }
    }
}
