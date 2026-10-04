package io.github.tis199.gamecraft.client.render;

import io.github.tis199.gamecraft.client.GameCraftClient;
import io.github.tis199.gamecraft.client.model.FurnitureState;
import io.github.tis199.gamecraft.client.model.GameOption;
import io.github.tis199.gamecraft.client.model.GameSceneState;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemDisplayContext;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Draws baked GameCraft furniture and game pieces directly on physical table blocks on the client. */
public final class GameBoardRenderer {
    private static final Pattern BOARD_ROW = Pattern.compile("\\|([^|]+)\\|");
    private static final Pattern SQUARE = Pattern.compile("(?i)([a-h][1-8])");
    private static final Pattern HEX = Pattern.compile("(-?\\d+),(-?\\d+)");
    private static final Map<String, Motion> MOTIONS = new HashMap<>();
    private static final Map<UUID, Set<String>> ACTIVE_VISUALS = new HashMap<>();

    private GameBoardRenderer() { }

    public static void render(LevelRenderContext context) {
        PoseStack matrices = context.poseStack();
        Minecraft client = Minecraft.getInstance();
        if (matrices == null || context.submitNodeCollector() == null || client.level == null) return;
        Vec3 camera = context.levelState().cameraRenderState.pos;
        long now = System.currentTimeMillis();
        for (FurnitureState furniture : GameCraftClient.furniture()) {
            if (!furniture.world().equals(client.level.dimension().identifier().toString())) continue;
            double dx = furniture.x() - camera.x;
            double dy = furniture.y() - camera.y;
            double dz = furniture.z() - camera.z;
            if (dx * dx + dy * dy + dz * dz > 64 * 64) continue;
            if (furniture.type().equals("table")) {
                if (!hasActiveScene(furniture)) renderTable(context, furniture, camera);
            } else if (furniture.type().equals("chair")) {
                renderChair(context, furniture, camera);
            }
        }
        for (GameSceneState scene : GameCraftClient.scenes()) {
            if (!scene.world().equals(client.level.dimension().identifier().toString())) continue;
            double dx = scene.x() - camera.x;
            double dy = scene.y() - camera.y;
            double dz = scene.z() - camera.z;
            if (dx * dx + dy * dy + dz * dz > 64 * 64) continue;
            List<Visual> visuals = buildScene(scene);
            Set<String> active = new HashSet<>();
            for (Visual visual : visuals) {
                String key = scene.sessionId() + "/" + visual.key();
                active.add(key);
                Motion motion = motion(key, visual.x(), visual.y(), visual.z(), now);
                double progress = Math.min(1, Math.max(0, (now - motion.startedAt()) / 240.0));
                double eased = progress * progress * (3 - 2 * progress);
                double x = Mth.lerp(eased, motion.fromX(), motion.toX());
                double y = Mth.lerp(eased, motion.fromY(), motion.toY());
                double z = Mth.lerp(eased, motion.fromZ(), motion.toZ());
                renderModel(client, context, matrices, visual.model(),
                        x - camera.x, y - camera.y, z - camera.z,
                        visual.scaleX(), visual.scaleY(), visual.scaleZ(), visual.yaw());
            }
            Set<String> previous = ACTIVE_VISUALS.put(scene.sessionId(), active);
            if (previous != null) previous.stream().filter(key -> !active.contains(key)).forEach(MOTIONS::remove);
        }
    }

    private static Motion motion(String key, double x, double y, double z, long now) {
        Motion previous = MOTIONS.get(key);
        if (previous == null) {
            Motion initial = new Motion(x, y, z, x, y, z, now);
            MOTIONS.put(key, initial);
            return initial;
        }
        if (Math.abs(previous.toX() - x) + Math.abs(previous.toY() - y) + Math.abs(previous.toZ() - z) > 0.025) {
            double progress = Math.min(1, Math.max(0, (now - previous.startedAt()) / 240.0));
            double eased = progress * progress * (3 - 2 * progress);
            double currentX = Mth.lerp(eased, previous.fromX(), previous.toX());
            double currentY = Mth.lerp(eased, previous.fromY(), previous.toY());
            double currentZ = Mth.lerp(eased, previous.fromZ(), previous.toZ());
            previous = new Motion(currentX, currentY, currentZ, x, y, z, now);
            MOTIONS.put(key, previous);
        }
        return previous;
    }

