package io.github.tis199.gamecraft.paper.session;

import io.github.tis199.gamecraft.api.GameLocation;
import io.github.tis199.gamecraft.api.GameAction;
import io.github.tis199.gamecraft.api.GameModule;
import io.github.tis199.gamecraft.api.GameScheduler;
import io.github.tis199.gamecraft.api.GameSession;
import io.github.tis199.gamecraft.api.GameSessionManager;
import io.github.tis199.gamecraft.paper.GameCraftPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventPriority;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Owns active matches and ensures a player can only be in one match at a time. */
public final class GameSessionManagerImpl implements GameSessionManager, Listener {
    private final GameCraftPlugin plugin;
    private final GameScheduler scheduler;
    private final Map<UUID, GameSessionImpl> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> playerToSession = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> temporaryFurnitureBySession = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> sessionsByTemporaryFurniture = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerInventorySnapshot> playerInventories = new ConcurrentHashMap<>();
    private final NamespacedKey gameControlKey;
    private final NamespacedKey gameControlTypeKey;
    private final NamespacedKey gameControlChoiceKey;
    private final NamespacedKey inventorySnapshotKey;

    public GameSessionManagerImpl(GameCraftPlugin plugin, GameScheduler scheduler) {
        this.plugin = plugin;
        this.scheduler = scheduler;
        this.gameControlKey = new NamespacedKey(plugin, "game-control");
        this.gameControlTypeKey = new NamespacedKey(plugin, "game-control-type");
        this.gameControlChoiceKey = new NamespacedKey(plugin, "game-control-choice");
        this.inventorySnapshotKey = new NamespacedKey(plugin, "gc_inventory_snapshot");
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public GameSession createSession(String moduleId, List<UUID> players, String difficulty,
                                     GameLocation origin, boolean computerOpponent, int totalPlayers) {
        return createSession(moduleId, players, difficulty, origin, computerOpponent, totalPlayers, Map.of());
    }

    @Override
    public GameSession createSession(String moduleId, List<UUID> players, String difficulty,
                                     GameLocation origin, boolean computerOpponent, int totalPlayers,
                                     Map<String, String> properties) {
        if (moduleId == null || difficulty == null || origin == null || players == null || players.isEmpty()) {
            throw new IllegalArgumentException("A game, difficulty, origin, and at least one human player are required");
        }
        List<UUID> humans = List.copyOf(players);
        Set<UUID> uniquePlayers = new HashSet<>(humans);
        if (uniquePlayers.size() != humans.size() || uniquePlayers.contains(null)) {
            throw new IllegalArgumentException("Player list must contain unique player ids");
        }

        GameModule module = plugin.moduleManager().getModule(moduleId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown or disabled game: " + moduleId));
        if (totalPlayers < module.minPlayers() || totalPlayers > module.maxPlayers()) {
            throw new IllegalArgumentException(module.descriptor().displayName() + " supports "
                    + module.minPlayers() + "–" + module.maxPlayers() + " seats");
        }
        if (totalPlayers < humans.size()
                || (computerOpponent && totalPlayers <= humans.size())
                || (!computerOpponent && totalPlayers != humans.size())) {
            throw new IllegalArgumentException("Seat count does not match the human/computer players");
        }
        if (computerOpponent && !module.supportsComputer()) {
            throw new IllegalArgumentException(module.descriptor().displayName() + " has no computer opponent");
        }
        List<String> difficulties = module.supportedDifficulties();
        if (!difficulties.isEmpty() && difficulties.stream().noneMatch(value -> value.equalsIgnoreCase(difficulty))) {
            throw new IllegalArgumentException("Supported difficulties: " + String.join(", ", difficulties));
        }
        for (UUID playerId : humans) {
            if (Bukkit.getPlayer(playerId) == null) {
                throw new IllegalArgumentException("All players must be online to start a match");
            }
            if (plugin.boardService() != null && !plugin.boardService().isClientModReady(playerId)) {
                throw new IllegalArgumentException("Install and launch the GameCraft client mod before joining a match");
            }
            if (playerToSession.containsKey(playerId)) {
                throw new IllegalStateException("A player is already in a game session");
            }
        }

        UUID sessionId = UUID.randomUUID();
        GameSessionImpl session = new GameSessionImpl(this, scheduler, sessionId, module, humans,
                totalPlayers, difficulty.toLowerCase(java.util.Locale.ROOT), origin, computerOpponent,
                properties == null ? Map.of() : properties);
        synchronized (this) {
            for (UUID playerId : humans) {
                if (playerToSession.putIfAbsent(playerId, sessionId) != null) {
                    humans.forEach(id -> playerToSession.remove(id, sessionId));
                    throw new IllegalStateException("A player is already in a game session");
                }
            }
            sessions.put(sessionId, session);
        }

        for (UUID playerId : humans) {
            scheduler.runForEntity(playerId, () -> {
                GameSession active = getPlayerSession(playerId).orElse(null);
                Player player = Bukkit.getPlayer(playerId);
                if (active == null || !active.sessionId().equals(sessionId) || player == null || !player.isOnline()) return;
                PlayerInventorySnapshot snapshot = PlayerInventorySnapshot.capture(player);
                if (playerInventories.putIfAbsent(playerId, snapshot) == null) {
                    try {
                        player.getPersistentDataContainer().set(inventorySnapshotKey, PersistentDataType.BYTE_ARRAY,
                                snapshot.serialize());
                        clearPlayerInventory(player);
                    } catch (IOException | RuntimeException exception) {
                        playerInventories.remove(playerId, snapshot);
                        plugin.getLogger().severe("Could not safely snapshot " + player.getName()
                                + "'s inventory; ending their game session setup: " + exception.getMessage());
                        active.end(null);
                    }
                }
            }, () -> { });
        }

        try {
            module.onSessionStart(session);
        } catch (RuntimeException exception) {
            session.cancel();
            logModuleFailure(moduleId, "start session", exception);
            throw new IllegalStateException("The game failed to start", exception);
        }
        return session;
    }

    @Override
    public Optional<GameSession> getSession(UUID sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    /** Associates automatically spawned furniture with a session's terminal cleanup. */
    public void removeFurnitureWhenSessionEnds(GameSession session, UUID furnitureId) {
        boolean attached;
        boolean removeIfUnused;
        synchronized (this) {
            attached = sessions.get(session.sessionId()) == session
                    && session.state() == io.github.tis199.gamecraft.api.GameSessionState.IN_PROGRESS;
            if (attached) {
                temporaryFurnitureBySession.put(session.sessionId(), furnitureId);
                sessionsByTemporaryFurniture.computeIfAbsent(furnitureId, ignored -> new HashSet<>())
                        .add(session.sessionId());
            }
            removeIfUnused = !attached && !sessionsByTemporaryFurniture.containsKey(furnitureId);
        }
        if (removeIfUnused) plugin.furniture().remove(furnitureId);
    }

    /** Removes an automatically created table when startup fails before it can be attached. */
    public void removeTemporaryFurnitureIfUnused(UUID furnitureId) {
        boolean removeIfUnused;
        synchronized (this) {
            removeIfUnused = !sessionsByTemporaryFurniture.containsKey(furnitureId);
        }
        if (removeIfUnused) plugin.furniture().remove(furnitureId);
    }

    @Override
    public Optional<GameSession> getPlayerSession(UUID playerId) {
        UUID sessionId = playerToSession.get(playerId);
        return sessionId == null ? Optional.empty() : getSession(sessionId);
    }

    @Override
    public List<GameSession> getActiveSessions() {
        return List.copyOf(sessions.values());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        restorePlayerNow(player);
        getPlayerSession(player.getUniqueId()).ifPresent(session -> session.end(null));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (playerInventories.containsKey(event.getPlayer().getUniqueId())
                || event.getPlayer().getPersistentDataContainer().has(inventorySnapshotKey, PersistentDataType.BYTE_ARRAY)) {
            scheduler.runForEntity(event.getPlayer().getUniqueId(), () -> restorePlayerNow(event.getPlayer()), () -> { });
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (inGame(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (inGame(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (!inGame(event.getPlayer())) return;
        event.setCancelled(true);
        useGameControl(event.getPlayer(), event.getItem());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (!inGame(event.getPlayer())) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
        onInteractEntity(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !inGame(player)) return;
        event.setCancelled(true);
        if (event.getClickedInventory() == player.getInventory() && event.getSlot() >= 0 && event.getSlot() < 8) {
            useGameControl(player, event.getCurrentItem());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player && inGame(player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (inGame(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && inGame(player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (inGame(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (inGame(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (inGame(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (inGame(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && inGame(player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        if (damager instanceof Player player && inGame(player)) event.setCancelled(true);
        else if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player
                && inGame(player)) event.setCancelled(true);
    }

    private boolean inGame(Player player) {
        return getPlayerSession(player.getUniqueId()).filter(session -> session.state().name().equals("IN_PROGRESS")).isPresent();
    }

    private void clearPlayerInventory(Player player) {
        player.getInventory().clear();
        player.setItemOnCursor(null);
        player.updateInventory();
    }

    /** Replaces the first eight slots with protected game commands and current action shortcuts. */
    public void updateGameHotbar(GameSession session, UUID privatePlayerId,
                                 List<io.github.tis199.gamecraft.api.MenuOption> options, String actionType) {
        for (UUID playerId : session.players()) {
            scheduler.runForEntity(playerId, () -> {
                Player player = Bukkit.getPlayer(playerId);
                if (player == null || !inGame(player)) return;
                PlayerInventory inventory = player.getInventory();
                for (int slot = 0; slot < 8; slot++) inventory.setItem(slot, null);
                inventory.setItem(0, gameControl(Material.BARRIER, "✕ Forfeit", "forfeit", actionType, ""));
                boolean draw = session.moduleId().equals("chess") || session.moduleId().equals("checkers");
                inventory.setItem(1, gameControl(draw ? Material.WHITE_BANNER : Material.PAPER,
                        draw ? "Offer draw" : "Game status", draw ? "draw" : "status", actionType, ""));
                inventory.setItem(2, gameControl(session.moduleId().equals("uno") ? Material.MAP : Material.WRITABLE_BOOK,
                        session.moduleId().equals("uno") ? "My cards" : "Game help",
                        session.moduleId().equals("uno") ? "open-hand" : "help", actionType, ""));
                if (privatePlayerId == null || privatePlayerId.equals(playerId)) {
                    List<io.github.tis199.gamecraft.api.MenuOption> actions = options.stream()
                            .filter(option -> hotbarAction(option.id()))
                            .limit(5).toList();
                    for (int index = 0; index < actions.size(); index++) {
                        var option = actions.get(index);
                        inventory.setItem(index + 3, gameControl(controlMaterial(option.id()), option.title(),
                                "action", actionType, option.id()));
                    }
                }
                player.updateInventory();
            }, () -> { });
        }
    }

    private ItemStack gameControl(Material material, String title, String control, String actionType, String choice) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(title, control.equals("forfeit") ? NamedTextColor.RED : NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.getPersistentDataContainer().set(gameControlKey, PersistentDataType.STRING, control);
        meta.getPersistentDataContainer().set(gameControlTypeKey, PersistentDataType.STRING, actionType);
        if (!choice.isEmpty()) meta.getPersistentDataContainer().set(gameControlChoiceKey, PersistentDataType.STRING, choice);
        stack.setItemMeta(meta);
        return stack;
    }

    private static boolean hotbarAction(String id) {
        return !(id.equals("status") || id.equals("board") || id.startsWith("selected:")
                || id.startsWith("square:") || id.startsWith("destination:")
                || id.startsWith("hand:") || id.startsWith("play:"));
    }

    private static Material controlMaterial(String choice) {
        if (choice.startsWith("promote:")) return Material.WHITE_WOOL;
        if (choice.startsWith("wild:")) return Material.MAGENTA_DYE;
        if (choice.equals("roll")) return Material.REDSTONE;
        if (choice.equals("draw")) return Material.PAPER;
        return Material.LIME_DYE;
    }

    private void useGameControl(Player player, ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) return;
        var data = stack.getItemMeta().getPersistentDataContainer();
        String control = data.get(gameControlKey, PersistentDataType.STRING);
        if (control == null) return;
        GameSession session = getPlayerSession(player.getUniqueId()).orElse(null);
        if (session == null) return;
        String type = data.get(gameControlTypeKey, PersistentDataType.STRING);
        if (type == null) return;
        switch (control) {
            case "forfeit" -> {
                UUID winner = session.players().stream().filter(id -> !id.equals(player.getUniqueId())).findFirst().orElse(null);
                session.end(winner);
            }
            case "draw" -> session.sendAction(new GameAction(type, player.getUniqueId(), Map.of("choice", "draw-offer")));
            case "open-hand" -> plugin.boardService().openHand(player, session.sessionId());
            case "help", "status" -> player.sendMessage(Component.text(
                    "Play on the GameCraft table with the installed client mod. Forfeit with the barrier.", NamedTextColor.YELLOW));
            case "action" -> {
                String choice = data.get(gameControlChoiceKey, PersistentDataType.STRING);
                if (choice != null && plugin.boardService().isValidChoice(session.sessionId(), player.getUniqueId(), type, choice)) {
                    session.sendAction(new GameAction(type, player.getUniqueId(), Map.of("choice", choice)));
                }
            }
            default -> { }
        }
    }

    private void restorePlayerNow(Player player) {
        PlayerInventorySnapshot snapshot = playerInventories.remove(player.getUniqueId());
        if (snapshot == null) {
            byte[] bytes = player.getPersistentDataContainer().get(inventorySnapshotKey, PersistentDataType.BYTE_ARRAY);
            if (bytes != null) {
                try {
                    snapshot = PlayerInventorySnapshot.deserialize(bytes);
                } catch (IOException | ClassNotFoundException exception) {
                    plugin.getLogger().severe("Could not restore a saved GameCraft inventory for "
                            + player.getName() + ": " + exception.getMessage());
                    return;
                }
            }
        }
        if (snapshot != null) snapshot.restore(player);
        if (snapshot != null) player.getPersistentDataContainer().remove(inventorySnapshotKey);
    }

    void removeSession(UUID sessionId) {
        GameSessionImpl session;
        UUID temporaryFurnitureToRemove = null;
        synchronized (this) {
            session = sessions.remove(sessionId);
            UUID temporaryFurniture = session == null ? null : temporaryFurnitureBySession.remove(sessionId);
            if (temporaryFurniture != null) {
                Set<UUID> owners = sessionsByTemporaryFurniture.get(temporaryFurniture);
                if (owners != null) {
                    owners.remove(sessionId);
                    if (owners.isEmpty()) {
                        sessionsByTemporaryFurniture.remove(temporaryFurniture);
                        temporaryFurnitureToRemove = temporaryFurniture;
                    }
                }
            }
        }
        if (session != null) {
            for (UUID playerId : session.players()) {
                playerToSession.remove(playerId, sessionId);
                scheduler.runForEntity(playerId, () -> {
                    Player player = Bukkit.getPlayer(playerId);
                    if (player != null) restorePlayerNow(player);
                }, () -> { });
            }
            if (temporaryFurnitureToRemove != null) plugin.furniture().remove(temporaryFurnitureToRemove);
        }
    }

    void logModuleFailure(String moduleId, String operation, RuntimeException exception) {
        plugin.getLogger().warning("Game module '" + moduleId + "' failed to " + operation + ": "
                + exception.getMessage());
    }

    public void close() {
        for (GameSessionImpl session : new ArrayList<>(sessions.values())) {
            session.cancel();
        }
        sessions.clear();
        playerToSession.clear();
        temporaryFurnitureBySession.clear();
        sessionsByTemporaryFurniture.clear();
    }

    private record PlayerInventorySnapshot(ItemStack[] contents, ItemStack[] armor,
                                           ItemStack[] extra, ItemStack cursor) implements java.io.Serializable {
        private static final long serialVersionUID = 1L;

        private static PlayerInventorySnapshot capture(Player player) {
            PlayerInventory inventory = player.getInventory();
            return new PlayerInventorySnapshot(cloneItems(inventory.getStorageContents()),
                    cloneItems(inventory.getArmorContents()), cloneItems(inventory.getExtraContents()),
                    cloneItem(player.getItemOnCursor()));
        }

        private void restore(Player player) {
            PlayerInventory inventory = player.getInventory();
            inventory.setStorageContents(cloneItems(contents));
            inventory.setArmorContents(cloneItems(armor));
            inventory.setExtraContents(cloneItems(extra));
            player.setItemOnCursor(cloneItem(cursor));
            player.updateInventory();
        }

        private byte[] serialize() throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (BukkitObjectOutputStream output = new BukkitObjectOutputStream(bytes)) {
                output.writeObject(this);
            }
            return bytes.toByteArray();
        }

        private static PlayerInventorySnapshot deserialize(byte[] bytes) throws IOException, ClassNotFoundException {
            try (BukkitObjectInputStream input = new BukkitObjectInputStream(new ByteArrayInputStream(bytes))) {
                Object value = input.readObject();
                if (value instanceof PlayerInventorySnapshot snapshot) return snapshot;
                throw new IOException("saved player data has an unexpected object type");
            }
        }

        private static ItemStack[] cloneItems(ItemStack[] source) {
            ItemStack[] copy = new ItemStack[source.length];
            for (int index = 0; index < source.length; index++) copy[index] = cloneItem(source[index]);
            return copy;
        }

        private static ItemStack cloneItem(ItemStack item) { return item == null ? null : item.clone(); }
    }
}
