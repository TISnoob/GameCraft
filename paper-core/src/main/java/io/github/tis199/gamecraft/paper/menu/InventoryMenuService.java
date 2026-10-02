package io.github.tis199.gamecraft.paper.menu;

import io.github.tis199.gamecraft.api.MenuDefinition;
import io.github.tis199.gamecraft.api.MenuOption;
import io.github.tis199.gamecraft.api.MenuService;
import net.kyori.adventure.text.Component;
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
    private final JavaPlugin plugin;

    public InventoryMenuService(JavaPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void open(UUID playerId, MenuDefinition menu, Consumer<String> selectedOption) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            return;
        }
        int count = Math.min(45, menu.options().size());
        int size = Math.max(9, Math.min(54, ((count + 8) / 9) * 9));
        MenuHolder holder = new MenuHolder(menu, selectedOption);
        Inventory inventory = Bukkit.createInventory(holder, size, Component.text(menu.title()));
        holder.inventory = inventory;

        for (int index = 0; index < count; index++) {
            MenuOption option = menu.options().get(index);
            ItemStack icon = new ItemStack(Material.PAPER);
            ItemMeta meta = icon.getItemMeta();
            meta.displayName(Component.text(option.title()));
            if (!option.description().isEmpty()) {
                meta.lore(option.description().stream().map(Component::text).toList());
            }
            icon.setItemMeta(meta);
            inventory.setItem(index, icon);
        }
        player.openInventory(inventory);
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
        if (slot < 0 || slot >= holder.menu.options().size()) {
            return;
        }
        String selected = holder.menu.options().get(slot).id();
        Player player = (Player) event.getWhoClicked();
        player.closeInventory();
        holder.selection.accept(selected);
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
        private Inventory inventory;

        private MenuHolder(MenuDefinition menu, Consumer<String> selection) {
            this.menu = menu;
            this.selection = selection;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
