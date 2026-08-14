package dev.oakheart.oakoverflow.api;

import dev.oakheart.oakoverflow.config.ConfigManager;
import dev.oakheart.oakoverflow.model.OverflowItem;
import dev.oakheart.oakoverflow.storage.DataStore;
import dev.oakheart.oakoverflow.util.CountCache;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Implementation of the OakOverflow API.
 */
public class OakOverflowAPIImpl implements OakOverflowAPI {

    /** Stack multiples above which a single add is worth flagging as suspicious. */
    private static final int SPLIT_WARN_THRESHOLD = 100;

    private DataStore dataStore;
    private final ConfigManager configManager;
    private final CountCache countCache;
    private final Logger logger;

    public OakOverflowAPIImpl(DataStore dataStore, ConfigManager configManager, Logger logger) {
        this.dataStore = dataStore;
        this.configManager = configManager;
        this.logger = logger;
        this.countCache = new CountCache(configManager);
    }

    // ========================================
    //          Item Management
    // ========================================

    @Override
    public CompletableFuture<OverflowItem> addOverflowItem(UUID playerUUID, String playerName,
                                                             ItemStack item, int amount, String source) {
        // Insert-only, deliberately no merging into existing rows. The old
        // read-then-write merge raced three ways: a concurrent claim silently
        // swallowed the merged amount (updateItemAmount guards on claimed = 0 and
        // the failure was ignored), two concurrent adds overwrote each other's
        // totals, and an open GUI held the pre-merge amount which claimItem then
        // gave out while marking the whole row claimed. One row per add is
        // race-free; stack-style presentation is a GUI concern, not storage, and
        // lives in UnclaimedItemsGUI#rebuildEntries.
        //
        // Rows are capped at one stack on the way in. The GUI packs whole rows into
        // slots, so a row bigger than a stack is the one thing it can't lay out
        // properly: it has to render capped and explain itself in lore. Splitting
        // here means every row can share a slot with its neighbours, and costs
        // nothing, since storage is one-row-per-receipt regardless.
        int maxStack = Math.max(1, item.getMaxStackSize());
        if (amount <= maxStack) {
            return insertRow(playerUUID, playerName, item, amount, source);
        }

        if (amount / maxStack > SPLIT_WARN_THRESHOLD) {
            logger.warning("Splitting an unusually large overflow add for " + playerName + ": "
                    + amount + "x " + item.getType() + " becomes " + ((amount + maxStack - 1) / maxStack)
                    + " rows (source: " + source + ")");
        }

        CompletableFuture<OverflowItem> firstRow = null;
        int remaining = amount;
        while (remaining > 0) {
            int slice = Math.min(remaining, maxStack);
            CompletableFuture<OverflowItem> inserted = insertRow(playerUUID, playerName, item, slice, source);
            if (firstRow == null) {
                firstRow = inserted;
            }
            remaining -= slice;
        }
        return firstRow;
    }

    /**
     * Writes a single row, sized to the amount it actually holds.
     */
    private CompletableFuture<OverflowItem> insertRow(UUID playerUUID, String playerName,
                                                      ItemStack item, int amount, String source) {
        ItemStack row = item.clone();
        row.setAmount(amount);
        return dataStore.addOverflowItem(playerUUID, playerName, row, amount, source)
                .thenApply(overflowItem -> {
                    countCache.invalidate(playerUUID);
                    return overflowItem;
                });
    }

    @Override
    public CompletableFuture<OverflowItem> addOverflowItem(Player player, ItemStack item,
                                                             int amount, String source) {
        return addOverflowItem(player.getUniqueId(), player.getName(), item, amount, source);
    }

    @Override
    public CompletableFuture<List<OverflowItem>> getUnclaimedItems(UUID playerUUID) {
        if (!configManager.hasQueryLimit()) {
            return dataStore.getUnclaimedItems(playerUUID, -1);
        }

        // A cap truncates a newest-first query, so it hides the player's OLDEST items.
        // Never let that happen quietly.
        int limit = configManager.getMaxQueryLimit();
        return dataStore.getUnclaimedItems(playerUUID, limit).thenApply(items -> {
            if (items.size() >= limit) {
                logger.warning("Overflow query for " + playerUUID + " hit advanced.max-query-limit ("
                        + limit + "). Their older items are not visible in /overflow or claimall. "
                        + "Raise or remove the limit, or use limits.auto-delete-days.");
            }
            return items;
        });
    }

    @Override
    public CompletableFuture<List<OverflowItem>> getUnclaimedItems(Player player) {
        return getUnclaimedItems(player.getUniqueId());
    }

    @Override
    public CompletableFuture<Integer> getUnclaimedCount(UUID playerUUID) {
        Integer cachedCount = countCache.get(playerUUID);
        if (cachedCount != null) {
            return CompletableFuture.completedFuture(cachedCount);
        }

        return dataStore.getUnclaimedCount(playerUUID)
                .thenApply(count -> {
                    countCache.put(playerUUID, count);
                    return count;
                });
    }