    private static void renderModel(Minecraft client, LevelRenderContext context, PoseStack matrices,
                                    String name,
                                    double x, double y, double z,
                                    float sx, float sy, float sz, float yaw) {
        Identifier id = Identifier.fromNamespaceAndPath("gamecraft", "item/" + name);
        matrices.pushPose();
        matrices.translate(x, y, z);
        matrices.rotate(com.mojang.math.Axis.YP, yaw);
        matrices.scale(sx, sy, sz);
        matrices.translate(-0.5, 0, -0.5);
        ItemStack carrier = new ItemStack(Items.PAPER);
        carrier.set(DataComponents.ITEM_MODEL, id);
        ItemStackRenderState state = new ItemStackRenderState();
        client.getItemModelResolver().updateForTopItem(state, carrier, ItemDisplayContext.FIXED,
                client.level, null, 0);
        state.submit(matrices, context.submitNodeCollector(), 0x00F000F0, 0, -1);
        matrices.popPose();
    }

    private static boolean hasActiveScene(FurnitureState furniture) {
        return GameCraftClient.scenes().stream().anyMatch(scene -> scene.game().equals(furniture.game())
                && scene.world().equals(furniture.world())
                && Math.hypot(scene.x() - furniture.x(), scene.z() - furniture.z()) < 1.0);
    }

    private static void renderTable(LevelRenderContext context, FurnitureState table, Vec3 camera) {
        Minecraft client = Minecraft.getInstance();
        PoseStack matrices = context.poseStack();
        float width = table.width();
        float depth = table.depth();
        double x = table.x() - camera.x;
        double y = table.y() - camera.y;
        double z = table.z() - camera.z;
        renderModel(client, context, matrices, "table-" + table.game(),
                x, y - 0.5, z, width, 1.0f, depth, 0);
        for (int dx : new int[]{-1, 1}) {
            for (int dz : new int[]{-1, 1}) {
                renderModel(client, context, matrices, "table-leg",
                        x + dx * (width / 2 - 0.18), y - 0.9, z + dz * (depth / 2 - 0.18),
                        0.18f, 0.9f, 0.18f, 0);
            }
        }
    }

    private static void renderChair(LevelRenderContext context, FurnitureState chair, Vec3 camera) {
        Minecraft client = Minecraft.getInstance();
        renderModel(client, context, context.poseStack(),
                "chair-" + chairGame(chair), chair.x() + 0.5 - camera.x,
                chair.y() - camera.y, chair.z() + 0.5 - camera.z,
                0.9f, 1.0f, 0.9f, 0);
    }

    private static String chairGame(FurnitureState chair) {
        if (!chair.game().isBlank()) return chair.game();
        return GameCraftClient.furniture().stream().filter(item -> item.type().equals("table")
                        && item.world().equals(chair.world()))
                .min(java.util.Comparator.comparingDouble(item -> Math.hypot(item.x() - chair.x(), item.z() - chair.z())))
                .map(FurnitureState::game).orElse("chess");
    }

    private static List<Visual> buildScene(GameSceneState scene) {
        List<Visual> out = new ArrayList<>();
        Footprint footprint = Footprint.forGame(scene.game());
        double topY = scene.y() + 0.02;
        out.add(new Visual("table", "table-" + scene.game(), scene.x(),
                scene.y() - 0.5, scene.z(),
                footprint.width, 1.0f, footprint.depth, 0));
        for (int dx : new int[]{-1, 1}) {
            for (int dz : new int[]{-1, 1}) {
                out.add(new Visual("table-leg-" + dx + "-" + dz, "table-leg",
                        scene.x() + dx * (footprint.width / 2 - 0.18), scene.y() - 0.9,
                        scene.z() + dz * (footprint.depth / 2 - 0.18), 0.18f, 0.9f, 0.18f, 0));
            }
        }

        GameOption status = scene.options().stream()
                .filter(option -> option.id().equals("status") || option.id().equals("board"))
                .findFirst().orElse(null);
        switch (scene.game()) {
            case "chess" -> addChess(scene, status, out, topY);
            case "checkers" -> addCheckers(scene, status, out, topY);
            case "chinese-checkers" -> addChinese(scene, status, out, topY);
            case "ludo" -> addLudo(scene, status, out, topY);
            case "monopoly" -> addMonopoly(scene, status, out, topY);
            case "uno" -> addUno(scene, status, out, topY);
            case "solitaire" -> addSolitaire(scene, status, out, topY);
            case "sudoku" -> addSudoku(scene, status, out, topY);
            default -> { }
        }
        return out;
    }

