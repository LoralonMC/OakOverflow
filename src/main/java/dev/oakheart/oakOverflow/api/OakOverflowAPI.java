package dev.oakheart.oakoverflow.api;

import dev.oakheart.oakoverflow.model.OverflowItem;
import dev.oakheart.oakoverflow.storage.DataStore;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Public API for OakOverflow plugin.
 * Other plugins can use this API to interact with the overflow storage system.
 *
 * <p>To get an instance of this API:</p>
 * <pre>{@code
 * Plugin plugin = Bukkit.getPluginManager().getPlugin("OakOverflow");
 * if (plugin instanceof OakOverflow) {
 *     OakOverflowAPI api = ((OakOverflow) plugin).getAPI();
 * }
 * }</pre>
 */
public interface OakOverflowAPI {

    // ========================================
    //          Item Management
    // ========================================

    CompletableFuture<OverflowItem> addOverflowItem(UUID playerUUID, String playerName,
                                                      ItemStack item, int amount, String source);

    CompletableFuture<OverflowItem> addOverflowItem(Player player, ItemStack item,
                                                      int amount, String source);

    CompletableFuture<List<OverflowItem>> getUnclaimedItems(UUID playerUUID);

    CompletableFuture<List<OverflowItem>> getUnclaimedItems(Player player);

    CompletableFuture<Integer> getUnclaimedCount(UUID playerUUID);

    CompletableFuture<Integer> getUnclaimedCount(Player player);

    CompletableFuture<OverflowItem> getItemById(int itemId);

    CompletableFuture<Boolean> markAsClaimed(int itemId, UUID playerUUID);

    CompletableFuture<Boolean> markAsClaimed(int itemId);

    CompletableFuture<Integer> markAsClaimedBatch(UUID playerUUID, List<Integer> itemIds);

    /**
     * Marks each item as claimed individually and returns the IDs that were
     * actually marked. Use this when the caller hands out items afterwards:
     * the count-only batch variant cannot say which rows were already claimed.
     */
    CompletableFuture<List<Integer>> markAsClaimedBatchReturningIds(UUID playerUUID, List<Integer> itemIds);

    CompletableFuture<Integer> clearPlayerItems(UUID playerUUID);

    CompletableFuture<Integer> clearAllItems();

    // ========================================
    //          Player Settings
    // ========================================

    boolean hasBypass(Player player);

    CompletableFuture<Boolean> hasReachedLimit(Player player);

    // ========================================
    //          Statistics
    // ========================================

    CompletableFuture<DataStore.DatabaseStats> getStatistics();

    CompletableFuture<Integer> deleteOlderThan(int days);

    CompletableFuture<Integer> deleteClaimedOlderThan(int days);

    CompletableFuture<Integer> getExpiringItemCount(UUID playerUUID, int warningDaysBefore);

    CompletableFuture<Integer> purgeCorruptedItems();

    int getSchemaVersion();

    // ========================================
    //          Cache Management
    // ========================================

    void invalidateCache(UUID playerUUID);

}
