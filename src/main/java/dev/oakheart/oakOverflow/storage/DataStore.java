package dev.oakheart.oakoverflow.storage;

import dev.oakheart.oakoverflow.model.OverflowItem;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Interface for database operations.
 * Supports both SQLite and MySQL implementations.
 */
public interface DataStore {

    boolean initialize();

    void close();

    int getSchemaVersion();

    CompletableFuture<OverflowItem> addOverflowItem(UUID playerUUID, String playerName,
                                                      ItemStack item, int amount, String source);

    CompletableFuture<List<OverflowItem>> getUnclaimedItems(UUID playerUUID);

    CompletableFuture<List<OverflowItem>> getUnclaimedItems(UUID playerUUID, int limit);

    CompletableFuture<OverflowItem> getItemById(int id);

    CompletableFuture<Integer> getUnclaimedCount(UUID playerUUID);

    CompletableFuture<Boolean> markAsClaimed(int itemId, UUID playerUUID);

    CompletableFuture<Integer> markAsClaimedBatch(UUID playerUUID, List<Integer> itemIds);

    /**
     * Marks each item as claimed individually and returns the IDs that were
     * actually marked (rows that were still unclaimed). Callers giving out items
     * must use this instead of {@link #markAsClaimedBatch} so an item whose row
     * was already claimed by a racing single-claim is not given a second time.
     */
    CompletableFuture<List<Integer>> markAsClaimedBatchReturningIds(UUID playerUUID, List<Integer> itemIds);

    /**
     * Puts rows a claim marked back into the unclaimed state, for a claim that won its
     * mark but could not hand the items over (the player left, or the plugin is
     * shutting down). Restores the original rows, so nothing is re-serialised.
     */
    CompletableFuture<Integer> unmarkClaimed(UUID playerUUID, List<Integer> itemIds);

    /**
     * {@link #unmarkClaimed} run on the calling thread. Only for a caller already on the
     * storage thread (a claim callback finishing during shutdown, when the executor no
     * longer accepts new work).
     */
    int unmarkClaimedNow(UUID playerUUID, List<Integer> itemIds);

    CompletableFuture<Integer> clearPlayerItems(UUID playerUUID);

    CompletableFuture<Integer> clearAllItems();

    CompletableFuture<DatabaseStats> getStatistics();

    CompletableFuture<Integer> deleteOlderThan(int days);

    CompletableFuture<Integer> deleteClaimedOlderThan(int days);

    CompletableFuture<Integer> getExpiringItemCount(UUID playerUUID, long olderThanTimestamp);

    CompletableFuture<Integer> purgeCorruptedItems();

    CompletableFuture<List<RawOverflowItem>> exportAllItems();

    /**
     * Imports items into this data store. <b>WARNING: This deletes all existing data first.</b>
     * Intended for database migration only — not for incremental inserts.
     *
     * @param items the raw items to import
     * @return number of items successfully imported
     */
    CompletableFuture<Integer> importItems(List<RawOverflowItem> items);

    record DatabaseStats(int totalItems, int unclaimedItems, int uniquePlayers, long oldestItemAge) {}

    record RawOverflowItem(UUID playerUUID, String playerName, String itemData, int amount,
                           long receivedTimestamp, String source, boolean claimed, Long claimedTimestamp) {}
}
