package dev.oakheart.oakoverflow.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * Handles GUI inventory click, drag, and close events.
 * Uses InventoryHolder pattern to identify OakOverflow GUIs.
 */
public class GUIListener implements Listener {

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }

        if (!(event.getInventory().getHolder() instanceof UnclaimedItemsGUI gui)) {
            return;
        }

        // Cancel ALL clicks when our GUI is open (prevents item movement)
        event.setCancelled(true);

        // Only process clicks in the top inventory
        if (event.getClickedInventory() != null && event.getClickedInventory().equals(event.getInventory())) {
            gui.handleClick(event.getSlot(), event.isLeftClick());
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof UnclaimedItemsGUI)) {
            return;
        }

        event.setCancelled(true);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof UnclaimedItemsGUI)) {
            return;
        }

        UnclaimedItemsGUI.untrack(event.getPlayer().getUniqueId());
    }
}
