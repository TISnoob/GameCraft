package io.github.tis199.gamecraft.paper.session;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.tis199.gamecraft.api.GameBoardService;
import io.github.tis199.gamecraft.api.GameLocation;
import io.github.tis199.gamecraft.api.GameSession;
import io.github.tis199.gamecraft.api.MenuOption;
import io.github.tis199.gamecraft.paper.GameCraftPlugin;
import io.github.tis199.gamecraft.paper.platform.PaperScheduler;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Paper side of the versioned, validated GameCraft client-mod protocol. */
public final class GameBoardServiceImpl implements GameBoardService, PluginMessageListener, Listener {
    public static final String CLIENT_CHANNEL = "gamecraft:client";
    public static final String SCENE_CHANNEL = "gamecraft:scene";
    private static final int PROTOCOL = 1;
    private static final double VIEW_DISTANCE_SQUARED = 48.0 * 48.0;

    private final GameCraftPlugin plugin;
    private final GameSessionManagerImpl sessions;
    private final PaperScheduler scheduler;
    private final Map<UUID, Snapshot> activeScenes = new ConcurrentHashMap<>();
    private final Map<UUID, Snapshot> privateHands = new ConcurrentHashMap<>();
    private final Set<UUID> moddedClients = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> lastActionNanos = new ConcurrentHashMap<>();
    private final Map<UUID, SyncCell> sceneSyncCells = new ConcurrentHashMap<>();

    public GameBoardServiceImpl(GameCraftPlugin plugin, GameSessionManagerImpl sessions, PaperScheduler scheduler) {
        this.plugin = plugin;
        this.sessions = sessions;
        this.scheduler = scheduler;
        var messenger = Bukkit.getMessenger();
        messenger.registerOutgoingPluginChannel(plugin, CLIENT_CHANNEL);
        messenger.registerIncomingPluginChannel(plugin, CLIENT_CHANNEL, this);
        messenger.registerOutgoingPluginChannel(plugin, SCENE_CHANNEL);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public boolean isClientModReady(UUID playerId) {
        return moddedClients.contains(playerId);
    }

    public boolean isValidChoice(UUID sessionId, UUID playerId, String actionType, String choice) {
        Snapshot snapshot = activeScenes.get(sessionId);
        return snapshot != null && snapshot.actionType().equals(actionType)
                && (snapshot.privatePlayerId() == null || snapshot.privatePlayerId().equals(playerId))
                && snapshot.options().stream().anyMatch(option -> option.id().equals(choice));
    }

    @Override
    public void publish(GameSession session, UUID privatePlayerId, String title, List<MenuOption> options, String actionType) {
        Snapshot snapshot = new Snapshot(session.sessionId(), session.moduleId(), session.origin(), privatePlayerId, title,
                actionType, List.copyOf(options), "");
        activeScenes.put(session.sessionId(), snapshot);
        privateHands.entrySet().removeIf(entry -> entry.getValue().sessionId().equals(session.sessionId()));
        if ("uno".equals(session.moduleId()) && privatePlayerId != null
                && options.stream().anyMatch(option -> option.id().startsWith("play:") || option.id().startsWith("hand:"))) {
            privateHands.put(privatePlayerId, snapshot);
        }
        sessions.updateGameHotbar(session, privatePlayerId, options, actionType);
        String payload = scenePacket(snapshot, privatePlayerId).toString();
        if (payload.length() > 24_000) {
            plugin.getLogger().warning("Game board state exceeded the client packet limit; update was dropped for "
                    + session.sessionId());
            return;
        }
        scheduler.runGlobal(() -> {
            if (activeScenes.get(snapshot.sessionId()) != snapshot) return;
            Snapshot resolved = resolveWorld(snapshot);
            if (resolved == null || !activeScenes.replace(snapshot.sessionId(), snapshot, resolved)) return;
            if (privatePlayerId != null) privateHands.replace(privatePlayerId, snapshot, resolved);
            for (Player viewer : Bukkit.getOnlinePlayers()) sendSceneTo(viewer.getUniqueId(), resolved);
        });
    }

    @Override
    public void clear(UUID sessionId) {
        Snapshot snapshot = activeScenes.remove(sessionId);
        privateHands.entrySet().removeIf(entry -> entry.getValue().sessionId().equals(sessionId));
        if (snapshot == null) return;
        scheduler.runGlobal(() -> {
            JsonObject packet = new JsonObject();
            packet.addProperty("protocol", PROTOCOL);
            packet.addProperty("kind", "clear");
            packet.addProperty("session", sessionId.toString());
            String payload = packet.toString();
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                UUID viewerId = viewer.getUniqueId();
                if (!moddedClients.contains(viewerId)) continue;
                scheduler.runForEntity(viewerId, () -> {
                    Player current = Bukkit.getPlayer(viewerId);
                    if (current != null) send(current, SCENE_CHANNEL, payload);
                }, () -> { });
            }
        });
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CLIENT_CHANNEL.equals(channel) || message.length == 0 || message.length > 4096) return;
        try {
            JsonObject packet = JsonParser.parseString(decodeString(message)).getAsJsonObject();
            if (packet.get("protocol").getAsInt() != PROTOCOL) return;
            String kind = packet.get("kind").getAsString();
            if (kind.equals("hello")) {
                moddedClients.add(player.getUniqueId());
                sceneSyncCells.put(player.getUniqueId(), syncCell(player));
                acknowledge(player);
                syncVisibleScenes(player);
                plugin.furniture().sync(player);
            } else if (kind.equals("action")) {
                handleAction(player, packet);
            } else if (kind.equals("open_hand")) {
                handleOpenHand(player, packet);
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().fine("Ignored malformed GameCraft client packet from " + player.getName());
        }
    }

