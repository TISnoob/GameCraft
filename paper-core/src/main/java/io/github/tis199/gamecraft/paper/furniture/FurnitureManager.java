package io.github.tis199.gamecraft.paper.furniture;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.tis199.gamecraft.api.FurnitureService;
import io.github.tis199.gamecraft.api.GameLocation;
import io.github.tis199.gamecraft.paper.GameCraftPlugin;
import io.github.tis199.gamecraft.paper.platform.PaperScheduler;
import io.github.tis199.gamecraft.paper.session.GameSessionManagerImpl;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.Comparator;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Places real table and chair blocks. Only the invisible seat anchor is an entity. */
public final class FurnitureManager implements FurnitureService, Listener, AutoCloseable {
    private static final String FURNITURE_CHANNEL = "gamecraft:furniture";
    private static final int PROTOCOL = 1;
    private static final double VIEW_DISTANCE_SQUARED = 64.0 * 64.0;
    private final GameCraftPlugin plugin;
    private final PaperScheduler scheduler;
    private final GameSessionManagerImpl sessions;
    private final NamespacedKey furnitureKey;
    private final NamespacedKey kindKey;
    private final NamespacedKey tableItemKey;
    private final Map<UUID, GameLocation> locations = new ConcurrentHashMap<>();
    private final Map<UUID, GameLocation> tableOrigins = new ConcurrentHashMap<>();
    private final Map<UUID, String> tableGames = new ConcurrentHashMap<>();
    private final Set<UUID> temporaryTables = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> chairGames = new ConcurrentHashMap<>();
    private final Map<UUID, Map<BlockKey, BlockRecord>> furnitureBlocks = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> seatEntities = new ConcurrentHashMap<>();
    private final Map<BlockKey, UUID> chairsByBlock = new ConcurrentHashMap<>();
    private final Map<BlockKey, UUID> tablesByBlock = new ConcurrentHashMap<>();
    private final Map<UUID, SyncCell> furnitureSyncCells = new ConcurrentHashMap<>();

    public FurnitureManager(GameCraftPlugin plugin, PaperScheduler scheduler, GameSessionManagerImpl sessions) {
        this.plugin = plugin;
        this.scheduler = scheduler;
        this.sessions = sessions;
        this.furnitureKey = new NamespacedKey(plugin, "furniture-id");
        this.kindKey = new NamespacedKey(plugin, "furniture-kind");
        this.tableItemKey = new NamespacedKey(plugin, "table-item");
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, FURNITURE_CHANNEL);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    private static String title(String id) {
        return switch (id) {
            case "uno" -> "UNO";
            case "chinese-checkers" -> "Chinese Checkers";
            default -> id.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + id.substring(1);
        };
    }

    @Override
    public UUID placeTable(GameLocation target, int width, int depth) {
        validateDimensions(width, depth);
        UUID furnitureId = UUID.randomUUID();
        locations.put(furnitureId, target);
        GameLocation center = offset(target, width / 2.0, 1.0, depth / 2.0);
        tableOrigins.put(furnitureId, center);
        BlockData top = Material.DARK_OAK_SLAB.createBlockData();
        ((Slab) top).setType(Slab.Type.TOP);
        placeTableBlocks(furnitureId, target, width, depth, top, null);
        publishFurniture();
        return furnitureId;
    }