    private static void addChess(GameSceneState scene, GameOption status, List<Visual> out, double y) {
        if (status == null) return;
        double cell = 0.5625;
        double startX = scene.x() - 2.25;
        double startZ = scene.z() - 2.25;
        int rank = 7;
        for (String line : status.description()) {
            Matcher matcher = BOARD_ROW.matcher(line);
            if (!matcher.find()) continue;
            String cells = matcher.group(1).replaceAll("\\s", "");
            if (cells.length() != 8 || rank < 0) continue;
            for (int file = 0; file < 8; file++) {
                char piece = cells.charAt(file);
                String model = chessModel(piece);
                if (model == null) continue;
                out.add(new Visual("piece-" + (char) ('a' + file) + (rank + 1), model,
                        startX + (file + 0.5) * cell, y, startZ + (7 - rank + 0.5) * cell,
                        0.48f, 0.48f, 0.48f, 0));
            }
            rank--;
        }
        Set<String> markers = new HashSet<>();
        for (GameOption option : scene.options()) {
            String id = option.id();
            if (id.startsWith("destination:") || id.startsWith("selected:")) {
                String square = id.substring(id.indexOf(':') + 1).toLowerCase(Locale.ROOT);
                if (!markers.add(square) || !square.matches("[a-h][1-8]")) continue;
                int file = square.charAt(0) - 'a';
                int cellRank = square.charAt(1) - '1';
                String model = id.startsWith("selected:") ? "chess-selected-marker" : "chess-move-marker";
                out.add(new Visual("marker-" + square, model,
                        startX + (file + 0.5) * cell, y + 0.015, startZ + (7 - cellRank + 0.5) * cell,
                        id.startsWith("selected:") ? 0.48f : 0.34f, 0.08f,
                        id.startsWith("selected:") ? 0.48f : 0.34f, 0));
            }
        }
    }

    private static String chessModel(char piece) {
        String white = "PNBRQK";
        String black = "pnbrqk";
        int index = white.indexOf(piece);
        if (index >= 0) return "chess-white-" + new String[]{"pawn", "knight", "bishop", "rook", "queen", "king"}[index];
        index = black.indexOf(piece);
        return index < 0 ? null : "chess-black-" + new String[]{"pawn", "knight", "bishop", "rook", "queen", "king"}[index];
    }

    private static void addCheckers(GameSceneState scene, GameOption status, List<Visual> out, double y) {
        if (status == null) return;
        double cell = 0.5625;
        double startX = scene.x() - 2.25;
        double startZ = scene.z() - 2.25;
        int rank = 7;
        for (String line : status.description()) {
            Matcher matcher = BOARD_ROW.matcher(line);
            if (!matcher.find()) continue;
            String cells = matcher.group(1).replaceAll("\\s", "");
            if (cells.length() != 8 || rank < 0) continue;
            for (int file = 0; file < 8; file++) {
                char piece = cells.charAt(file);
                String model = switch (piece) {
                    case 'r' -> "checkers-red-man";
                    case 'R' -> "checkers-red-king";
                    case 'b' -> "checkers-black-man";
                    case 'B' -> "checkers-black-king";
                    default -> null;
                };
                if (model != null) out.add(new Visual("piece-" + file + "-" + rank, model,
                        startX + (file + 0.5) * cell, y, startZ + (7 - rank + 0.5) * cell,
                        0.41f, 0.41f, 0.41f, 0));
            }
            rank--;
        }
        for (GameOption option : scene.options()) {
            if (!option.id().startsWith("move:")) continue;
            Matcher matcher = SQUARE.matcher(option.title());
            String last = null;
            while (matcher.find()) last = matcher.group(1).toLowerCase(Locale.ROOT);
            if (last == null) continue;
            int file = last.charAt(0) - 'a';
            int cellRank = last.charAt(1) - '1';
            out.add(new Visual("legal-" + option.id(), "chess-move-marker",
                    startX + (file + 0.5) * cell, y + 0.02, startZ + (7 - cellRank + 0.5) * cell,
                    0.3f, 0.06f, 0.3f, 0));
        }
    }