    private void handleAction(Player player, JsonObject packet) {
        UUID sessionId = UUID.fromString(packet.get("session").getAsString());
        Snapshot snapshot = activeScenes.get(sessionId);
        GameSession session = sessions.getSession(sessionId).orElse(null);
        if (snapshot == null || session == null || !session.players().contains(player.getUniqueId())
                || (snapshot.privatePlayerId() != null && !snapshot.privatePlayerId().equals(player.getUniqueId()))
                || !snapshot.gameId().equals(session.moduleId()) || !moddedClients.contains(player.getUniqueId())
                || !canView(player, snapshot.origin())) return;

        String actionType = packet.get("actionType").getAsString();
        String choice = packet.get("choice").getAsString();
        if (!snapshot.actionType().equals(actionType)
                || snapshot.options().stream().noneMatch(option -> option.id().equals(choice))) return;

        long now = System.nanoTime();
        Long previous = lastActionNanos.put(player.getUniqueId(), now);
        if (previous != null && now - previous < 60_000_000L) return;
        session.sendAction(new io.github.tis199.gamecraft.api.GameAction(actionType, player.getUniqueId(),
                Map.of("choice", choice)));
    }

    private void handleOpenHand(Player player, JsonObject packet) {
        UUID sessionId = UUID.fromString(packet.get("session").getAsString());
        Snapshot snapshot = privateHands.get(player.getUniqueId());
        GameSession session = sessions.getSession(sessionId).orElse(null);
        if (snapshot == null || !snapshot.sessionId().equals(sessionId) || session == null
                || !session.players().contains(player.getUniqueId()) || !snapshot.gameId().equals("uno")
                || snapshot.worldKey().isBlank() || !moddedClients.contains(player.getUniqueId())) return;
        JsonObject hand = scenePacket(snapshot, player.getUniqueId());
        hand.addProperty("kind", "open_hand");
        send(player, SCENE_CHANNEL, hand.toString());
    }

    public void openHand(Player player, UUID sessionId) {
        Snapshot snapshot = privateHands.get(player.getUniqueId());
        if (snapshot == null || !"uno".equals(snapshot.gameId())
                || !player.getUniqueId().equals(snapshot.privatePlayerId()) || !snapshot.sessionId().equals(sessionId)
                || snapshot.worldKey().isBlank()) return;
        JsonObject hand = scenePacket(snapshot, player.getUniqueId());
        hand.addProperty("kind", "open_hand");
        send(player, SCENE_CHANNEL, hand.toString());
    }

    private void acknowledge(Player player) {
        JsonObject packet = new JsonObject();
        packet.addProperty("protocol", PROTOCOL);
        packet.addProperty("kind", "hello_ack");
        send(player, SCENE_CHANNEL, packet.toString());
    }

    private void syncVisibleScenes(Player player) {
        activeScenes.values().stream().filter(snapshot -> !snapshot.worldKey().isBlank())
                .filter(snapshot -> canView(player, snapshot.origin()))
                .map(snapshot -> scenePacket(snapshot, snapshot.privatePlayerId() != null
                        && snapshot.privatePlayerId().equals(player.getUniqueId()) ? player.getUniqueId() : null)
                        .toString())
                .forEach(payload -> send(player, SCENE_CHANNEL, payload));
    }

    private static JsonObject scenePacket(Snapshot snapshot, UUID recipientId) {
        JsonObject packet = new JsonObject();
        packet.addProperty("protocol", PROTOCOL);
        packet.addProperty("kind", "scene");
        packet.addProperty("session", snapshot.sessionId().toString());
        packet.addProperty("game", snapshot.gameId());
        packet.addProperty("world", snapshot.worldKey());
        packet.addProperty("x", snapshot.origin().x());
        packet.addProperty("y", snapshot.origin().y());
        packet.addProperty("z", snapshot.origin().z());
        packet.addProperty("title", snapshot.title());
        packet.addProperty("actionType", snapshot.actionType());
        JsonArray options = new JsonArray();
        for (MenuOption option : snapshot.options()) {
            if (snapshot.privatePlayerId() != null && !snapshot.privatePlayerId().equals(recipientId)
                    && (option.id().startsWith("play:") || option.id().startsWith("hand:"))) continue;
            JsonObject item = new JsonObject();
            item.addProperty("id", option.id());
            item.addProperty("title", option.title());
            JsonArray descriptions = new JsonArray();
            option.description().forEach(descriptions::add);
            item.add("description", descriptions);
            options.add(item);
        }
        packet.add("options", options);
        return packet;
    }

