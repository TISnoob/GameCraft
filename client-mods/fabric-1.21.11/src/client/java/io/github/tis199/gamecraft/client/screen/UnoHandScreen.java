package io.github.tis199.gamecraft.client.screen;

import io.github.tis199.gamecraft.client.GameCraftClient;
import io.github.tis199.gamecraft.client.model.GameOption;
import io.github.tis199.gamecraft.client.model.GameSceneState;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/** Card picker that shows the player's own UNO hand using the same card art as the table. */
public final class UnoHandScreen extends Screen {
    private final GameSceneState scene;
    private final List<GameOption> cards;
    private int firstVisible;
    private int cardWidth;
    private int cardHeight;
    private int columns;
    private int top;

    public UnoHandScreen(GameSceneState scene) {
        super(Text.literal("UNO • Your hand"));
        this.scene = scene;
        this.cards = new ArrayList<>(scene.options().stream()
                .filter(option -> option.id().startsWith("play:") || option.id().startsWith("hand:"))
                .toList());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);
        context.fill(0, 0, width, height, 0xD9101711);
        context.fill(10, 10, width - 10, height - 10, 0xE8283025);
        context.drawHorizontalLine(10, width - 10, 10, 0xFFB97A3D);
        context.drawHorizontalLine(10, width - 10, height - 10, 0xFFB97A3D);
        context.drawVerticalLine(10, 10, height - 10, 0xFFB97A3D);
        context.drawVerticalLine(width - 10, 10, height - 10, 0xFFB97A3D);
        context.drawCenteredTextWithShadow(textRenderer, "UNO • Your cards", width / 2, 22, 0xFFFFD76A);
        GameOption status = scene.option("status");
        context.drawCenteredTextWithShadow(textRenderer,
                status == null ? "Choose a playable card" : status.title(), width / 2, 38, 0xFFF4EFE5);

        int availableWidth = Math.max(1, width - 38);
        columns = Math.max(1, Math.min(8, availableWidth / 72));
        cardWidth = Math.max(28, Math.min(72, (availableWidth - (columns - 1) * 7) / columns));
        cardHeight = (int) (cardWidth * 1.34);
        int visibleRows = Math.max(1, (height - 106) / (cardHeight + 22));
        int visibleCount = Math.min(cards.size(), columns * visibleRows);
        firstVisible = Math.min(firstVisible, Math.max(0, cards.size() - visibleCount));
        top = 60;
        for (int shown = 0; shown < visibleCount; shown++) {
            int index = firstVisible + shown;
            GameOption option = cards.get(index);
            int column = shown % columns;
            int row = shown / columns;
            int x = (width - (columns * cardWidth + (columns - 1) * 7)) / 2 + column * (cardWidth + 7);
            int y = top + row * (cardHeight + 22);
            boolean playable = option.id().startsWith("play:");
            boolean hovered = mouseX >= x && mouseX < x + cardWidth && mouseY >= y && mouseY < y + cardHeight;
            if (hovered) context.fill(x - 3, y - 3, x + cardWidth + 3, y + cardHeight + 3, 0xFFFFD76A);
            context.fill(x - 1, y - 1, x + cardWidth + 1, y + cardHeight + 1, 0xFF111111);
            Identifier texture = cardTexture(option.title());
            context.drawTexture(RenderPipelines.GUI_TEXTURED, texture, x, y, 0.0f, 0.0f,
                    cardWidth, cardHeight, 64, 64);
            if (!playable) context.fill(x, y, x + cardWidth, y + cardHeight, 0x66000000);
            context.drawCenteredTextWithShadow(textRenderer, shortLabel(option.title()), x + cardWidth / 2,
                    y + cardHeight + 5, playable ? 0xFFFFFFFF : 0xFF999999);
        }
        String footer = cards.isEmpty() ? "No cards in hand"
                : "Click a bright card to play  •  Wheel to browse";
        context.drawCenteredTextWithShadow(textRenderer, footer, width / 2, height - 27, 0xFFE7D9BE);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubleClick) {
        double mouseX = click.x();
        double mouseY = click.y();
        if (click.button() != 0) return super.mouseClicked(click, doubleClick);
        int availableWidth = Math.max(1, width - 38);
        columns = Math.max(1, Math.min(8, availableWidth / 72));
        cardWidth = Math.max(28, Math.min(72, (availableWidth - (columns - 1) * 7) / columns));
        cardHeight = (int) (cardWidth * 1.34);
        int visibleRows = Math.max(1, (height - 106) / (cardHeight + 22));
        int visibleCount = Math.min(cards.size(), columns * visibleRows);
        for (int shown = 0; shown < visibleCount; shown++) {
            int index = firstVisible + shown;
            int column = shown % columns;
            int row = shown / columns;
            int x = (width - (columns * cardWidth + (columns - 1) * 7)) / 2 + column * (cardWidth + 7);
            int y = 60 + row * (cardHeight + 22);
            if (mouseX < x || mouseX >= x + cardWidth || mouseY < y || mouseY >= y + cardHeight) continue;
            GameOption option = cards.get(index);
            if (option.id().startsWith("play:")) {
                GameCraftClient.sendAction(scene, option.id());
                close();
            }
            return true;
        }
        return super.mouseClicked(click, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int step = Math.max(1, columns);
        firstVisible = Math.max(0, Math.min(Math.max(0, cards.size() - step), firstVisible - (int) Math.signum(verticalAmount) * step));
        return true;
    }

    @Override
    public boolean shouldPause() { return false; }

    private static Identifier cardTexture(String title) {
        String label = title.replaceFirst("^(Play|Hold)\\s+", "").toUpperCase(java.util.Locale.ROOT)
                .replaceAll("[^A-Z0-9 ]", " ").trim();
        String[] parts = label.split("\\s+");
        int model;
        if (parts.length == 1 && parts[0].startsWith("WILD")) {
            model = parts[0].contains("4") ? 53 : 52;
        } else {
            int color = parts.length == 0 ? 0 : switch (parts[0]) {
                case "RED" -> 0; case "YELLOW" -> 1; case "GREEN" -> 2; case "BLUE" -> 3; default -> 0;
            };
            int rank = parts.length < 2 ? 0 : switch (parts[1]) {
                case "SKIP" -> 10; case "REVERSE" -> 11; case "DRAW2" -> 12;
                default -> { try { yield Math.max(0, Math.min(9, Integer.parseInt(parts[1]))); } catch (NumberFormatException ignored) { yield 0; } }
            };
            model = color * 13 + rank;
        }
        return Identifier.of("gamecraft", "textures/item/uno-card-" + model + ".png");
    }

    private static String shortLabel(String title) {
        String label = title.replaceFirst("^(Play|Hold)\\s+", "");
        return label.length() > 18 ? label.substring(0, 17) + "…" : label;
    }
}
