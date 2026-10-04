package io.github.tis199.gamecraft.engine.card;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class PhysicalCardDealer {

    public static void dealCardToPlayer(Player player, int customModelData, String name) {
        ItemStack card = new ItemStack(Material.PAPER);
        ItemMeta meta = card.getItemMeta();
        if (meta != null) {
            meta.setCustomModelData(customModelData);
            meta.setDisplayName(name);
            card.setItemMeta(meta);
        }
        player.getInventory().addItem(card);
    }

    public static void clearPlayerHand(Player player) {
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (item != null && item.getType() == Material.PAPER && item.hasItemMeta() && item.getItemMeta().hasCustomModelData()) {
                player.getInventory().setItem(i, null);
            }
        }
    }
}