    private static void addChinese(GameSceneState scene, GameOption status, List<Visual> out, double y) {
        if (status == null) return;
        String[] colors = {"chinese-red", "chinese-yellow", "chinese-blue", "chinese-green", "chinese-purple", "chinese-orange"};
        for (String line : status.description()) {
            Matcher seat = Pattern.compile("Seat (\\d+) pieces: (.*)").matcher(line);
            if (!seat.matches()) continue;
            int player = Integer.parseInt(seat.group(1)) - 1;
            Matcher pos = HEX.matcher(seat.group(2));
            int index = 0;
            while (pos.find()) {
                int q = Integer.parseInt(pos.group(1));
                int r = Integer.parseInt(pos.group(2));
                double x = 0.31 * (Math.sqrt(3) * q + Math.sqrt(3) / 2 * r);
                double z = 0.31 * 1.5 * r;
                out.add(new Visual("seat-" + player + "-" + index++, colors[Math.floorMod(player, colors.length)],
                        scene.x() + x, y, scene.z() + z, 0.32f, 0.32f, 0.32f, 0));
            }
        }
        String selected = scene.options().stream().filter(option -> option.id().startsWith("back:"))
                .map(option -> option.id().substring(5)).findFirst().orElse(null);
        for (GameOption option : scene.options()) {
            if (!option.id().startsWith("to:") && !option.id().startsWith("piece:")) continue;
            String pos = option.id().substring(option.id().indexOf(':') + 1);
            String[] pair = pos.split(",");
            if (pair.length != 2) continue;
            try {
                int q = Integer.parseInt(pair[0]);
                int r = Integer.parseInt(pair[1]);
                double x = 0.31 * (Math.sqrt(3) * q + Math.sqrt(3) / 2 * r);
                double z = 0.31 * 1.5 * r;
                out.add(new Visual("legal-" + option.id(), "chess-move-marker",
                        scene.x() + x, y + 0.02, scene.z() + z, 0.24f, 0.05f, 0.24f, 0));
            } catch (NumberFormatException ignored) { }
        }
    }

    private static void addLudo(GameSceneState scene, GameOption status, List<Visual> out, double y) {
        if (status == null) return;
        String[] colors = {"ludo-red", "ludo-yellow", "ludo-blue", "ludo-green"};
        for (String line : status.description()) {
            Matcher seat = Pattern.compile("(?:>\\s*)?(Red|Yellow|Blue|Green)(?:\\s+•[^:]*)?:\\s*(.*)", Pattern.CASE_INSENSITIVE).matcher(line);
            if (!seat.matches()) continue;
            int player = switch (seat.group(1).toLowerCase(Locale.ROOT)) {
                case "red" -> 0; case "yellow" -> 1; case "blue" -> 2; default -> 3;
            };
            String[] pieces = seat.group(2).split(",\\s*");
            double baseX = (player == 0 || player == 3) ? -1.35 : 1.35;
            double baseZ = (player == 0 || player == 1) ? -1.35 : 1.35;
            for (int token = 0; token < pieces.length; token++) {
                String value = pieces[token].trim();
                double x;
                double z;
                if (value.equalsIgnoreCase("base")) {
                    x = baseX + (token % 2) * 0.32;
                    z = baseZ + (token / 2) * 0.32;
                } else {
                    int progress;
                    try { progress = Integer.parseInt(value); } catch (NumberFormatException ignored) { continue; }
                    if (progress >= 56) continue;
                    int track = Math.floorMod(player * 13 + progress, 52);
                    double angle = track * Math.PI * 2 / 52.0 - Math.PI / 2;
                    double radius = progress >= 52 ? 0.45 : 1.88;
                    x = Math.cos(angle) * radius;
                    z = Math.sin(angle) * radius;
                }
                out.add(new Visual("token-" + player + "-" + token, colors[player], scene.x() + x, y,
                        scene.z() + z, 0.34f, 0.34f, 0.34f, 0));
            }
        }
    }