    @Override
    public CompletableFuture<Integer> getUnclaimedCount(Player player) {
        return getUnclaimedCount(player.getUniqueId());
    }

    @Override
    public CompletableFuture<OverflowItem> getItemById(int itemId) {
        return dataStore.getItemById(itemId);
    }

    @Override
    public CompletableFuture<Boolean> markAsClaimed(int itemId, UUID playerUUID) {
        return dataStore.markAsClaimed(itemId, playerUUID).thenApply(success -> {
            if (success) {
                countCache.invalidate(playerUUID);
            }
            return success;
        });
    }

    @Override
    public CompletableFuture<Boolean> markAsClaimed(int itemId) {
        return dataStore.getItemById(itemId).thenCompose(item -> {
            if (item == null) {
                return CompletableFuture.completedFuture(false);
            }
            return markAsClaimed(itemId, item.getPlayerUUID());
        });
    }

    @Override
    public CompletableFuture<Integer> markAsClaimedBatch(UUID playerUUID, List<Integer> itemIds) {
        return dataStore.markAsClaimedBatch(playerUUID, itemIds).thenApply(count -> {
            if (count > 0) {
                countCache.invalidate(playerUUID);
            }
            return count;
        });
    }

    @Override
    public CompletableFuture<List<Integer>> markAsClaimedBatchReturningIds(UUID playerUUID, List<Integer> itemIds) {
        return dataStore.markAsClaimedBatchReturningIds(playerUUID, itemIds).thenApply(marked -> {
            if (!marked.isEmpty()) {
                countCache.invalidate(playerUUID);
            }
            return marked;
        });
    }

    @Override
    public CompletableFuture<Integer> clearPlayerItems(UUID playerUUID) {
        return dataStore.clearPlayerItems(playerUUID)
                .thenApply(deleted -> {
                    countCache.invalidate(playerUUID);
                    return deleted;
                });
    }

    @Override
    public CompletableFuture<Integer> clearAllItems() {
        return dataStore.clearAllItems()
                .thenApply(deleted -> {
                    countCache.clear();
                    return deleted;
                });
    }

    // ========================================
    //          Player Settings
    // ========================================

    @Override
    public boolean hasBypass(Player player) {
        // Opt-in only: isPermissionSet() ensures OPs don't auto-bypass
        return player.isPermissionSet("oakoverflow.bypass") && player.hasPermission("oakoverflow.bypass");
    }

    @Override
    public CompletableFuture<Boolean> hasReachedLimit(Player player) {
        if (!configManager.hasItemLimit()) {
            return CompletableFuture.completedFuture(false);
        }

        if (player.isPermissionSet("oakoverflow.bypass.limits") && player.hasPermission("oakoverflow.bypass.limits")) {
            return CompletableFuture.completedFuture(false);
        }

        int maxItems = configManager.getMaxItemsPerPlayer();
        return getUnclaimedCount(player).thenApply(count -> count >= maxItems);
    }

    // ========================================
    //          Statistics
    // ========================================

    @Override
    public CompletableFuture<DataStore.DatabaseStats> getStatistics() {
        return dataStore.getStatistics();
    }

    @Override
    public int getSchemaVersion() {
        return dataStore.getSchemaVersion();
    }

    @Override
    public CompletableFuture<Integer> deleteOlderThan(int days) {
        return dataStore.deleteOlderThan(days);
    }

    @Override
    public CompletableFuture<Integer> deleteClaimedOlderThan(int days) {
        return dataStore.deleteClaimedOlderThan(days);
    }

    @Override
    public CompletableFuture<Integer> getExpiringItemCount(UUID playerUUID, int warningDaysBefore) {
        if (!configManager.isAutoDeleteEnabled() || !configManager.isExpiryWarningEnabled()) {
            return CompletableFuture.completedFuture(0);
        }

        int autoDeleteDays = configManager.getAutoDeleteDays();
        long threshold = System.currentTimeMillis() - ((long)(autoDeleteDays - warningDaysBefore) * 24 * 60 * 60 * 1000);
        return dataStore.getExpiringItemCount(playerUUID, threshold);
    }

    @Override
    public CompletableFuture<Integer> purgeCorruptedItems() {
        return dataStore.purgeCorruptedItems().thenApply(count -> {
            if (count > 0) {
                countCache.clear();
            }
            return count;
        });
    }

    /**
     * Returns the current DataStore instance for migration purposes.
     */
    public DataStore getDataStore() {
        return dataStore;
    }

    /**
     * Swaps the data store and clears cache. Used during database migration.
     */
    public void setDataStore(DataStore newDataStore) {
        this.dataStore = newDataStore;
        countCache.clear();
    }

    // ========================================
    //          Cache Management
    // ========================================

    /**
     * Returns the count cache for internal use (not part of the public API interface).
     */
    public CountCache getCountCache() {
        return countCache;
    }

    public void clearCache() {
        countCache.clear();
    }

    @Override
    public void invalidateCache(UUID playerUUID) {
        countCache.invalidate(playerUUID);
    }

}
