package io.github.tis199.gamecraft.paper.session;

import io.github.tis199.gamecraft.api.GameLocation;
import io.github.tis199.gamecraft.api.GameModule;
import io.github.tis199.gamecraft.api.GameSession;
import io.github.tis199.gamecraft.paper.furniture.FurnitureManager;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory lobby rooms with host invitations and explicit start/cancel controls. */
public final class GameRoomManager {
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final long EXPIRY_SECONDS = 15 * 60;

    public record InviteView(UUID hostId, String hostName, String gameId, int joined, int capacity) { }
    public record InviteCandidate(UUID playerId, String name) { }
    public record RoomView(UUID hostId, String gameId, int joined, int capacity, boolean host, boolean teamMode) { }

    private final GameSessionManagerImpl sessions;
    private final FurnitureManager furniture;
    private final Map<UUID, Room> byHost = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> invites = new ConcurrentHashMap<>();

    public GameRoomManager(GameSessionManagerImpl sessions, FurnitureManager furniture) {
        this.sessions = sessions;
        this.furniture = furniture;
    }

    public void create(Player host, GameModule module, int seats, String difficulty,
                       boolean teams, int hostSeat) {
        expireOldRooms();
        UUID hostId = host.getUniqueId();
        if (sessions.getPlayerSession(hostId).isPresent()) {
            throw new IllegalStateException("<red>Finish your current game before hosting another.</red>");
        }
        Room membership = roomFor(hostId);
        if (membership != null && !membership.host.equals(hostId)) {
            throw new IllegalStateException("<red>Leave the room you're in before hosting another.</red>");
        }
        Room previous = byHost.get(hostId);
        if (previous != null) remove(previous);
        if (seats < module.minPlayers() || seats > module.maxPlayers()) {
            throw new IllegalArgumentException("<red>Choose between " + module.minPlayers() + " and "
                    + module.maxPlayers() + " players for this game.</red>");
        }
        if (teams && (!module.descriptor().id().equals("ludo") || seats != 4)) {
            throw new IllegalArgumentException("<red>Ludo team play needs a four-player room.</red>");
        }
        if (hostSeat < 0 || hostSeat >= seats) hostSeat = 0;
        Room room = new Room(hostId, module.descriptor().id(), seats, difficulty, teams, hostSeat);
        room.players.add(hostId);
        byHost.put(hostId, room);
        UUID oldInvite = invites.remove(hostId);
        Room oldInviteRoom = oldInvite == null ? null : byHost.get(oldInvite);
        if (oldInviteRoom != null) oldInviteRoom.invited.remove(hostId);
        tell(host, "<gold>✦ Room created!</gold> <gray>" + escape(module.descriptor().displayName())
                + " • " + seats + " seats • " + escape(difficulty) + (teams ? " • <aqua>2v2 teams</aqua>" : "")
                + "</gray>");
        tell(host, "<gray>Invite players with <yellow>/gc invite ‹player›</yellow>. Start with <yellow>/gc start</yellow>.</gray>");
    }

