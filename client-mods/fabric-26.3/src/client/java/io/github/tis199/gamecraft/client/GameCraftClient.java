package io.github.tis199.gamecraft.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.tis199.gamecraft.client.model.GameSceneState;
import io.github.tis199.gamecraft.client.model.FurnitureState;
import io.github.tis199.gamecraft.client.protocol.ClientPayload;
import io.github.tis199.gamecraft.client.protocol.FurniturePayload;
import io.github.tis199.gamecraft.client.protocol.ScenePayload;
import io.github.tis199.gamecraft.client.render.GameBoardRenderer;
import io.github.tis199.gamecraft.client.screen.UnoHandScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.InteractionResult;
import net.minecraft.resources.Identifier;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class GameCraftClient implements ClientModInitializer {
    public static final int PROTOCOL = 1;
    private static final Map<UUID, GameSceneState> SCENES = new ConcurrentHashMap<>();
    private static final Map<UUID, FurnitureState> FURNITURE = new ConcurrentHashMap<>();

    @Override
    public void onInitializeClient() {
        PayloadTypeRegistry.clientboundPlay().register(ScenePayload.ID, ScenePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(FurniturePayload.ID, FurniturePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ClientPayload.ID, ClientPayload.CODEC);

        ClientPlayNetworking.registerGlobalReceiver(ScenePayload.ID, (payload, context) ->
                accept(payload.json(), context.client()));
        ClientPlayNetworking.registerGlobalReceiver(FurniturePayload.ID, (payload, context) ->
                acceptFurniture(payload.json()));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            SCENES.clear();
            FURNITURE.clear();
            sendHello();
        });
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(GameBoardRenderer::render);
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!world.isClientSide() || hand != net.minecraft.world.InteractionHand.MAIN_HAND) return InteractionResult.PASS;
            Minecraft client = Minecraft.getInstance();
            GameSceneState scene = nearestScene(world.dimension().identifier().toString(), hit.getLocation().x, hit.getLocation().z);
            if (scene == null) return InteractionResult.PASS;
            GameBoardRenderer.click(scene, hit.getLocation().x, hit.getLocation().z);
            return InteractionResult.SUCCESS;
        });
    }

    private static GameSceneState nearestScene(String world, double x, double z) {
        return SCENES.values().stream()
                .filter(scene -> scene.world().equals(world))
                .filter(scene -> Math.abs(x - scene.x()) <= 4.6 && Math.abs(z - scene.z()) <= 4.6)
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
        ClientPlayNetworking.send(new ClientPayload(packet.toString()));
    }

    public static void requestHand(GameSceneState scene) {
        JsonObject packet = base("open_hand");
        packet.addProperty("session", scene.sessionId().toString());
        ClientPlayNetworking.send(new ClientPayload(packet.toString()));
    }

    private static void sendHello() {
        JsonObject packet = base("hello");
        ClientPlayNetworking.send(new ClientPayload(packet.toString()));
    }

    private static JsonObject base(String kind) {
        JsonObject packet = new JsonObject();
        packet.addProperty("protocol", PROTOCOL);
        packet.addProperty("kind", kind);
        return packet;
    }

    private static void accept(String json, Minecraft client) {
        try {
            JsonObject packet = JsonParser.parseString(json).getAsJsonObject();
            if (packet.get("protocol").getAsInt() != PROTOCOL) return;
            switch (packet.get("kind").getAsString()) {
                case "scene" -> {
                    GameSceneState scene = GameSceneState.parse(json);
                    SCENES.put(scene.sessionId(), scene);
                }
                case "clear" -> SCENES.remove(UUID.fromString(packet.get("session").getAsString()));
                case "hello_ack" -> { }
                case "open_hand" -> {
                    GameSceneState scene = GameSceneState.parse(json);
                    SCENES.put(scene.sessionId(), scene);
                    client.execute(() -> client.setScreenAndShow(new UnoHandScreen(scene)));
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
