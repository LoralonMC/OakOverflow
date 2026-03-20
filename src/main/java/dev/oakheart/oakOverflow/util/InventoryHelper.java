package dev.oakheart.oakoverflow.util;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;

/**
 * Utility class for inventory operations and space checking.
 */
public class InventoryHelper {

    private InventoryHelper() {}

    /**
     * Checks if a player has space in their inventory for an item.
     *
     * @param player The player
     * @param item The item to check
     * @param amount The amount of items
     * @return true if player has space
     */
    public static boolean hasSpace(Player player, ItemStack item, int amount) {
        if (player == null || item == null || amount <= 0) {
            return false;
        }

        Inventory inventory = player.getInventory();
        int remaining = amount;

        // First, check existing stacks of the same item
        for (ItemStack invItem : inventory.getStorageContents()) {
            if (invItem == null || invItem.getType().isAir()) {
                continue;
            }

            // Check if items are similar (same type, metadata, etc.)
            if (invItem.isSimilar(item)) {
                int maxStack = invItem.getMaxStackSize();
                int currentAmount = invItem.getAmount();
                int availableSpace = maxStack - currentAmount;

                if (availableSpace > 0) {
                    remaining -= availableSpace;
                    if (remaining <= 0) {
                        return true;
                    }
                }
            }
        }

        // Then check empty slots
        int emptySlots = countEmptySlots(inventory);
        int maxStack = item.getMaxStackSize();
        int slotsNeeded = (int) Math.ceil((double) remaining / maxStack);

        return emptySlots >= slotsNeeded;
    }

    /**
     * Counts the number of empty slots in an inventory.
     *
     * @param inventory The inventory to check
     * @return Number of empty slots
     */
    public static int countEmptySlots(Inventory inventory) {
        int count = 0;
        for (ItemStack item : inventory.getStorageContents()) {
            if (item == null || item.getType().isAir()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Counts the number of available slots for a specific item type.
     * Takes into account existing stacks that can be filled.
     *
     * @param inventory The inventory
     * @param item The item type to check for
     * @return Number of available slots (including partial stacks)
     */
    public static int countAvailableSlots(Inventory inventory, ItemStack item) {
        int availableSpace = 0;
        int maxStack = item.getMaxStackSize();

        for (ItemStack invItem : inventory.getStorageContents()) {
            if (invItem == null || invItem.getType().isAir()) {
                // Empty slot = full stack space
                availableSpace += maxStack;
            } else if (invItem.isSimilar(item)) {
                // Existing stack = remaining space in stack
                availableSpace += (maxStack - invItem.getAmount());
            }
        }

        return availableSpace;
    }

    /**
     * Attempts to add an item to a player's inventory.
     * Returns any leftover items that couldn't fit.
     *
     * @param player The player
     * @param item The item to add
     * @param amount The amount
     * @return Number of items that couldn't fit (0 if all fit)
     */
    public static int addItem(Player player, ItemStack item, int amount) {
        ItemStack toAdd = item.clone();
        toAdd.setAmount(amount);

        HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(toAdd);

        if (leftover.isEmpty()) {
            return 0;
        }

        // Calculate leftover amount
        int leftoverAmount = 0;
        for (ItemStack leftoverItem : leftover.values()) {
            leftoverAmount += leftoverItem.getAmount();
        }

        return leftoverAmount;
    }

    /**
     * Checks if an inventory is completely full.
     *
     * @param inventory The inventory
     * @return true if no empty slots
     */
    public static boolean isFull(Inventory inventory) {
        return countEmptySlots(inventory) == 0;
    }

    /**
     * Calculates how many items can fit in the inventory.
     *
     * @param player The player
     * @param item The item type
     * @return Maximum number of items that can fit
     */
    public static int calculateMaxFit(Player player, ItemStack item) {
        return countAvailableSlots(player.getInventory(), item);
    }

    /**
     * Checks if adding an item would cause overflow.
     *
     * @param player The player
     * @param item The item
     * @param amount The amount
     * @return true if there would be overflow
     */
    public static boolean wouldOverflow(Player player, ItemStack item, int amount) {
        return !hasSpace(player, item, amount);
    }

    /**
     * Result of splitting an item amount between inventory and overflow.
     *
     * @param fits Number of items that fit in the inventory
     * @param overflow Number of items that overflow
     */
    public record SplitResult(int fits, int overflow) {}

    /**
     * Splits an item amount into what fits and what overflows.
     *
     * @param player The player
     * @param item The item
     * @param amount The total amount
     * @return SplitResult with fits and overflow amounts
     */
    public static SplitResult splitAmount(Player player, ItemStack item, int amount) {
        int maxFit = calculateMaxFit(player, item);
        int fits = Math.min(amount, maxFit);
        int overflow = amount - fits;

        return new SplitResult(fits, overflow);
    }
}