    private static boolean canView(Player player, GameLocation origin) {
        if (!player.getWorld().getUID().equals(origin.worldId())) return false;
        double x = player.getLocation().getX() - origin.x();
        double y = player.getLocation().getY() - origin.y();
        double z = player.getLocation().getZ() - origin.z();
        return x * x + y * y + z * z <= VIEW_DISTANCE_SQUARED;
    }

    private void send(Player player, String channel, String json) {
        byte[] text = json.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream packet = new ByteArrayOutputStream(text.length + 5);
        int remaining = text.length;
        while ((remaining & 0xFFFFFF80) != 0) {
            packet.write((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        packet.write(remaining);
        packet.writeBytes(text);
        player.sendPluginMessage(plugin, channel, packet.toByteArray());
    }

    private static String decodeString(byte[] packet) {
        int length = 0;
        int shift = 0;
        int cursor = 0;
        while (true) {
            if (cursor >= packet.length || shift > 21) throw new IllegalArgumentException("Invalid string length");
            int value = packet[cursor++] & 0xFF;
            length |= (value & 0x7F) << shift;
            if ((value & 0x80) == 0) break;
            shift += 7;
        }
        if (length < 0 || length > 4096 || cursor + length != packet.length) {
            throw new IllegalArgumentException("Invalid string payload size");
        }
        return new String(packet, cursor, length, StandardCharsets.UTF_8);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        activeScenes.values().stream().filter(snapshot -> !snapshot.worldKey().isBlank())
                .filter(snapshot -> canView(event.getPlayer(), snapshot.origin()))
                .forEach(snapshot -> send(event.getPlayer(), SCENE_CHANNEL,
                        scenePacket(snapshot, snapshot.privatePlayerId() != null
                                && snapshot.privatePlayerId().equals(event.getPlayer().getUniqueId())
                                ? event.getPlayer().getUniqueId() : null)
                                .toString()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onViewerMove(PlayerMoveEvent event) {
        if (event.getTo() == null) return;
        if (event.getFrom().getWorld().equals(event.getTo().getWorld())
                && (event.getFrom().getBlockX() >> 4) == (event.getTo().getBlockX() >> 4)
                && (event.getFrom().getBlockZ() >> 4) == (event.getTo().getBlockZ() >> 4)) return;

        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        if (!moddedClients.contains(playerId)) return;
        SyncCell next = syncCell(player);
        SyncCell previous = sceneSyncCells.put(playerId, next);
        if (next.equals(previous)) return;

        scheduler.runForEntity(playerId, () -> {
            Player current = Bukkit.getPlayer(playerId);
            if (current == null || !current.isOnline()) return;
            for (Snapshot snapshot : activeScenes.values()) {
                if (!snapshot.worldKey().isBlank() && !canView(current, snapshot.origin())) {
                    sendClear(current, snapshot.sessionId());
                }
            }
            syncVisibleScenes(current);
        }, () -> { });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        moddedClients.remove(id);
        lastActionNanos.remove(id);
        sceneSyncCells.remove(id);
    }

    private Snapshot resolveWorld(Snapshot snapshot) {
        World world = Bukkit.getWorld(snapshot.origin().worldId());
        if (world == null) return null;
        return new Snapshot(snapshot.sessionId(), snapshot.gameId(), snapshot.origin(), snapshot.privatePlayerId(),
                snapshot.title(), snapshot.actionType(), snapshot.options(), world.getKey().toString());
    }

    private void sendSceneTo(UUID viewerId, Snapshot snapshot) {
        if (!moddedClients.contains(viewerId)) return;
        scheduler.runForEntity(viewerId, () -> {
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer == null || !canView(viewer, snapshot.origin())) return;
            UUID recipient = snapshot.privatePlayerId() != null && snapshot.privatePlayerId().equals(viewerId)
                    ? viewerId : null;
            send(viewer, SCENE_CHANNEL, scenePacket(snapshot, recipient).toString());
        }, () -> { });
    }

    private void sendClear(Player player, UUID sessionId) {
        JsonObject packet = new JsonObject();
        packet.addProperty("protocol", PROTOCOL);
        packet.addProperty("kind", "clear");
        packet.addProperty("session", sessionId.toString());
        send(player, SCENE_CHANNEL, packet.toString());
    }

    private static SyncCell syncCell(Player player) {
        return new SyncCell(player.getWorld().getUID(), player.getLocation().getBlockX() >> 4,
                player.getLocation().getBlockZ() >> 4);
    }

    private record Snapshot(UUID sessionId, String gameId, GameLocation origin, UUID privatePlayerId, String title,
                            String actionType, List<MenuOption> options, String worldKey) { }

    private record SyncCell(UUID world, int chunkX, int chunkZ) { }
}
