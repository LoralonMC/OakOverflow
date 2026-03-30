package dev.oakheart.oakoverflow;

import dev.oakheart.oakoverflow.api.OakOverflowAPI;
import dev.oakheart.oakoverflow.api.OakOverflowAPIImpl;
import dev.oakheart.oakoverflow.commands.AdminCommand;
import dev.oakheart.oakoverflow.commands.UnclaimedCommand;
import dev.oakheart.oakoverflow.config.ConfigManager;
import dev.oakheart.oakoverflow.listeners.ItemInterceptListener;
import dev.oakheart.oakoverflow.listeners.PhoenixCratesListener;
import dev.oakheart.oakoverflow.managers.ClaimManager;
import dev.oakheart.message.MessageManager;
import dev.oakheart.oakoverflow.notifications.NotificationManager;
import dev.oakheart.oakoverflow.storage.DataStore;
import dev.oakheart.oakoverflow.storage.MySQLDatabase;
import dev.oakheart.oakoverflow.storage.SQLiteDatabase;
import dev.oakheart.oakoverflow.gui.GUIListener;
import dev.oakheart.oakoverflow.gui.UnclaimedItemsGUI;
import dev.oakheart.util.DebugLogger;
import org.bstats.bukkit.Metrics;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.logging.Level;

/**
 * OakOverflow - Smart Inventory Overflow Storage System
 */
public final class OakOverflow extends JavaPlugin {

    private DataStore dataStore;
    private ConfigManager configManager;
    private MessageManager messageManager;
    private OakOverflowAPIImpl api;
    private ClaimManager claimManager;
    private ItemInterceptListener itemInterceptListener;
    private NotificationManager notificationManager;
    private DebugLogger debugLogger;

    private BukkitTask autoCleanupTask;
    private BukkitTask cacheCleanupTask;

    @Override
    public void onEnable() {
        try {
            checkDependencies();
            initializeComponents();
            registerListeners();
            registerCommands();
            initializeMetrics();
            registerPlaceholders();
            startScheduledTasks();

            getLogger().info("OakOverflow v" + getPluginMeta().getVersion() + " has been enabled!");
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to enable OakOverflow", e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        UnclaimedItemsGUI.closeAll();
        if (autoCleanupTask != null) {
            autoCleanupTask.cancel();
        }
        if (cacheCleanupTask != null) {
            cacheCleanupTask.cancel();
        }
        if (itemInterceptListener != null) {
            itemInterceptListener.shutdown();
        }
        if (notificationManager != null) {
            notificationManager.shutdown();
        }
        if (dataStore != null) {
            dataStore.close();
        }
        getLogger().info("OakOverflow has been disabled!");
    }

    private void checkDependencies() {
        Plugin placeholderAPI = getServer().getPluginManager().getPlugin("PlaceholderAPI");
        if (placeholderAPI != null) {
            getLogger().info("PlaceholderAPI detected.");
        }

        Plugin phoenixCrates = getServer().getPluginManager().getPlugin("PhoenixCratesPro");
        if (phoenixCrates != null) {
            getLogger().info("PhoenixCratesPro detected.");
        }
    }

    private void initializeComponents() {
        // Configuration
        configManager = new ConfigManager(this);
        configManager.load();

        // Debug logger
        debugLogger = new DebugLogger(getLogger(), configManager::isDebugMode);

        // Messages
        messageManager = new MessageManager(this, getLogger());
        messageManager.load();

        // Database
        initializeDatabase();

        // API
        api = new OakOverflowAPIImpl(dataStore, configManager);

        // Claim manager
        claimManager = new ClaimManager(this, api, configManager, messageManager);

        // Notifications
        notificationManager = new NotificationManager(this, api, configManager, messageManager);
    }

    private void initializeDatabase() {
        ConfigManager.DatabaseType dbType = configManager.getDatabaseType();

        switch (dbType) {
            case SQLITE -> dataStore = new SQLiteDatabase(getDataFolder(), getLogger(), configManager);
            case MYSQL -> dataStore = new MySQLDatabase(configManager, getLogger());
        }

        if (!dataStore.initialize()) {
            throw new RuntimeException("Failed to initialize " + dbType + " database");
        }

        getLogger().info("Database initialized (" + dbType + ")");
    }

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(new GUIListener(), this);

        itemInterceptListener = new ItemInterceptListener(this, api, configManager, notificationManager, messageManager);
        getServer().getPluginManager().registerEvents(itemInterceptListener, this);

        // Register NotificationManager as listener (join events)
        getServer().getPluginManager().registerEvents(notificationManager, this);

        registerPhoenixCratesIntegration();
    }

