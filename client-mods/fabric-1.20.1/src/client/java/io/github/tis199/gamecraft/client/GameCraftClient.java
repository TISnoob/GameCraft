package io.github.tis199.gamecraft.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.tis199.gamecraft.client.model.GameSceneState;
import io.github.tis199.gamecraft.client.model.FurnitureState;
import io.github.tis199.gamecraft.client.render.GameBoardRenderer;
import io.github.tis199.gamecraft.client.screen.UnoHandScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.MinecraftClient;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;

import java.util.Map;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class GameCraftClient implements ClientModInitializer {
    public static final int PROTOCOL = 1;
    private static final Identifier CLIENT_CHANNEL = Identifier.of("gamecraft", "client");
    private static final Identifier SCENE_CHANNEL = Identifier.of("gamecraft", "scene");
    private static final Identifier FURNITURE_CHANNEL = Identifier.of("gamecraft", "furniture");
    private static final Map<UUID, GameSceneState> SCENES = new ConcurrentHashMap<>();
    private static final Map<UUID, FurnitureState> FURNITURE = new ConcurrentHashMap<>();

    @Override
    public void onInitializeClient() {
        ModelLoadingPlugin.register(context -> context.addModels(ModelIdCatalog.all()));

        ClientPlayNetworking.registerGlobalReceiver(SCENE_CHANNEL,
                (client, handler, buffer, responseSender) -> accept(buffer.readString(32767), client));
        ClientPlayNetworking.registerGlobalReceiver(FURNITURE_CHANNEL,
                (client, handler, buffer, responseSender) -> acceptFurniture(buffer.readString(32767)));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            GameBoardRenderer.clearAll();
            SCENES.clear();
            FURNITURE.clear();
            sendHello();
        });
        WorldRenderEvents.AFTER_ENTITIES.register(GameBoardRenderer::render);
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!world.isClient() || hand != net.minecraft.util.Hand.MAIN_HAND) return ActionResult.PASS;
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.currentScreen != null) return ActionResult.PASS;
            GameSceneState scene = nearestScene(world.getRegistryKey().getValue().toString(), hit.getPos().x, hit.getPos().z);
            if (scene == null) return ActionResult.PASS;
            GameBoardRenderer.click(scene, hit.getPos().x, hit.getPos().z);
            return ActionResult.SUCCESS;
        });
    }

    private static GameSceneState nearestScene(String world, double x, double z) {
        return SCENES.values().stream()
                .filter(scene -> scene.world().equals(world))
                .filter(scene -> Math.abs(x - scene.x()) <= 1.6 && Math.abs(z - scene.z()) <= 1.6)
                .min(java.util.Comparator.comparingDouble(scene -> squared(x - scene.x(), z - scene.z())))
                .orElse(null);
    }

    private static double squared(double x, double z) { return x * x + z * z; }

    public static GameSceneState scene(UUID id) { return SCENES.get(id); }

    public static Collection<GameSceneState> scenes() { return List.copyOf(SCENES.values()); }

    public static Collection<FurnitureState> furniture() { return List.copyOf(FURNITURE.values()); }

    public static void sendAction(GameSceneState scene, String choice) {
        JsonObject packet = base("action");
        packet.addProperty("session", scene.sessionId().toString());
        packet.addProperty("actionType", scene.actionType());
        packet.addProperty("choice", choice);
        send(CLIENT_CHANNEL, packet.toString());
    }

    public static void requestHand(GameSceneState scene) {
        JsonObject packet = base("open_hand");
        packet.addProperty("session", scene.sessionId().toString());
        send(CLIENT_CHANNEL, packet.toString());
    }

    private static void sendHello() {
        JsonObject packet = base("hello");
        send(CLIENT_CHANNEL, packet.toString());
    }

    private static void send(Identifier channel, String json) {
        PacketByteBuf buffer = PacketByteBufs.create();
        buffer.writeString(json);
        ClientPlayNetworking.send(channel, buffer);
    }

    private static JsonObject base(String kind) {
        JsonObject packet = new JsonObject();
        packet.addProperty("protocol", PROTOCOL);
        packet.addProperty("kind", kind);
        return packet;
    }

    private static void accept(String json, MinecraftClient client) {
        try {
            JsonObject packet = JsonParser.parseString(json).getAsJsonObject();
            if (packet.get("protocol").getAsInt() != PROTOCOL) return;
            switch (packet.get("kind").getAsString()) {
                case "scene" -> {
                    GameSceneState scene = GameSceneState.parse(json);
                    SCENES.put(scene.sessionId(), scene);
                }
                case "clear" -> {
                    UUID sessionId = UUID.fromString(packet.get("session").getAsString());
                    SCENES.remove(sessionId);
                    client.execute(() -> GameBoardRenderer.clearSession(sessionId));
                }
                case "hello_ack" -> { }
                case "open_hand" -> {
                    GameSceneState scene = GameSceneState.parse(json);
                    SCENES.put(scene.sessionId(), scene);
                    client.execute(() -> client.setScreen(new UnoHandScreen(scene)));
                }
                default -> { }
            }
        } catch (RuntimeException ignored) {
            // A malformed or mismatched protocol packet must never interrupt world rendering.
        }
    }

    private static void acceptFurniture(String json) {
        try {
            JsonObject packet = JsonParser.parseString(json).getAsJsonObject();
            if (packet.get("protocol").getAsInt() != PROTOCOL
                    || !packet.get("kind").getAsString().equals("snapshot")) return;
            Map<UUID, FurnitureState> updated = new ConcurrentHashMap<>();
            var items = packet.getAsJsonArray("items");
            for (int i = 0; i < items.size(); i++) {
                FurnitureState furniture = FurnitureState.parse(items.get(i).getAsJsonObject());
                updated.put(furniture.id(), furniture);
            }
            FURNITURE.clear();
            FURNITURE.putAll(updated);
        } catch (RuntimeException ignored) {
            // Ignore malformed furniture updates so they cannot break client rendering.
        }
    }
}
