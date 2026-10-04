package io.github.tis199.gamecraft.paper.menu;

import io.github.tis199.gamecraft.api.MenuDefinition;
import io.github.tis199.gamecraft.api.MenuOption;
import io.github.tis199.gamecraft.api.MenuService;
import io.github.tis199.gamecraft.api.GameScheduler;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** Java inventory renderer for the platform-neutral menu API. */
public final class InventoryMenuService implements MenuService, Listener {
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private final JavaPlugin plugin;
    private final GameScheduler scheduler;

    public InventoryMenuService(JavaPlugin plugin, GameScheduler scheduler) {
        this.plugin = plugin;
        this.scheduler = scheduler;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void open(UUID playerId, MenuDefinition menu, Consumer<String> selectedOption) {
        openPage(playerId, menu, selectedOption, 0);
    }

    private void openPage(UUID playerId, MenuDefinition menu, Consumer<String> selectedOption, int page) {
        scheduler.runForEntity(playerId, () -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) return;
            int pages = Math.max(1, (menu.options().size() + 44) / 45);
            int currentPage = Math.max(0, Math.min(page, pages - 1));
            int start = currentPage * 45;
            int count = Math.min(45, menu.options().size() - start);
            int size = pages > 1 ? 54 : Math.max(9, Math.min(54, ((count + 8) / 9) * 9));
            MenuHolder holder = new MenuHolder(menu, selectedOption, currentPage);
            Inventory inventory = Bukkit.createInventory(holder, size, MINI.deserialize(menu.title()));
            holder.inventory = inventory;

            for (int index = 0; index < count; index++) {
                MenuOption option = menu.options().get(start + index);
                ItemStack icon = optionItem(option);
                ItemMeta meta = icon.getItemMeta();
                meta.displayName(MINI.deserialize(option.title()));
                if (!option.description().isEmpty()) {
                    meta.lore(option.description().stream().map(MINI::deserialize).toList());
                }
                icon.setItemMeta(meta);
                inventory.setItem(index, icon);
            }
            if (pages > 1 && currentPage > 0) inventory.setItem(45, item(Material.ARROW, "Previous page"));
            if (pages > 1 && currentPage + 1 < pages) inventory.setItem(53, item(Material.ARROW, "Next page"));
            player.openInventory(inventory);
        }, () -> { });
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        int slot = event.getRawSlot();
        Player player = (Player) event.getWhoClicked();
        if (holder.page > 0 && slot == 45) {
            player.closeInventory();
            openPage(player.getUniqueId(), holder.menu, holder.selection, holder.page - 1);
            return;
        }
        int pages = Math.max(1, (holder.menu.options().size() + 44) / 45);
        if (pages > 1 && holder.page + 1 < pages && slot == 53) {
            player.closeInventory();
            openPage(player.getUniqueId(), holder.menu, holder.selection, holder.page + 1);
            return;
        }
        int optionIndex = holder.page * 45 + slot;
        if (slot < 0 || slot >= 45 || optionIndex >= holder.menu.options().size()) {
            return;
        }
        String selected = holder.menu.options().get(optionIndex).id();
        if (selected.equals("status") || selected.equals("board")) return;
        player.closeInventory();
        holder.selection.accept(selected);
    }

    private static ItemStack item(Material material, String title) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MINI.deserialize("<yellow>" + title + "</yellow>"));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack optionItem(MenuOption option) {
        Material material = option.id().startsWith("game:") ? Material.PAPER
                : option.id().startsWith("seat:") ? Material.PLAYER_HEAD
                : option.id().startsWith("difficulty:") ? Material.AMETHYST_SHARD
                : option.id().startsWith("mode:computer") ? Material.BLAZE_POWDER
                : option.id().startsWith("mode:room") ? Material.OAK_SIGN
                : option.id().startsWith("mode:solo") ? Material.ENCHANTED_BOOK
                : Material.MAP;
        ItemStack item = new ItemStack(material);
        if (option.id().startsWith("game:")) {
            int model = switch (option.id().substring(5)) {
                case "chess" -> 104;
                case "ludo" -> 301;
                case "chinese-checkers" -> 401;
                case "checkers" -> 201;
                case "monopoly" -> 506;
                case "uno" -> 601;
                case "solitaire" -> 701;
                case "sudoku" -> 801;
                default -> 900;
            };
            ItemMeta meta = item.getItemMeta();
            meta.setCustomModelData(model);
            item.setItemMeta(meta);
        }
        return item;
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof MenuHolder) {
            event.setCancelled(true);
        }
    }

    private static final class MenuHolder implements InventoryHolder {
        private final MenuDefinition menu;
        private final Consumer<String> selection;
        private final int page;
        private Inventory inventory;

        private MenuHolder(MenuDefinition menu, Consumer<String> selection, int page) {
            this.menu = menu;
            this.selection = selection;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