    private static void addMonopoly(GameSceneState scene, GameOption status, List<Visual> out, double y) {
        if (status == null) return;
        Matcher matcher = Pattern.compile("Seat\\s+(\\d+).*?\\[#?(\\d+)\\]").matcher("");
        String[] colors = {"monopoly-token-red", "monopoly-token-blue", "monopoly-token-yellow", "monopoly-token-green", "monopoly-token-white", "monopoly-token-purple"};
        for (String line : status.description()) {
            matcher.reset(line);
            if (!matcher.find()) continue;
            int seat = Integer.parseInt(matcher.group(1)) - 1;
            int position = Integer.parseInt(matcher.group(2));
            double edge = 2.5;
            double x;
            double z;
            if (position < 10) { x = edge - position * edge / 10.0; z = edge; }
            else if (position < 20) { x = -edge; z = edge - (position - 10) * edge / 10.0; }
            else if (position < 30) { x = -edge + (position - 20) * edge / 10.0; z = -edge; }
            else { x = edge; z = -edge + (position - 30) * edge / 10.0; }
            out.add(new Visual("token-" + seat, colors[Math.floorMod(seat, colors.length)], scene.x() + x,
                    y, scene.z() + z, 0.32f, 0.32f, 0.32f, 0));
        }
    }

    private static void addUno(GameSceneState scene, GameOption status, List<Visual> out, double y) {
        if (status != null) {
            String label = status.title().replaceFirst("^Top:\\s*", "").split("\\s*•", 2)[0];
            out.add(new Visual("draw-pile", "uno-draw-pile", scene.x() - 0.9, y, scene.z(),
                    0.52f, 0.05f, 0.68f, 0));
            out.add(new Visual("discard", unoModel(label), scene.x() + 0.38, y + 0.04, scene.z(),
                    0.72f, 0.05f, 0.84f, 0.08f));
        }
        List<GameOption> hand = scene.options().stream().filter(option -> option.id().startsWith("play:")
                || option.id().startsWith("hand:")).toList();
        double spacing = Math.min(0.46, 3.85 / Math.max(1, hand.size()));
        for (int i = 0; i < hand.size(); i++) {
            GameOption card = hand.get(i);
            double x = -((hand.size() - 1) * spacing) / 2 + i * spacing;
            double lift = card.id().startsWith("play:") ? 0.065 : 0;
            out.add(new Visual("hand-" + i, unoModel(card.title().replaceFirst("^(Play|Hold)\\s+", "")),
                    scene.x() + x, y + lift, scene.z() + 1.38, 0.70f, 0.05f, 0.84f,
                    (float) (Math.sin(i * 0.18) * 0.09)));
        }
    }

