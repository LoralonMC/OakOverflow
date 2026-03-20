package dev.oakheart.oakoverflow.integration;

import dev.oakheart.oakoverflow.OakOverflow;
import dev.oakheart.oakoverflow.api.OakOverflowAPI;
import dev.oakheart.oakoverflow.util.CountCache;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

/**
 * PlaceholderAPI expansion for OakOverflow.
 *
 * Provides placeholders for displaying unclaimed item information in:
 * - Scoreboards
 * - Tab lists
 * - Chat messages
 * - Holograms
 * - Any other PlaceholderAPI-compatible plugin
 *
 * Uses the count cache to avoid blocking the main thread. If the cache
 * doesn't have a value yet, returns a default and triggers an async fetch
 * so the next call will have the data.
 *
 * Available placeholders:
 * - %oakoverflow_unclaimed_count% - Number of unclaimed items
 * - %oakoverflow_has_items% - "true" or "false" if player has items
 * - %oakoverflow_has_items_yes_no% - "Yes" or "No" if player has items
 */
public class PlaceholderAPIExpansion extends PlaceholderExpansion {

    private final OakOverflow plugin;
    private final OakOverflowAPI api;
    private final CountCache countCache;

    public PlaceholderAPIExpansion(OakOverflow plugin, OakOverflowAPI api, CountCache countCache) {
        this.plugin = plugin;
        this.api = api;
        this.countCache = countCache;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "oakoverflow";
    }

    @Override
    public @NotNull String getAuthor() {
        return String.join(", ", plugin.getPluginMeta().getAuthors());
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true; // Keep expansion loaded even if plugin is temporarily unavailable
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    /**
     * Processes placeholder requests.
     *
     * @param player The player requesting the placeholder
     * @param params The placeholder identifier (after "oakoverflow_")
     * @return The placeholder value as a string
     */
    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) {
            return "";
        }

        // Handle different placeholder types
        return switch (params.toLowerCase()) {
            case "unclaimed_count" -> getUnclaimedCount(player);
            case "has_items" -> getHasItems(player);
            case "has_items_yes_no" -> getHasItemsYesNo(player);
            default -> null; // Placeholder not found
        };
    }

    /**
     * Gets the cached count for a player without blocking.
     * If the cache doesn't have a value, triggers an async fetch
     * and returns null so the caller can use a default.
     *
     * @param player The player
     * @return Cached count, or null if not yet available
     */
    private Integer getCachedCount(OfflinePlayer player) {
        Integer cached = countCache.get(player.getUniqueId());
        if (cached == null) {
            // Trigger async fetch to populate cache for next call - never blocks
            api.getUnclaimedCount(player.getUniqueId());
        }
        return cached;
    }

    /**
     * Gets the number of unclaimed items for a player.
     * Returns cached value or "0" if not yet cached (async fetch triggered for next call).
     *
     * @param player The player
     * @return Count as string
     */
    private String getUnclaimedCount(OfflinePlayer player) {
        Integer count = getCachedCount(player);
        return String.valueOf(count != null ? count : 0);
    }

    /**
     * Returns "true" or "false" if player has unclaimed items.
     *
     * @param player The player
     * @return "true" or "false"
     */
    private String getHasItems(OfflinePlayer player) {
        Integer count = getCachedCount(player);
        return (count != null && count > 0) ? "true" : "false";
    }

    /**
     * Returns "Yes" or "No" if player has unclaimed items.
     * User-friendly version for display.
     *
     * @param player The player
     * @return "Yes" or "No"
     */
    private String getHasItemsYesNo(OfflinePlayer player) {
        Integer count = getCachedCount(player);
        return (count != null && count > 0) ? "Yes" : "No";
    }
}
