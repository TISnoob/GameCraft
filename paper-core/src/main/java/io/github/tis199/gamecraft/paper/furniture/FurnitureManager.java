package io.github.tis199.gamecraft.paper.furniture;

import io.github.tis199.gamecraft.api.FurnitureService;
import io.github.tis199.gamecraft.api.GameLocation;
import io.github.tis199.gamecraft.paper.platform.PaperScheduler;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Minimal public-API furniture renderer using display and interaction entities. */
public final class FurnitureManager implements FurnitureService, Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final PaperScheduler scheduler;
    private final NamespacedKey kindKey;
    private final NamespacedKey idKey;
    private final NamespacedKey seatKey;
    private final Map<UUID, Location> locations = new HashMap<>();

    public FurnitureManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.scheduler = new PaperScheduler(plugin);
        this.kindKey = new NamespacedKey(plugin, "furniture-kind");
        this.idKey = new NamespacedKey(plugin, "furniture-id");
        this.seatKey = new NamespacedKey(plugin, "seat-entity");
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public UUID placeTable(GameLocation target, int width, int depth) {
        validateDimensions(width, depth);
        UUID furnitureId = UUID.randomUUID();
        scheduler.runAt(target, () -> {
            Location base = location(target);
            locations.put(furnitureId, base.clone());
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    Location tile = base.clone().add(x, 0.95, z);
                    BlockDisplay display = base.getWorld().spawn(tile, BlockDisplay.class);
                    BlockData slab = Material.SPRUCE_SLAB.createBlockData();
                    display.setBlock(slab);
                    display.getPersistentDataContainer().set(kindKey, PersistentDataType.STRING, "table");
                    display.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, furnitureId.toString());
                }
            }
            Interaction interaction = base.getWorld().spawn(base.clone().add(width / 2.0, 0.1, depth / 2.0),
                    Interaction.class);
            interaction.setInteractionWidth(Math.max(1.0f, width));
            interaction.setInteractionHeight(1.2f);
            interaction.setResponsive(true);
            interaction.getPersistentDataContainer().set(kindKey, PersistentDataType.STRING, "table");
            interaction.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, furnitureId.toString());
        });
        return furnitureId;
    }

    @Override
    public UUID placeChair(GameLocation target) {
        UUID furnitureId = UUID.randomUUID();
        scheduler.runAt(target, () -> {
            Location base = location(target);
            locations.put(furnitureId, base.clone());
            BlockDisplay visual = base.getWorld().spawn(base.clone().add(0, 0.5, 0), BlockDisplay.class);
            visual.setBlock(Material.SPRUCE_STAIRS.createBlockData());
            visual.getPersistentDataContainer().set(kindKey, PersistentDataType.STRING, "chair");
            visual.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, furnitureId.toString());

            ArmorStand seat = base.getWorld().spawn(base.clone().add(0.5, 0, 0.5), ArmorStand.class);
            seat.setVisible(false);
            seat.setInvulnerable(true);
            seat.setGravity(false);
            seat.setSilent(true);
            seat.setBasePlate(false);
            seat.setCanPickupItems(false);
            seat.getPersistentDataContainer().set(kindKey, PersistentDataType.STRING, "seat");
            seat.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, furnitureId.toString());

            Interaction interaction = base.getWorld().spawn(base.clone().add(0.5, 0, 0.5), Interaction.class);
            interaction.setInteractionWidth(0.9f);
            interaction.setInteractionHeight(1.4f);
            interaction.setResponsive(true);
            interaction.getPersistentDataContainer().set(kindKey, PersistentDataType.STRING, "chair");
            interaction.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, furnitureId.toString());
            interaction.getPersistentDataContainer().set(seatKey, PersistentDataType.STRING, seat.getUniqueId().toString());
        });
        return furnitureId;
    }

    @Override
    public boolean remove(UUID furnitureId) {
        Location known = locations.remove(furnitureId);
        if (known == null) {
            return false;
        }
        GameLocation target = new GameLocation(known.getWorld().getUID(), known.getX(), known.getY(), known.getZ());
        scheduler.runAt(target, () -> known.getWorld().getNearbyEntities(known, 20, 10, 20).forEach(entity -> {
            String taggedId = entity.getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
            if (furnitureId.toString().equals(taggedId)) {
                entity.remove();
            }
        }));
        return true;
    }

    @Override
    public boolean sit(UUID playerId, UUID chairId) {
        Entity entity = Bukkit.getEntity(chairId);
        Player player = Bukkit.getPlayer(playerId);
        if (entity == null || player == null) {
            return false;
        }
        scheduler.runForEntity(entity.getUniqueId(), () -> entity.addPassenger(player), () -> { });
        return true;
    }

    @Override
    public boolean stand(UUID playerId) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || player.getVehicle() == null) {
            return false;
        }
        Entity vehicle = player.getVehicle();
        scheduler.runForEntity(playerId, () -> vehicle.removePassenger(player), () -> { });
        return true;
    }

    public boolean sitNearest(Player player) {
        return player.getNearbyEntities(2.5, 2.0, 2.5).stream()
                .filter(entity -> entity instanceof Interaction)
                .filter(entity -> "chair".equals(entity.getPersistentDataContainer()
                        .get(kindKey, PersistentDataType.STRING)))
                .map(entity -> entity.getPersistentDataContainer().get(seatKey, PersistentDataType.STRING))
                .filter(java.util.Objects::nonNull)
                .map(value -> {
                    try {
                        return UUID.fromString(value);
                    } catch (IllegalArgumentException ignored) {
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .map(seat -> sit(player.getUniqueId(), seat))
                .orElse(false);
    }

    @EventHandler
    public void onFurnitureInteract(PlayerInteractAtEntityEvent event) {
        String kind = event.getRightClicked().getPersistentDataContainer()
                .get(kindKey, PersistentDataType.STRING);
        if (kind == null) {
            return;
        }
        event.setCancelled(true);
        if (kind.equals("chair")) {
            String seatId = event.getRightClicked().getPersistentDataContainer()
                    .get(seatKey, PersistentDataType.STRING);
            if (seatId != null) {
                try {
                    sit(event.getPlayer().getUniqueId(), UUID.fromString(seatId));
                } catch (IllegalArgumentException ignored) {
                    plugin.getLogger().warning("Ignoring malformed GameCraft chair seat id.");
                }
            }
        } else if (kind.equals("table")) {
            event.getPlayer().sendMessage("GameCraft table ready. Enable a game module to use it.");
        }
    }

    private static Location location(GameLocation target) {
        return new Location(Bukkit.getWorld(target.worldId()), target.x(), target.y(), target.z());
    }

    private static void validateDimensions(int width, int depth) {
        if (width < 1 || width > 16 || depth < 1 || depth > 16) {
            throw new IllegalArgumentException("Furniture dimensions must be between 1 and 16 blocks");
        }
    }

    @Override
    public void close() {
        locations.clear();
    }
}