    private static String unoModel(String label) {
        String normalized = label.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9 ]", " ").trim();
        String[] parts = normalized.split("\\s+");
        if (parts.length == 1 && parts[0].startsWith("WILD")) return "uno-card-" + (parts[0].contains("4") ? 53 : 52);
        if (parts.length < 2) return "uno-card-52";
        int color = switch (parts[0]) { case "RED" -> 0; case "YELLOW" -> 1; case "GREEN" -> 2; case "BLUE" -> 3; default -> 0; };
        int rank = switch (parts[1]) {
            case "SKIP" -> 10; case "REVERSE" -> 11; case "DRAW2" -> 12;
            default -> { try { yield Math.min(9, Math.max(0, Integer.parseInt(parts[1]))); } catch (NumberFormatException ignored) { yield 0; } }
        };
        return "uno-card-" + (color * 13 + rank);
    }

    private static void addSolitaire(GameSceneState scene, GameOption status, List<Visual> out, double y) {
        out.add(new Visual("stock", "solitaire-deck", scene.x() - 1.8, y, scene.z() - 1.35, 0.74f, 0.05f, 0.94f, 0));
        if (status != null) {
            String title = status.title();
            int wasteAt = title.indexOf("Waste:");
            if (wasteAt >= 0) out.add(new Visual("waste", solitaireModel(title.substring(wasteAt + 6).trim()),
                    scene.x() - 0.9, y + 0.02, scene.z() - 1.35, 0.74f, 0.05f, 0.94f, 0));
        }
        for (GameOption option : scene.options()) {
            if (!option.id().startsWith("select:")) continue;
            String[] data = option.id().split(":");
            if (data.length != 3) continue;
            try {
                int col = Integer.parseInt(data[1]);
                int index = Integer.parseInt(data[2]);
                int card = option.title().contains("•") ? option.title().substring(option.title().lastIndexOf('•') + 1).trim().hashCode() : 0;
                out.add(new Visual("card-" + col + "-" + index, solitaireModel(option.title()),
                        scene.x() - 1.8 + col * 0.55, y + 0.02 + Math.min(index, 12) * 0.018,
                        scene.z() + 0.1 + Math.min(index, 8) * 0.18, 0.68f, 0.05f, 0.86f, 0));
            } catch (NumberFormatException ignored) { }
        }
    }

    private static String solitaireModel(String label) {
        Matcher matcher = Pattern.compile("(?i)(ace|[2-9]|10|jack|queen|king)\\s+(hearts|diamonds|clubs|spades)").matcher(label);
        if (!matcher.find()) return "solitaire-card-0";
        int rank = switch (matcher.group(1).toLowerCase(Locale.ROOT)) {
            case "ace" -> 0; case "jack" -> 10; case "queen" -> 11; case "king" -> 12;
            default -> Integer.parseInt(matcher.group(1)) - 1;
        };
        int suit = switch (matcher.group(2).toLowerCase(Locale.ROOT)) { case "hearts" -> 0; case "diamonds" -> 1; case "clubs" -> 2; default -> 3; };
        return "solitaire-card-" + (suit * 13 + rank);
    }

    private static void addSudoku(GameSceneState scene, GameOption status, List<Visual> out, double y) {
        double cell = 0.42;
        for (GameOption row : scene.options()) {
            if (row.id().equals("board")) {
                for (String line : row.description()) {
                    Matcher match = Pattern.compile("Grid r([0-8]):\\s*(.*)").matcher(line);
                    if (match.matches()) addSudokuRow(Integer.parseInt(match.group(1)), match.group(2), scene, out, y, cell);
                }
            }
        }
        for (GameOption option : scene.options()) {
            if (!option.id().matches("v[1-9]")) continue;
            int value = Integer.parseInt(option.id().substring(1));
            out.add(new Visual("enter-" + value, "sudoku-" + value,
                    scene.x() - 1.68 + (value - 1) * 0.42, y + 0.02, scene.z() + 2.12,
                    0.30f, 0.12f, 0.30f, 0));
        }
    }

    private static void addSudokuRow(int row, String raw, GameSceneState scene, List<Visual> out,
                                     double y, double cell) {
        String[] digits = raw.replace("Grid r" + row + ":", "").trim().split("\\s+");
        for (int column = 0; column < Math.min(9, digits.length); column++) {
            try {
                int value = Integer.parseInt(digits[column]);
                out.add(new Visual("number-" + row + "-" + column, "sudoku-" + value,
                        scene.x() - 1.89 + column * cell + cell / 2, y, scene.z() - 1.89 + row * cell + cell / 2,
                        0.32f, 0.12f, 0.32f, 0));
            } catch (NumberFormatException ignored) { }
        }
    }

    /** Uses a click on the solid table block as a board click, then sends only a listed legal action. */
    public static void click(GameSceneState scene, double x, double z) {
        String game = scene.game();
        if (game.equals("chess") || game.equals("checkers")) {
            int file = (int) Math.floor((x - (scene.x() - 2.25)) / 0.5625);
            int rank = 7 - (int) Math.floor((z - (scene.z() - 2.25)) / 0.5625);
            if (file < 0 || file > 7 || rank < 0 || rank > 7) return;
            String square = "" + (char) ('a' + file) + (rank + 1);
            if (game.equals("chess")) {
                String destination = "destination:" + square;
                String piece = "square:" + square;
                if (scene.option(destination) != null) GameCraftClient.sendAction(scene, destination);
                else if (scene.option(piece) != null) GameCraftClient.sendAction(scene, piece);
            } else {
                for (GameOption option : scene.options()) {
                    if (!option.id().startsWith("move:")) continue;
                    Matcher matcher = SQUARE.matcher(option.title());
                    String last = null;
                    while (matcher.find()) last = matcher.group(1).toLowerCase(Locale.ROOT);
                    if (square.equals(last)) { GameCraftClient.sendAction(scene, option.id()); return; }
                }
            }
            return;
        }
        if (game.equals("chinese-checkers")) {
            GameOption closest = scene.options().stream().filter(option -> option.id().startsWith("piece:") || option.id().startsWith("to:"))
                    .min(java.util.Comparator.comparingDouble(option -> hexDistance(scene, option.id(), x, z))).orElse(null);
            if (closest != null && hexDistance(scene, closest.id(), x, z) < 0.42) GameCraftClient.sendAction(scene, closest.id());
            return;
        }
        if (game.equals("uno")) {
            if (scene.option("draw") != null && Math.abs(x - (scene.x() - 0.9)) < 0.42
                    && Math.abs(z - scene.z()) < 0.5) {
                GameCraftClient.sendAction(scene, "draw");
                return;
            }
            List<GameOption> hand = scene.options().stream().filter(option -> option.id().startsWith("play:")
                    || option.id().startsWith("hand:")).toList();
            if (hand.isEmpty() || Math.abs(z - (scene.z() + 1.38)) > 0.65) return;
            double spacing = Math.min(0.46, 3.85 / Math.max(1, hand.size()));
            int index = (int) Math.round((x - scene.x()) / spacing + (hand.size() - 1) / 2.0);
            if (index >= 0 && index < hand.size() && hand.get(index).id().startsWith("play:")) {
                GameCraftClient.sendAction(scene, hand.get(index).id());
            }
            return;
        }
        if (game.equals("ludo")) {
            clickLudo(scene, x, z);
            return;
        }
        if (game.equals("solitaire")) {
            if (z < scene.z() - 0.8 && x < scene.x() - 1.25 && scene.option("draw") != null) {
                GameCraftClient.sendAction(scene, "draw"); return;
            }
            int col = Math.round((float) ((x - (scene.x() - 1.8)) / 0.55));
            GameOption card = scene.options().stream().filter(option -> option.id().startsWith("select:" + col + ":"))
                    .max(java.util.Comparator.comparingInt(option -> Integer.parseInt(option.id().split(":")[2]))).orElse(null);
            if (card != null) GameCraftClient.sendAction(scene, card.id());
            return;
        }
        if (game.equals("sudoku")) {
            List<GameOption> values = scene.options().stream().filter(option -> option.id().matches("v[1-9]")).toList();
            if (!values.isEmpty() && z > scene.z() + 1.91) {
                int value = Math.round((float) ((x - (scene.x() - 1.68)) / 0.42)) + 1;
                if (value >= 1 && value <= 9 && scene.option("v" + value) != null) {
                    GameCraftClient.sendAction(scene, "v" + value);
                }
                return;
            }
            int col = (int) Math.floor((x - (scene.x() - 1.89)) / 0.42);
            int row = (int) Math.floor((z - (scene.z() - 1.89)) / 0.42);
            if (col < 0 || col > 8 || row < 0 || row > 8) return;
            if (scene.option("r" + row) != null) GameCraftClient.sendAction(scene, "r" + row);
            else if (scene.option("c" + col) != null) GameCraftClient.sendAction(scene, "c" + col);
        }
    }

    private static void clickLudo(GameSceneState scene, double x, double z) {
        GameOption status = scene.option("status");
        if (status == null) return;
        String[] colors = {"Red", "Yellow", "Blue", "Green"};
        String current = status.description().stream()
                .filter(line -> line.startsWith("> "))
                .map(line -> line.substring(2).split("[ •:]", 2)[0])
                .findFirst().orElse(null);
        int seat = -1;
        for (int i = 0; i < colors.length; i++) if (colors[i].equalsIgnoreCase(current)) seat = i;
        if (seat < 0) return;
        final int activeSeat = seat;
        String tokenLine = status.description().stream().filter(line -> {
            String trimmed = line.replaceFirst("^>\\s*", "");
            return trimmed.regionMatches(true, 0, colors[activeSeat], 0, colors[activeSeat].length()) && trimmed.contains(":");
        }).findFirst().orElse(null);
        if (tokenLine == null) return;
        int totalSeats = (int) status.description().stream().filter(line ->
                line.matches("(?:>\\s*)?(Red|Yellow|Blue|Green)(?:\\s+•[^:]*)?:.*")).count();
        String[] positions = tokenLine.substring(tokenLine.indexOf(':') + 1).split(",\\s*");
        double baseX = (seat == 0 || seat == 3) ? -1.35 : 1.35;
        double baseZ = (seat == 0 || seat == 1) ? -1.35 : 1.35;
        for (GameOption option : scene.options()) {
            if (!option.id().startsWith("token:")) continue;
            int token;
            try { token = Integer.parseInt(option.id().substring(6)); } catch (NumberFormatException ignored) { continue; }
            if (token < 0 || token >= positions.length) continue;
            String value = positions[token].trim();
            double px;
            double pz;
            if (value.equalsIgnoreCase("base")) {
                px = scene.x() + baseX + (token % 2) * 0.32;
                pz = scene.z() + baseZ + (token / 2) * 0.32;
            } else {
                int progress;
                try { progress = Integer.parseInt(value); } catch (NumberFormatException ignored) { continue; }
                int track = Math.floorMod(seat * 52 / Math.max(2, totalSeats) + progress, 52);
                double angle = track * Math.PI * 2 / 52.0 - Math.PI / 2;
                double radius = progress >= 52 ? 0.45 : 1.88;
                px = scene.x() + Math.cos(angle) * radius;
                pz = scene.z() + Math.sin(angle) * radius;
            }
            if (Math.hypot(px - x, pz - z) < 0.55) {
                GameCraftClient.sendAction(scene, option.id());
                return;
            }
        }
    }

    private static double hexDistance(GameSceneState scene, String id, double x, double z) {
        String pos = id.substring(id.indexOf(':') + 1);
        String[] pair = pos.split(",");
        if (pair.length != 2) return Double.MAX_VALUE;
        try {
            int q = Integer.parseInt(pair[0]);
            int r = Integer.parseInt(pair[1]);
            double px = scene.x() + 0.31 * (Math.sqrt(3) * q + Math.sqrt(3) / 2 * r);
            double pz = scene.z() + 0.31 * 1.5 * r;
            return Math.hypot(px - x, pz - z);
        } catch (NumberFormatException ignored) { return Double.MAX_VALUE; }
    }

    private record Footprint(float width, float depth) {
        static Footprint forGame(String game) {
            return switch (game) {
                case "chess", "checkers" -> new Footprint(6.0f, 6.0f);
                case "ludo", "chinese-checkers", "monopoly" -> new Footprint(7, 7);
                case "uno" -> new Footprint(6, 4);
                case "solitaire" -> new Footprint(5, 4);
                case "sudoku" -> new Footprint(5, 5);
                default -> new Footprint(5, 5);
            };
        }
    }

    private record Visual(String key, String model, double x, double y, double z,
                          float scaleX, float scaleY, float scaleZ, float yaw) { }
    private record Motion(double fromX, double fromY, double fromZ,
                          double toX, double toY, double toZ, long startedAt) { }
}