    /** Gives a table item which is placeable in-world; tables have no crafting recipe. */
    public void giveTableItem(Player recipient, String gameId) {
        TableStyle style = TableStyle.forGame(gameId);
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(title(style.id()) + " Table"));
        meta.setCustomModelData(tableModelId(style.id()));
        meta.getPersistentDataContainer().set(tableItemKey, PersistentDataType.STRING, style.id());
        item.setItemMeta(meta);
        Map<Integer, ItemStack> overflow = recipient.getInventory().addItem(item);
        overflow.values().forEach(stack ->
                recipient.getWorld().dropItemNaturally(recipient.getLocation(), stack));
    }

    private static int tableModelId(String gameId) {
        return switch (gameId) {
            case "chess" -> 910;
            case "ludo" -> 911;
            case "chinese-checkers" -> 912;
            case "checkers" -> 913;
            case "monopoly" -> 914;
            case "uno" -> 915;
            case "solitaire" -> 916;
            case "sudoku" -> 917;
            default -> throw new IllegalArgumentException("No GameCraft table style is defined for '" + gameId + "'");
        };
    }

    /** Per-game footprints follow the texture/model dimensions used by the client renderer. */
    @Override
    public UUID placeGameTable(String gameId, GameLocation target) {
        TableStyle style = TableStyle.forGame(gameId);
        UUID furnitureId = UUID.randomUUID();
        locations.put(furnitureId, target);
        tableGames.put(furnitureId, style.id());
        tableOrigins.put(furnitureId, offset(target, style.width() / 2.0, 1.0, style.depth() / 2.0));
        BlockData top = Material.SPRUCE_SLAB.createBlockData();
        ((Slab) top).setType(Slab.Type.TOP);
        placeTableBlocks(furnitureId, target, style.width(), style.depth(), top, style.id());
        publishFurniture();
        return furnitureId;
    }

    /** Finds a nearby matching table, or places a real game-sized table ahead of the player. */
    public GameLocation findOrPlaceGameTable(String gameId, Player player) {
        GameLocation playerPosition = new GameLocation(player.getWorld().getUID(), player.getLocation().getX(),
                player.getLocation().getY(), player.getLocation().getZ());
        Optional<GameLocation> existing = findTableNear(playerPosition, 16.0, gameId);
        if (existing.isPresent()) return existing.get();
        return placeGameTableAhead(gameId, player, false).origin();
    }

    /** Finds a persistent table or creates a temporary one owned by the next match. */
    public GameTablePlacement findOrPlaceGameTableForMatch(String gameId, Player player) {
        GameLocation playerPosition = new GameLocation(player.getWorld().getUID(), player.getLocation().getX(),
                player.getLocation().getY(), player.getLocation().getZ());
        Optional<Map.Entry<UUID, GameLocation>> existing = tableOrigins.entrySet().stream()
                .filter(entry -> gameId.equals(tableGames.get(entry.getKey())))
                .filter(entry -> entry.getValue().worldId().equals(playerPosition.worldId()))
                .filter(entry -> squaredDistance(entry.getValue(), playerPosition) <= 16.0 * 16.0)
                .min(Comparator.comparingDouble(entry -> squaredDistance(entry.getValue(), playerPosition)));
        if (existing.isPresent()) {
            Map.Entry<UUID, GameLocation> table = existing.get();
            return new GameTablePlacement(table.getValue(), table.getKey(), temporaryTables.contains(table.getKey()));
        }

        return placeGameTableAhead(gameId, player, true);
    }

    private GameTablePlacement placeGameTableAhead(String gameId, Player player, boolean temporary) {
        TableStyle style = TableStyle.forGame(gameId);
        org.bukkit.util.Vector forward = player.getLocation().getDirection().setY(0);
        if (forward.lengthSquared() < 0.001) forward.setZ(1); else forward.normalize();
        int centerX = player.getLocation().getBlockX();
        int centerZ = player.getLocation().getBlockZ();
        if (Math.abs(forward.getX()) > Math.abs(forward.getZ())) centerX += (int) Math.signum(forward.getX()) * 5;
        else centerZ += (int) Math.signum(forward.getZ()) * 5;
        double parityX = style.width() % 2 == 0 ? 0.0 : 0.5;
        double parityZ = style.depth() % 2 == 0 ? 0.0 : 0.5;
        GameLocation surfaceCenter = new GameLocation(player.getWorld().getUID(), centerX + parityX,
                player.getLocation().getBlockY() + 1.0, centerZ + parityZ);
        return placeGameTableAtCenter(gameId, surfaceCenter, temporary);
    }

    private GameTablePlacement placeGameTableAtCenter(String gameId, GameLocation surfaceCenter, boolean temporary) {
        TableStyle style = TableStyle.forGame(gameId);
        double centerX = Math.floor(surfaceCenter.x()) + (style.width() % 2 == 0 ? 0 : 0.5);
        double centerZ = Math.floor(surfaceCenter.z()) + (style.depth() % 2 == 0 ? 0 : 0.5);
        double floorY = Math.floor(surfaceCenter.y()) - 1.0;
        GameLocation corner = new GameLocation(surfaceCenter.worldId(), centerX - style.width() / 2.0,
                floorY, centerZ - style.depth() / 2.0);
        UUID id = placeGameTable(gameId, corner);
        if (temporary) temporaryTables.add(id);
        return new GameTablePlacement(tableOrigins.getOrDefault(id, surfaceCenter), id, temporary);
    }

    private void placeTableBlocks(UUID furnitureId, GameLocation corner, int width, int depth,
                                  BlockData top, String gameId) {
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                GameLocation at = offset(corner, x, 0, z);
                BlockKey blockKey = BlockKey.at(at);
                tablesByBlock.put(blockKey, furnitureId);
                scheduleBlock(at, furnitureId, top, gameId == null ? Material.DARK_OAK_SLAB : Material.SPRUCE_SLAB);
            }
        }
    }

    @Override
    public UUID placeChair(GameLocation target) {
        return placeChair(target, null);
    }

    private UUID placeChair(GameLocation target, String gameId) {
        UUID furnitureId = UUID.randomUUID();
        locations.put(furnitureId, target);
        if (gameId != null) chairGames.put(furnitureId, gameId.toLowerCase(java.util.Locale.ROOT));
        BlockData chair = Material.SPRUCE_STAIRS.createBlockData();
        if (chair instanceof Stairs stairs) {
            stairs.setFacing(BlockFace.SOUTH);
            stairs.setHalf(Stairs.Half.BOTTOM);
        }
        BlockKey chairKey = BlockKey.at(target);
        chairsByBlock.put(chairKey, furnitureId);
        scheduleBlock(target, furnitureId, chair, Material.SPRUCE_STAIRS);

        scheduler.runAt(target, () -> {
            if (!locations.containsKey(furnitureId)) return;
            Location base = location(target);
            if (base.getWorld() == null) return;
            ArmorStand seat = base.getWorld().spawn(base.clone().add(0.5, 0, 0.5), ArmorStand.class);
            seat.setVisible(false);
            seat.setInvulnerable(true);
            seat.setGravity(false);
            seat.setSilent(true);
            seat.setBasePlate(false);
            seat.setCanPickupItems(false);
            seat.getPersistentDataContainer().set(kindKey, PersistentDataType.STRING, "seat");
            seat.getPersistentDataContainer().set(furnitureKey, PersistentDataType.STRING, furnitureId.toString());
            seatEntities.put(furnitureId, seat.getUniqueId());
            if (!locations.containsKey(furnitureId)) seat.remove();
        });
        publishFurniture();
        return furnitureId;
    }

    @Override
    public UUID placeGameChair(String gameId, GameLocation target) {
        TableStyle.forGame(gameId);
        return placeChair(target, gameId.toLowerCase(java.util.Locale.ROOT));
    }

    private void scheduleBlock(GameLocation at, UUID furnitureId, BlockData placed, Material replaceWith) {
        scheduler.runAt(at, () -> {
            if (!locations.containsKey(furnitureId)) return;
            Location location = location(at);
            if (location.getWorld() == null) return;
            Block block = location.getBlock();
            BlockKey key = BlockKey.at(at);
            BlockRecord existing = furnitureBlocks.computeIfAbsent(furnitureId, ignored -> new ConcurrentHashMap<>()).get(key);
            if (existing == null) {
                if (!block.isEmpty() && !block.isPassable()) {
                    plugin.getLogger().fine("Skipped occupied furniture block at " + key);
                    return;
                }
                existing = new BlockRecord(block.getBlockData().clone(), placed.clone());
                furnitureBlocks.get(furnitureId).put(key, existing);
            } else {
                existing = new BlockRecord(existing.original(), placed.clone());
                furnitureBlocks.get(furnitureId).put(key, existing);
            }
            block.setBlockData(placed, false);
        });
    }

    @Override
    public boolean remove(UUID furnitureId) {
        if (locations.remove(furnitureId) == null) return false;
        tableOrigins.remove(furnitureId);
        tableGames.remove(furnitureId);
        temporaryTables.remove(furnitureId);
        chairGames.remove(furnitureId);
        UUID seatId = seatEntities.remove(furnitureId);
        if (seatId != null) scheduler.runForEntity(seatId, () -> {
            Entity seat = Bukkit.getEntity(seatId);
            if (seat != null) seat.remove();
        }, () -> { });
        chairsByBlock.entrySet().removeIf(entry -> entry.getValue().equals(furnitureId));
        tablesByBlock.entrySet().removeIf(entry -> entry.getValue().equals(furnitureId));
        Map<BlockKey, BlockRecord> blocks = furnitureBlocks.remove(furnitureId);
        if (blocks != null) blocks.forEach((key, record) -> {
            GameLocation at = key.location();
            scheduler.runAt(at, () -> {
                Location location = location(at);
                if (location.getWorld() == null) return;
                Block block = location.getBlock();
                if (block.getBlockData().matches(record.placed())) block.setBlockData(record.original(), false);
            });
        });
        publishFurniture();
        return true;
    }

    /** Sends the furniture visible in a player's world to the client renderer. */
    public void sync(Player player) {
        UUID playerId = player.getUniqueId();
        furnitureSyncCells.put(playerId, syncCell(player));
        scheduler.runGlobal(() -> sendFurnitureSnapshot(playerId, furnitureSnapshots()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onViewerMove(PlayerMoveEvent event) {
        if (event.getTo() == null || (event.getFrom().getBlockX() >> 4) == (event.getTo().getBlockX() >> 4)
                && (event.getFrom().getBlockZ() >> 4) == (event.getTo().getBlockZ() >> 4)
                && event.getFrom().getWorld().equals(event.getTo().getWorld())) return;
        Player player = event.getPlayer();
        if (plugin.boardService() == null || !plugin.boardService().isClientModReady(player.getUniqueId())) return;
        SyncCell next = syncCell(player);
        SyncCell previous = furnitureSyncCells.put(player.getUniqueId(), next);
        if (previous != null && !previous.equals(next)) {
            UUID playerId = player.getUniqueId();
            scheduler.runGlobal(() -> sendFurnitureSnapshot(playerId, furnitureSnapshots()));
        }
    }

    @EventHandler
    public void onViewerQuit(PlayerQuitEvent event) {
        furnitureSyncCells.remove(event.getPlayer().getUniqueId());
    }

    private static SyncCell syncCell(Player player) {
        Location location = player.getLocation();
        return new SyncCell(player.getWorld().getUID(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    private void publishFurniture() {
        scheduler.runGlobal(() -> {
            java.util.List<FurnitureSnapshot> snapshots = furnitureSnapshots();
            for (Player player : Bukkit.getOnlinePlayers()) {
                UUID playerId = player.getUniqueId();
                if (plugin.boardService() != null && !plugin.boardService().isClientModReady(playerId)) continue;
                scheduler.runForEntity(playerId, () -> sendFurnitureSnapshot(playerId, snapshots), () -> { });
            }
        });
    }

    private java.util.List<FurnitureSnapshot> furnitureSnapshots() {
        java.util.List<FurnitureSnapshot> result = new java.util.ArrayList<>();
        for (Map.Entry<UUID, GameLocation> entry : tableOrigins.entrySet()) {
            String game = tableGames.get(entry.getKey());
            if (game == null) continue;
            TableStyle style = TableStyle.forGame(game);
            org.bukkit.World world = Bukkit.getWorld(entry.getValue().worldId());
            if (world == null) continue;
            GameLocation location = entry.getValue();
            result.add(new FurnitureSnapshot(entry.getKey(), "table", game, world.getKey().toString(),
                    location.x(), location.y(), location.z(), style.width(), style.depth()));
        }
        for (Map.Entry<BlockKey, UUID> entry : chairsByBlock.entrySet()) {
            UUID id = entry.getValue();
            GameLocation location = locations.get(id);
            if (location == null) continue;
            org.bukkit.World world = Bukkit.getWorld(location.worldId());
            if (world == null) continue;
            result.add(new FurnitureSnapshot(id, "chair", chairGames.getOrDefault(id, ""),
                    world.getKey().toString(), location.x(), location.y(), location.z(), 1, 1));
        }
        return java.util.List.copyOf(result);
    }

    private void sendFurnitureSnapshot(UUID playerId, java.util.List<FurnitureSnapshot> all) {
        scheduler.runForEntity(playerId, () -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) return;
            JsonObject packet = new JsonObject();
            packet.addProperty("protocol", PROTOCOL);
            packet.addProperty("kind", "snapshot");
            JsonArray items = new JsonArray();
            for (FurnitureSnapshot snapshot : all) {
                if (!snapshot.world().equals(player.getWorld().getKey().toString())) continue;
                double dx = player.getLocation().getX() - snapshot.x();
                double dy = player.getLocation().getY() - snapshot.y();
                double dz = player.getLocation().getZ() - snapshot.z();
                if (dx * dx + dy * dy + dz * dz > VIEW_DISTANCE_SQUARED) continue;
                JsonObject item = new JsonObject();
                item.addProperty("id", snapshot.id().toString());
                item.addProperty("type", snapshot.type());
                item.addProperty("game", snapshot.game());
                item.addProperty("world", snapshot.world());
                item.addProperty("x", snapshot.x());
                item.addProperty("y", snapshot.y());
                item.addProperty("z", snapshot.z());
                item.addProperty("width", snapshot.width());
                item.addProperty("depth", snapshot.depth());
                items.add(item);
            }
            packet.add("items", items);
            send(player, packet.toString());
        }, () -> { });
    }

    private void send(Player player, String json) {
        byte[] text = json.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream packet = new ByteArrayOutputStream(text.length + 5);
        int remaining = text.length;
        while ((remaining & 0xFFFFFF80) != 0) {
            packet.write((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        packet.write(remaining);
        packet.writeBytes(text);
        player.sendPluginMessage(plugin, FURNITURE_CHANNEL, packet.toByteArray());
    }

    public Optional<GameLocation> findTableNear(GameLocation point, double maxDistance) {
        return findTableNear(point, maxDistance, null);
    }

    public Optional<GameLocation> findTableNear(GameLocation point, double maxDistance, String gameId) {
        double limit = maxDistance * maxDistance;
        return tableOrigins.entrySet().stream()
                .filter(entry -> gameId == null || gameId.equals(tableGames.get(entry.getKey())))
                .map(Map.Entry::getValue)
                .filter(table -> table.worldId().equals(point.worldId()))
                .filter(table -> squaredDistance(table, point) <= limit)
                .min(Comparator.comparingDouble(table -> squaredDistance(table, point)));
    }

    @Override
    public boolean sit(UUID playerId, UUID chairId) {
        if (sessions.getPlayerSession(playerId).isPresent()) return false;
        UUID seatId = seatEntities.get(chairId);
        if (seatId == null) return false;
        scheduler.runForEntity(playerId, () -> {
            Player player = Bukkit.getPlayer(playerId);
            Entity seat = Bukkit.getEntity(seatId);
            if (player == null || !player.isOnline() || !(seat instanceof ArmorStand)
                    || !Bukkit.isOwnedByCurrentRegion(seat)
                    || !"seat".equals(seat.getPersistentDataContainer().get(kindKey, PersistentDataType.STRING))
                    || !seat.getWorld().equals(player.getWorld())
                    || seat.getLocation().distanceSquared(player.getLocation()) > 9.0) return;
            seat.addPassenger(player);
        }, () -> { });
        return true;
    }

    @Override
    public boolean stand(UUID playerId) {
        scheduler.runForEntity(playerId, () -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) return;
            Entity vehicle = player.getVehicle();
            if (vehicle == null || !Bukkit.isOwnedByCurrentRegion(vehicle)
                    || !"seat".equals(vehicle.getPersistentDataContainer().get(kindKey, PersistentDataType.STRING))) {
                player.sendMessage("You are not sitting in a GameCraft chair.");
                return;
            }
            vehicle.removePassenger(player);
        }, () -> { });
        return true;
    }

    public boolean sitNearest(Player player) {
        if (sessions.getPlayerSession(player.getUniqueId()).isPresent()) return false;
        BlockKey nearest = chairsByBlock.keySet().stream()
                .filter(key -> key.world().equals(player.getWorld().getUID()))
                .filter(key -> squaredDistance(key.location(), new GameLocation(key.world(), player.getLocation().getX(),
                        player.getLocation().getY(), player.getLocation().getZ())) <= 6.25)
                .min(Comparator.comparingDouble(key -> squaredDistance(key.location(),
                        new GameLocation(key.world(), player.getLocation().getX(), player.getLocation().getY(), player.getLocation().getZ()))))
                .orElse(null);
        return nearest != null && sit(player.getUniqueId(), chairsByBlock.get(nearest));
    }

    @EventHandler(ignoreCancelled = true)
    public void onFurnitureInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        Player player = event.getPlayer();
        if (event.getHand() == EquipmentSlot.HAND) {
            ItemStack held = event.getItem();
            if (held != null) {
                String tableGame = held.getItemMeta().getPersistentDataContainer()
                        .get(tableItemKey, PersistentDataType.STRING);
                if (tableGame != null) {
                    event.setCancelled(true);
                    try {
                        TableStyle.forGame(tableGame);
                        Block placement = event.getClickedBlock().getRelative(event.getBlockFace());
                        placeGameTable(tableGame, new GameLocation(placement.getWorld().getUID(),
                                placement.getX(), placement.getY(), placement.getZ()));
                        if (player.getGameMode() != GameMode.CREATIVE) {
                            ItemStack handItem = player.getInventory().getItemInMainHand();
                            if (handItem.getAmount() <= 1) player.getInventory().setItemInMainHand(null);
                            else handItem.setAmount(handItem.getAmount() - 1);
                        }
                    } catch (IllegalArgumentException exception) {
                        plugin.getLogger().warning("Ignored invalid GameCraft table item: " + exception.getMessage());
                        player.sendMessage(Component.text("That GameCraft table item is invalid."));
                    }
                    return;
                }
            }
        }
        BlockKey key = BlockKey.of(event.getClickedBlock());
        UUID chairId = chairsByBlock.get(key);
        UUID tableId = tablesByBlock.get(key);
        if (chairId == null && tableId == null) return;
        event.setCancelled(true);
        if (sessions.getPlayerSession(player.getUniqueId()).isPresent()) return;
        if (chairId != null) sit(player.getUniqueId(), chairId);
        else Bukkit.dispatchCommand(player, "gamecraft play");
    }

    private static Location location(GameLocation target) {
        return new Location(Bukkit.getWorld(target.worldId()), target.x(), target.y(), target.z());
    }

    private static GameLocation offset(GameLocation from, double x, double y, double z) {
        return new GameLocation(from.worldId(), from.x() + x, from.y() + y, from.z() + z);
    }

    private static double squaredDistance(GameLocation first, GameLocation second) {
        double x = first.x() - second.x();
        double y = first.y() - second.y();
        double z = first.z() - second.z();
        return x * x + y * y + z * z;
    }

    private static void validateDimensions(int width, int depth) {
        if (width < 1 || width > 3 || depth < 1 || depth > 3) {
            throw new IllegalArgumentException("Game tables must be between 1 and 3 blocks wide and deep");
        }
    }

    @Override
    public void close() {
        for (UUID seatId : seatEntities.values()) {
            scheduler.runForEntity(seatId, () -> {
                Entity seat = Bukkit.getEntity(seatId);
                if (seat != null) seat.remove();
            }, () -> { });
        }
        locations.clear();
        tableOrigins.clear();
        tableGames.clear();
        temporaryTables.clear();
        chairGames.clear();
        furnitureBlocks.clear();
        seatEntities.clear();
        chairsByBlock.clear();
        tablesByBlock.clear();
        furnitureSyncCells.clear();
        Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin, FURNITURE_CHANNEL);
    }

    private record FurnitureSnapshot(UUID id, String type, String game, String world,
                                     double x, double y, double z, int width, int depth) { }
    public record GameTablePlacement(GameLocation origin, UUID furnitureId, boolean temporary) { }
    private record SyncCell(UUID world, int chunkX, int chunkZ) { }

    private record TableStyle(String id, int width, int depth) {
        private static TableStyle forGame(String gameId) {
            String id = gameId == null ? "" : gameId.toLowerCase(java.util.Locale.ROOT);
            return switch (id) {
                case "chess", "checkers", "ludo", "chinese-checkers", "monopoly", "sudoku" ->
                        new TableStyle(id, 3, 3);
                case "uno", "solitaire" -> new TableStyle(id, 3, 2);
                default -> throw new IllegalArgumentException("No GameCraft table style is defined for '" + gameId + "'");
            };
        }
    }

    private record BlockKey(UUID world, int x, int y, int z) {
        static BlockKey at(GameLocation location) {
            return new BlockKey(location.worldId(), (int) Math.floor(location.x()),
                    (int) Math.floor(location.y()), (int) Math.floor(location.z()));
        }
        static BlockKey of(Block block) {
            return new BlockKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }
        GameLocation location() { return new GameLocation(world, x, y, z); }
    }

    private record BlockRecord(BlockData original, BlockData placed) { }
}
