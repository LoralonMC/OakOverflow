package dev.oakheart.oakoverflow.util;

import dev.oakheart.oakoverflow.config.ConfigManager;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe cache for player unclaimed item counts.
 * Reduces database load for frequently accessed counts (PlaceholderAPI, action bars, etc.)
 */
public class CountCache {

    private final ConfigManager config;
    private final Map<UUID, CacheEntry> cache;
    private volatile long cacheDurationMs;

    public CountCache(ConfigManager config) {
        this.config = config;
        this.cache = new ConcurrentHashMap<>();
        this.cacheDurationMs = config.getCacheDuration() * 1000L;
    }

    /**
     * Refreshes the cached duration from config.
     * Called after a config reload.
     */
    public void refreshConfig() {
        this.cacheDurationMs = config.getCacheDuration() * 1000L;
    }

    public Integer get(UUID playerUUID) {
        if (!config.isCacheEnabled()) {
            return null;
        }

        CacheEntry entry = cache.get(playerUUID);
        if (entry == null) {
            return null;
        }

        if (System.currentTimeMillis() - entry.timestamp > cacheDurationMs) {
            cache.remove(playerUUID);
            return null;
        }

        return entry.count;
    }

    public void put(UUID playerUUID, int count) {
        if (!config.isCacheEnabled()) {
            return;
        }

        cache.put(playerUUID, new CacheEntry(count, System.currentTimeMillis()));
    }

    public void invalidate(UUID playerUUID) {
        cache.remove(playerUUID);
    }

    public void clear() {
        cache.clear();
    }

    public int size() {
        return cache.size();
    }

    public int cleanupExpired() {
        if (!config.isCacheEnabled()) {
            return 0;
        }

        long now = System.currentTimeMillis();
        int initialSize = cache.size();
        cache.entrySet().removeIf(entry ->
            now - entry.getValue().timestamp > cacheDurationMs
        );

        return initialSize - cache.size();
    }

    private record CacheEntry(int count, long timestamp) {}
}