    private void registerPhoenixCratesIntegration() {
        Plugin phoenixCratesPlugin = getServer().getPluginManager().getPlugin("PhoenixCratesPro");
        if (phoenixCratesPlugin == null || !phoenixCratesPlugin.isEnabled()) {
            return;
        }

        if (!configManager.isPhoenixCratesEnabled()) {
            getLogger().info("PhoenixCratesPro detected but integration disabled in config.");
            return;
        }

        try {
            if (configManager.isPhoenixCratesInterceptRewards()) {
                PhoenixCratesListener phoenixListener = new PhoenixCratesListener(this, api, configManager, notificationManager);
                getServer().getPluginManager().registerEvents(phoenixListener, this);
            }

            getLogger().info("PhoenixCratesPro integration enabled.");
        } catch (Exception e) {
            getLogger().warning("Failed to enable PhoenixCratesPro integration: " + e.getMessage());
        }
    }

    private void registerCommands() {
        new UnclaimedCommand(this, api, configManager, messageManager, claimManager).register();
        new AdminCommand(this, api, configManager, messageManager).register();
    }

    private void initializeMetrics() {
        new Metrics(this, 27843);
    }

    private void registerPlaceholders() {
        Plugin placeholderAPIPlugin = getServer().getPluginManager().getPlugin("PlaceholderAPI");
        if (placeholderAPIPlugin != null && placeholderAPIPlugin.isEnabled() && configManager.isPlaceholderAPIEnabled()) {
            try {
                new dev.oakheart.oakoverflow.integration.PlaceholderAPIExpansion(this, api, api.getCountCache()).register();
                getLogger().info("PlaceholderAPI expansion registered.");
            } catch (Exception e) {
                getLogger().warning("Failed to register PlaceholderAPI expansion: " + e.getMessage());
            }
        }
    }

    private void startScheduledTasks() {
        startAutoCleanupTask();
        startCacheCleanupTask();
        notificationManager.startActionBarTask();
    }

    private void startAutoCleanupTask() {
        int autoDeleteDays = configManager.getAutoDeleteDays();

        if (autoDeleteDays <= 0) {
            return;
        }

        getLogger().info("Auto-cleanup enabled: Items older than " + autoDeleteDays + " days will be deleted daily.");

        long delay = 1200L; // 1 minute
        long period = 24 * 60 * 60 * 20L; // 24 hours

        autoCleanupTask = getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            api.deleteOlderThan(autoDeleteDays).thenAccept(deleted -> {
                if (deleted > 0) {
                    getLogger().info("[AUTO-CLEANUP] Deleted " + deleted + " old unclaimed items.");
                    api.clearCache();
                }
            }).exceptionally(throwable -> {
                getLogger().warning("[AUTO-CLEANUP] Failed to run cleanup: " + throwable.getMessage());
                return null;
            });

            api.deleteClaimedOlderThan(autoDeleteDays).thenAccept(purged -> {
                if (purged > 0) {
                    getLogger().info("[AUTO-CLEANUP] Purged " + purged + " old claimed items.");
                }
            }).exceptionally(throwable -> {
                getLogger().warning("[AUTO-CLEANUP] Failed to purge claimed items: " + throwable.getMessage());
                return null;
            });
        }, delay, period);
    }

    private void startCacheCleanupTask() {
        if (!configManager.isCacheEnabled()) {
            return;
        }

        long period = 5 * 60 * 20L; // 5 minutes

        cacheCleanupTask = getServer().getScheduler().runTaskTimer(this, () -> {
            int removed = api.getCountCache().cleanupExpired();
            if (removed > 0) {
                getLogger().fine("[CACHE-CLEANUP] Removed " + removed + " expired cache entries.");
            }
        }, period, period);
    }

    public OakOverflowAPI getAPI() {
        return api;
    }

    /**
     * Returns the API implementation. Internal use only — for migration and admin operations
     * that need implementation-specific methods.
     */
    public OakOverflowAPIImpl getAPIImpl() {
        return api;
    }

    /**
     * Swaps the active data store to a new one. Used during database migration.
     * Updates the API's data store reference and clears cache.
     */
    public void swapDataStore(DataStore newDataStore) {
        this.dataStore = newDataStore;
        api.setDataStore(newDataStore);
    }

    public ClaimManager getClaimManager() {
        return claimManager;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public MessageManager getMessageManager() {
        return messageManager;
    }

    public NotificationManager getNotificationManager() {
        return notificationManager;
    }

    /**
     * Logs a debug message at INFO level if debug mode is enabled.
     *
     * @param message the debug message to log
     */
    public void debug(String message) {
        if (debugLogger != null) {
            debugLogger.log(message);
        }
    }

    public boolean reloadPluginConfig() {
        try {
            UnclaimedItemsGUI.closeAll();
            boolean success = configManager.reload();
            if (success) {
                messageManager.reload();
                api.clearCache();
                api.getCountCache().refreshConfig();

                // Restart configurable scheduled tasks
                if (autoCleanupTask != null) {
                    autoCleanupTask.cancel();
                    autoCleanupTask = null;
                }
                startAutoCleanupTask();

                if (cacheCleanupTask != null) {
                    cacheCleanupTask.cancel();
                    cacheCleanupTask = null;
                }
                startCacheCleanupTask();

                notificationManager.restartActionBarTask();
            }
            return success;
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to reload configuration", e);
            return false;
        }
    }
}