    public void invite(Player host, String targetName) {
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) throw new IllegalArgumentException("<red>That player is not online.</red>");
        invite(host, target.getUniqueId());
    }

    public void invite(Player host, UUID targetId) {
        expireOldRooms();
        Room room = requireHostRoom(host.getUniqueId());
        Player target = Bukkit.getPlayer(targetId);
        if (target == null) throw new IllegalArgumentException("<red>That player is not online.</red>");
        if (target.getUniqueId().equals(host.getUniqueId())) {
            throw new IllegalArgumentException("<gray>You're already in your room.</gray>");
        }
        if (room.players.contains(target.getUniqueId())) {
            throw new IllegalArgumentException("<yellow>That player has already joined.</yellow>");
        }
        if (roomFor(target.getUniqueId()) != null) {
            throw new IllegalStateException("<red>That player is already in a multiplayer room.</red>");
        }
        if (room.players.size() >= room.capacity) {
            throw new IllegalStateException("<red>Your room is full.</red>");
        }
        if (sessions.getPlayerSession(target.getUniqueId()).isPresent()) {
            throw new IllegalStateException("<red>That player is already in a game.</red>");
        }
        UUID existing = invites.get(target.getUniqueId());
        if (existing != null && !existing.equals(room.host)) {
            Room previousRoom = byHost.get(existing);
            if (previousRoom != null) {
                previousRoom.invited.remove(target.getUniqueId());
                previousRoom.updatedAt = Instant.now().getEpochSecond();
            }
            tell(Bukkit.getPlayer(existing), "<gray>Your invitation to <white>" + escape(target.getName())
                    + "</white> was replaced by another invite.</gray>");
        }
        invites.put(target.getUniqueId(), room.host);
        room.invited.add(target.getUniqueId());
        room.updatedAt = Instant.now().getEpochSecond();
        tell(target, "<gold>✉ Game invite!</gold> <yellow>" + escape(host.getName()) + "</yellow> invited you to "
                + "<aqua>" + escape(room.gameId) + "</aqua> <gray>(" + room.players.size() + "/" + room.capacity
                + " joined)</gray>. <green>/gc accept " + escape(host.getName()) + "</green>");
        tell(host, "<green>Invite sent to <white>" + escape(target.getName()) + "</white>.</green>");
    }

    public void accept(Player player, String hostName) {
        expireOldRooms();
        UUID hostId = invites.get(player.getUniqueId());
        if (hostName != null && !hostName.isBlank()) {
            Player host = Bukkit.getPlayerExact(hostName);
            if (host != null) hostId = host.getUniqueId();
        }
        accept(player, hostId);
    }

    public void accept(Player player, UUID hostId) {
        expireOldRooms();
        Room room = hostId == null ? null : byHost.get(hostId);
        if (room == null || !room.invited.contains(player.getUniqueId())) {
            throw new IllegalStateException("<red>You don't have an active GameCraft invite.</red>");
        }
        if (roomFor(player.getUniqueId()) != null) {
            throw new IllegalStateException("<red>Leave your current multiplayer room before joining another.</red>");
        }
        if (sessions.getPlayerSession(player.getUniqueId()).isPresent()) {
            throw new IllegalStateException("<red>Leave your current game before accepting an invite.</red>");
        }
        if (room.players.size() >= room.capacity) {
            room.invited.remove(player.getUniqueId());
            invites.remove(player.getUniqueId(), room.host);
            throw new IllegalStateException("<red>That room is already full.</red>");
        }
        room.invited.remove(player.getUniqueId());
        invites.remove(player.getUniqueId(), room.host);
        room.players.add(player.getUniqueId());
        room.updatedAt = Instant.now().getEpochSecond();
        tell(player, "<green>✓ You joined <yellow>" + playerName(room.host) + "</yellow>'s room.</green>");
        tell(Bukkit.getPlayer(room.host), "<aqua>✦ " + escape(player.getName()) + " joined!</aqua> <gray>("
                + room.players.size() + "/" + room.capacity + ")</gray>");
    }

    public List<InviteView> pendingInvites(Player player) {
        expireOldRooms();
        UUID hostId = invites.get(player.getUniqueId());
        Room room = hostId == null ? null : byHost.get(hostId);
        Player host = hostId == null ? null : Bukkit.getPlayer(hostId);
        if (room == null || host == null || !room.invited.contains(player.getUniqueId())) return List.of();
        return List.of(new InviteView(hostId, host.getName(), room.gameId, room.players.size(), room.capacity));
    }

    public RoomView roomView(Player player) {
        expireOldRooms();
        Room room = byHost.get(player.getUniqueId());
        boolean isHost = room != null;
        if (room == null) {
            room = byHost.values().stream()
                    .filter(candidate -> candidate.players.contains(player.getUniqueId()))
                    .findFirst().orElse(null);
        }
        return room == null ? null : new RoomView(room.host, room.gameId, room.players.size(),
                room.capacity, isHost, room.teamMode);
    }

    public List<InviteCandidate> onlineInviteCandidates(Player host) {
        Room room = requireHostRoom(host.getUniqueId());
        return Bukkit.getOnlinePlayers().stream()
                .filter(candidate -> !room.players.contains(candidate.getUniqueId()))
                .filter(candidate -> !room.invited.contains(candidate.getUniqueId()))
                .filter(candidate -> roomFor(candidate.getUniqueId()) == null)
                .filter(candidate -> sessions.getPlayerSession(candidate.getUniqueId()).isEmpty())
                .map(candidate -> new InviteCandidate(candidate.getUniqueId(), candidate.getName()))
                .sorted(java.util.Comparator.comparing(InviteCandidate::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public void leave(Player player) {
        expireOldRooms();
        Room room = byHost.values().stream()
                .filter(candidate -> candidate.players.contains(player.getUniqueId()))
                .findFirst().orElseThrow(() -> new IllegalStateException("<gray>You're not in a multiplayer room.</gray>"));
        if (room.host.equals(player.getUniqueId())) {
            throw new IllegalStateException("<yellow>Hosts should close their room with the cancel button.</yellow>");
        }
        room.players.remove(player.getUniqueId());
        room.updatedAt = Instant.now().getEpochSecond();
        tell(player, "<gray>You left the <yellow>" + title(room.gameId) + "</yellow> room.</gray>");
        tell(Bukkit.getPlayer(room.host), "<yellow>" + escape(player.getName()) + " left your room.</yellow> <gray>("
                + room.players.size() + "/" + room.capacity + ")</gray>");
    }

    public void start(Player host) {
        Room room = requireHostRoom(host.getUniqueId());
        if (room.players.size() < room.capacity) {
            throw new IllegalStateException("<yellow>Invite " + (room.capacity - room.players.size())
                    + " more player(s) before starting.</yellow>");
        }
        if (room.teamMode && room.players.size() != 4) {
            throw new IllegalStateException("<yellow>2v2 Ludo starts when all four players have joined.</yellow>");
        }
        List<UUID> ordered = seatOrder(room);
        int seats = room.capacity;
        if (room.hostSeat >= seats) {
            throw new IllegalStateException("<yellow>Invite enough players for the side/color you selected.</yellow>");
        }
        FurnitureManager.GameTablePlacement table = furniture.findOrPlaceGameTableForMatch(room.gameId, host);
        Map<String, String> properties = new HashMap<>();
        if (room.teamMode) properties.put("team-mode", "true");
        try {
            GameSession session = sessions.createSession(room.gameId, ordered, room.difficulty, table.origin(),
                    false, seats, properties);
            if (table.temporary()) sessions.removeFurnitureWhenSessionEnds(session, table.furnitureId());
            remove(room);
            session.broadcastMessage("<gold>✦ " + title(room.gameId) + " is live!</gold> <gray>Nearby players can watch the board.</gray>");
        } catch (RuntimeException exception) {
            if (table.temporary()) sessions.removeTemporaryFurnitureIfUnused(table.furnitureId());
            throw new IllegalStateException("<red>Couldn't start the room: " + escape(message(exception)) + "</red>");
        }
    }

    public void cancel(Player host) {
        Room room = requireHostRoom(host.getUniqueId());
        for (UUID playerId : room.players) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) tell(player, "<gray>The <yellow>" + title(room.gameId) + "</yellow> room was closed by its host.</gray>");
        }
        remove(room);
    }

    public void show(Player player) {
        expireOldRooms();
        Room own = byHost.get(player.getUniqueId());
        if (own != null) {
            tell(player, "<gold>✦ Your room</gold> <aqua>" + title(own.gameId) + "</aqua> <gray>• "
                    + own.players.size() + "/" + own.capacity + (own.teamMode ? " • teams 2v2" : "") + "</gray>");
            return;
        }
        Room joined = byHost.values().stream().filter(room -> room.players.contains(player.getUniqueId())).findFirst().orElse(null);
        if (joined != null) {
            tell(player, "<gold>✦ Room</gold> <aqua>" + title(joined.gameId) + "</aqua> <gray>hosted by "
                    + playerName(joined.host) + " • " + joined.players.size() + "/" + joined.capacity + "</gray>");
            return;
        }
        UUID invite = invites.get(player.getUniqueId());
        if (invite != null && byHost.containsKey(invite)) {
            tell(player, "<gold>✉ Pending invite</gold> <gray>from " + playerName(invite)
                    + ". Accept with <yellow>/gc accept</yellow>.</gray>");
        } else {
            tell(player, "<gray>You aren't in a room. Create one from <yellow>/gc play</yellow>.</gray>");
        }
    }

    private Room requireHostRoom(UUID player) {
        Room room = byHost.get(player);
        if (room == null) throw new IllegalStateException("<yellow>Host a multiplayer room first with <gold>/gc play</gold>.</yellow>");
        room.updatedAt = Instant.now().getEpochSecond();
        return room;
    }

    private Room roomFor(UUID playerId) {
        return byHost.values().stream()
                .filter(room -> room.players.contains(playerId))
                .findFirst().orElse(null);
    }

    private List<UUID> seatOrder(Room room) {
        List<UUID> order = new ArrayList<>(room.capacity);
        for (int i = 0; i < room.capacity; i++) order.add(null);
        order.set(room.hostSeat, room.host);
        for (UUID player : room.players) {
            if (player.equals(room.host)) continue;
            int seat = order.indexOf(null);
            if (seat < 0) break;
            order.set(seat, player);
        }
        return List.copyOf(order);
    }

    private void expireOldRooms() {
        long now = Instant.now().getEpochSecond();
        for (Room room : byHost.values()) {
            if (now - room.updatedAt > EXPIRY_SECONDS) remove(room);
        }
    }

    private void remove(Room room) {
        byHost.remove(room.host, room);
        for (UUID invitee : room.invited) invites.remove(invitee, room.host);
        room.invited.clear();
    }

    private static GameLocation toGameLocation(Location location) {
        return new GameLocation(location.getWorld().getUID(), location.getX(), location.getY(), location.getZ());
    }

    private static void tell(Player player, String miniMessage) {
        if (player != null) player.sendMessage(MINI.deserialize(miniMessage));
    }

    private static String playerName(UUID id) {
        Player player = Bukkit.getPlayer(id);
        return escape(player == null ? "offline player" : player.getName());
    }

    private static String title(String id) {
        return switch (id) {
            case "uno" -> "UNO";
            case "chinese-checkers" -> "Chinese Checkers";
            default -> id.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + id.substring(1);
        };
    }

    private static String escape(String text) {
        return MINI.escapeTags(text);
    }

    private static String message(Throwable exception) {
        String value = exception.getMessage();
        return MINI.escapeTags(value == null || value.isBlank() ? "unknown error" : value);
    }

    private static final class Room {
        private final UUID host;
        private final String gameId;
        private final int capacity;
        private final String difficulty;
        private final boolean teamMode;
        private final int hostSeat;
        private final LinkedHashSet<UUID> players = new LinkedHashSet<>();
        private final LinkedHashSet<UUID> invited = new LinkedHashSet<>();
        private volatile long updatedAt = Instant.now().getEpochSecond();

        private Room(UUID host, String gameId, int capacity, String difficulty, boolean teamMode, int hostSeat) {
            this.host = host;
            this.gameId = gameId;
            this.capacity = capacity;
            this.difficulty = difficulty;
            this.teamMode = teamMode;
            this.hostSeat = hostSeat;
        }
    }
}
