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

/**
 * Implementation of the OakOverflow API.
 */
public class OakOverflowAPIImpl implements OakOverflowAPI {

    private DataStore dataStore;
    private final ConfigManager configManager;
    private final CountCache countCache;

    public OakOverflowAPIImpl(DataStore dataStore, ConfigManager configManager) {
        this.dataStore = dataStore;
        this.configManager = configManager;
        this.countCache = new CountCache(configManager);
    }

    // ========================================
    //          Item Management
    // ========================================

    @Override
    public CompletableFuture<OverflowItem> addOverflowItem(UUID playerUUID, String playerName,
                                                             ItemStack item, int amount, String source) {
        // Try to merge with existing unclaimed items of the same type, respecting max stack size
        return dataStore.getUnclaimedItems(playerUUID).thenCompose(existingItems -> {
            int remaining = amount;
            int maxStack = item.getMaxStackSize();
            CompletableFuture<OverflowItem> lastResult = null;

            for (OverflowItem existing : existingItems) {
                if (remaining <= 0) break;
                if (existing.isCorrupted() || !existing.getItem().isSimilar(item)) continue;
                if (existing.getAmount() >= maxStack) continue; // Already full

                int space = maxStack - existing.getAmount();
                int toAdd = Math.min(remaining, space);
                int newAmount = existing.getAmount() + toAdd;
                remaining -= toAdd;

                final int finalNewAmount = newAmount;
                lastResult = dataStore.updateItemAmount(existing.getId(), newAmount)
                        .thenApply(success -> {
                            countCache.invalidate(playerUUID);
                            if (success) {
                                return new OverflowItem(existing.getId(), playerUUID, playerName,
                                        item, finalNewAmount, existing.getReceivedTimestamp(),
                                        existing.getSource(), false, null);
                            }
                            return existing;
                        });
            }

            // Insert new entry for any remainder that didn't fit
            if (remaining > 0) {
                final int insertAmount = remaining;
                CompletableFuture<OverflowItem> insertFuture =
                        dataStore.addOverflowItem(playerUUID, playerName, item, insertAmount, source)
                                .thenApply(overflowItem -> {
                                    countCache.invalidate(playerUUID);
                                    return overflowItem;
                                });
                lastResult = insertFuture;
            }

            return lastResult != null ? lastResult
                    : CompletableFuture.completedFuture(null);
        });
    }

    @Override
    public CompletableFuture<OverflowItem> addOverflowItem(Player player, ItemStack item,
                                                             int amount, String source) {
        return addOverflowItem(player.getUniqueId(), player.getName(), item, amount, source);
    }

    @Override
    public CompletableFuture<List<OverflowItem>> getUnclaimedItems(UUID playerUUID) {
        int limit = configManager.hasQueryLimit() ? configManager.getMaxQueryLimit() : -1;
        return dataStore.getUnclaimedItems(playerUUID, limit);
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
