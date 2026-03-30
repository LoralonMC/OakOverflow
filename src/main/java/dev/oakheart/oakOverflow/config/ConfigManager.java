package dev.oakheart.oakoverflow.config;

import dev.oakheart.oakoverflow.OakOverflow;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages plugin configuration: loading, validation, merging defaults, and
 * type-safe access to all config values. Message resolution is handled by
 * {@link dev.oakheart.message.MessageManager}.
 */
public class ConfigManager {

    private final OakOverflow plugin;
    private final Logger logger;
    private final File configFile;

    private dev.oakheart.config.ConfigManager config;

    // Cached hot-path values
    private double interceptionRadius;
    private double ambiguityThreshold;
    private boolean debugMode;
    private int guiSize;
    private int itemsPerPage;
    private boolean cacheEnabled;
    private int cacheDuration;
    private boolean joinNotificationEnabled;
    private int joinNotificationDelay;
    private int joinNotificationMinItems;
    private boolean receiveNotificationEnabled;
    private boolean actionBarEnabled;
    private int actionBarUpdateInterval;
    private boolean actionBarOnlyWhenItems;
    private Component guiTitle;
    private Material previousPageMaterial;
    private Material nextPageMaterial;
    private Material claimAllMaterial;
    private Material viewOnlyMaterial;
    private Material fillerMaterial;
    private Material sortButtonMaterial;
    private Material capacityIndicatorMaterial;
    private boolean expiryWarningEnabled;
    private int expiryWarningDaysBefore;

    public ConfigManager(OakOverflow plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.configFile = new File(plugin.getDataFolder(), "config.yml");
    }

    /**
     * Initial load of configuration. Called once during onEnable.
     */
    public void load() {
        if (!configFile.exists()) {
            plugin.saveResource("config.yml", false);
        }
        try {
            this.config = dev.oakheart.config.ConfigManager.load(configFile.toPath());
        } catch (IOException e) {
            throw new RuntimeException("Failed to load config.yml", e);
        }
        mergeDefaults();
        validate(this.config);
        cacheValues();
    }

    /**
     * Reloads configuration from disk. Merges defaults and validates before applying.
     *
     * @return true if reload was successful
     */
    public boolean reload() {
        try {
            dev.oakheart.config.ConfigManager newConfig = dev.oakheart.config.ConfigManager.load(configFile.toPath());
            mergeDefaults(newConfig);

            if (!validate(newConfig)) {
                logger.warning("Configuration reload failed validation. Keeping previous configuration.");
                return false;
            }

            this.config = newConfig;
            cacheValues();
            logger.info("Configuration reloaded successfully.");
            return true;
        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to reload config.yml", e);
            return false;
        }
    }

    /**
     * Returns the underlying config for direct access.
     */
    public dev.oakheart.config.ConfigManager getConfig() {
        return config;
    }

    /**
     * Merges default config values from the JAR resource into the current config.
     */
    private void mergeDefaults() {
        mergeDefaults(this.config);
    }

    /**
     * Merges default config values from the JAR resource into the given config.
     * Only saves if new keys are found, avoiding reformatting.
     */
    private void mergeDefaults(dev.oakheart.config.ConfigManager target) {
        try (var stream = plugin.getResource("config.yml")) {
            if (stream != null) {
                var defaults = dev.oakheart.config.ConfigManager.fromStream(stream);
                if (target.mergeDefaults(defaults)) {
                    target.save();
                    logger.info("Merged missing config keys from defaults.");
                }
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to merge default config", e);
        }
    }

    /**
     * Validates the given configuration and logs any warnings or errors.
     *
     * @param configToValidate the configuration to validate
     * @return true if no fatal errors were found
     */
    private boolean validate(dev.oakheart.config.ConfigManager configToValidate) {
        List<String> warnings = new ArrayList<>();
        boolean hasFatalError = false;

        // Validate GUI size
        int size = configToValidate.getInt("gui.size", 54);
        if (size % 9 != 0 || size > 54 || size < 9) {
            warnings.add("gui.size must be a multiple of 9, between 9 and 54, got: " + size + ". Using default: 54");
        }

        // Validate items per page
        int ipp = configToValidate.getInt("gui.items-per-page", 45);
        int maxAllowed = (size % 9 == 0 && size >= 9 && size <= 54 ? size : 54) - 9;
        if (ipp > maxAllowed || ipp < 1) {
            warnings.add("gui.items-per-page (" + ipp + ") must be 1-" + maxAllowed + " for gui.size " + size);
        }

        // Validate database type (fatal)
        String dbType = configToValidate.getString("database.type", "SQLITE");
        try {
            DatabaseType.valueOf(dbType.toUpperCase());
        } catch (IllegalArgumentException e) {
            warnings.add("Invalid database.type: " + dbType + ". This is a fatal error.");
            hasFatalError = true;
        }

        // Validate MySQL settings if MySQL is selected
        if ("MYSQL".equalsIgnoreCase(dbType)) {
            String host = configToValidate.getString("database.mysql.host", "");
            if (host.isBlank()) {
                warnings.add("database.mysql.host is empty");
            }
            int port = configToValidate.getInt("database.mysql.port", 3306);
            if (port < 1 || port > 65535) {
                warnings.add("database.mysql.port must be between 1 and 65535, got: " + port);
            }
            String database = configToValidate.getString("database.mysql.database", "");
            if (database.isBlank()) {
                warnings.add("database.mysql.database is empty");
            }
            String sslMode = configToValidate.getString("database.mysql.ssl-mode", "DISABLED");
            if (!List.of("DISABLED", "PREFERRED", "REQUIRED", "VERIFY_CA", "VERIFY_IDENTITY").contains(sslMode.toUpperCase())) {
                warnings.add("database.mysql.ssl-mode must be one of DISABLED, PREFERRED, REQUIRED, VERIFY_CA, VERIFY_IDENTITY, got: " + sslMode);
            }
        }

        // Validate expiry warning
        int autoDeleteDays = configToValidate.getInt("limits.auto-delete-days", -1);
        int warningDays = configToValidate.getInt("notifications.expiry-warning.days-before", 3);
        if (autoDeleteDays > 0 && warningDays >= autoDeleteDays) {
            warnings.add("notifications.expiry-warning.days-before (" + warningDays + ") should be less than limits.auto-delete-days (" + autoDeleteDays + ")");
        }

        // Validate sound strings
        validateSound(configToValidate, "gui.sounds.open", warnings);
        validateSound(configToValidate, "gui.sounds.claim", warnings);
        validateSound(configToValidate, "gui.sounds.claim-all", warnings);
        validateSound(configToValidate, "gui.sounds.navigation", warnings);

        if (!warnings.isEmpty()) {
            logger.warning("=== Configuration Warnings ===");
            warnings.forEach(w -> logger.warning("  - " + w));
            logger.warning("==============================");
        }

        return !hasFatalError;
    }

    /**
     * Validates a sound string follows the namespace:path format.
     */
    private void validateSound(dev.oakheart.config.ConfigManager configToValidate, String path, List<String> warnings) {
        String sound = configToValidate.getString(path, "");
        if (!sound.isEmpty() && !sound.contains(":")) {
            warnings.add(path + " should use namespace:path format (e.g. 'minecraft:block.chest.open'), got: " + sound);
        }
    }

    /**
     * Caches frequently accessed config values for hot-path performance.
     */
    private void cacheValues() {
        interceptionRadius = config.getDouble("performance.interception-radius", 5.0);
        ambiguityThreshold = config.getDouble("performance.interception-ambiguity-threshold", 1.5);
        debugMode = config.getBoolean("advanced.debug", false);

        int size = config.getInt("gui.size", 54);
        guiSize = (size % 9 == 0 && size >= 9 && size <= 54) ? size : 54;
        int ipp = config.getInt("gui.items-per-page", 45);
        int maxAllowed = guiSize - 9;
        itemsPerPage = (ipp >= 1 && ipp <= maxAllowed) ? ipp : maxAllowed;

        cacheEnabled = config.getBoolean("advanced.cache.enabled", true);
        cacheDuration = config.getInt("advanced.cache.duration", 30);

        joinNotificationEnabled = config.getBoolean("notifications.join.enabled", true);
        joinNotificationDelay = config.getInt("notifications.join.delay", 40);
        joinNotificationMinItems = config.getInt("notifications.join.min-items", 1);
        receiveNotificationEnabled = config.getBoolean("notifications.receive.enabled", true);
        actionBarEnabled = config.getBoolean("notifications.action-bar.enabled", true);
        actionBarUpdateInterval = config.getInt("notifications.action-bar.update-interval", 60);
        actionBarOnlyWhenItems = config.getBoolean("notifications.action-bar.only-when-items", true);

        String titleStr = config.getString("gui.title", "<#FCD472>Overflow Storage");
        guiTitle = MiniMessage.miniMessage().deserialize(titleStr);

        previousPageMaterial = parseMaterial("gui.buttons.previous-page", Material.ARROW);
        nextPageMaterial = parseMaterial("gui.buttons.next-page", Material.ARROW);
        claimAllMaterial = parseMaterial("gui.buttons.claim-all", Material.EMERALD);
        viewOnlyMaterial = parseMaterial("gui.buttons.view-only", Material.BARRIER);
        fillerMaterial = parseMaterial("gui.buttons.filler", Material.GRAY_STAINED_GLASS_PANE);
        sortButtonMaterial = parseMaterial("gui.buttons.sort", Material.HOPPER);
        capacityIndicatorMaterial = parseMaterial("gui.buttons.capacity-indicator", Material.CHEST);

        expiryWarningEnabled = config.getBoolean("notifications.expiry-warning.enabled", true);
        expiryWarningDaysBefore = config.getInt("notifications.expiry-warning.days-before", 3);
    }

    private Material parseMaterial(String path, Material defaultMaterial) {
        String name = config.getString(path, defaultMaterial.name());
        Material material = Material.matchMaterial(name);
        if (material == null) {
            logger.warning("Invalid material '" + name + "' at " + path + ", using " + defaultMaterial.name());
            return defaultMaterial;
        }
        return material;
    }

    // ========================================
    //          Performance Settings
    // ========================================

    public double getInterceptionRadius() {
        return interceptionRadius;
    }

    public double getInterceptionAmbiguityThreshold() {
        return ambiguityThreshold;
    }

    // ========================================
    //          Item Limits
    // ========================================

    public int getMaxItemsPerPlayer() {
        return config.getInt("limits.max-items-per-player", -1);
    }

    public boolean hasItemLimit() {
        return getMaxItemsPerPlayer() > 0;
    }

    public int getAutoDeleteDays() {
        return config.getInt("limits.auto-delete-days", -1);
    }

    public boolean isAutoDeleteEnabled() {
        return getAutoDeleteDays() > 0;
    }

    // ========================================
    //          Notifications
    // ========================================

    public boolean isJoinNotificationEnabled() {
        return joinNotificationEnabled;
    }

    public int getJoinNotificationDelay() {
        return joinNotificationDelay;
    }

    public int getJoinNotificationMinItems() {
        return joinNotificationMinItems;
    }

    public boolean isReceiveNotificationEnabled() {
        return receiveNotificationEnabled;
    }

    public boolean isActionBarEnabled() {
        return actionBarEnabled;
    }

    public int getActionBarUpdateInterval() {
        return actionBarUpdateInterval;
    }

    public boolean isActionBarOnlyWhenItems() {
        return actionBarOnlyWhenItems;
    }

    // ========================================
    //          GUI Settings
    // ========================================

    public int getGuiSize() {
        return guiSize;
    }

    public Component getGuiTitle() {
        return guiTitle;
    }

    public int getItemsPerPage() {
        return itemsPerPage;
    }

    public boolean isGuiAutoUpdate() {
        return config.getBoolean("gui.auto-update", true);
    }

    public boolean areGuiSoundsEnabled() {
        return config.getBoolean("gui.sounds.enabled", true);
    }

    public String getOpenSound() {
        return config.getString("gui.sounds.open", "minecraft:block.chest.open");
    }

    public String getClaimSound() {
        return config.getString("gui.sounds.claim", "minecraft:entity.experience_orb.pickup");
    }

    public String getClaimAllSound() {
        return config.getString("gui.sounds.claim-all", "minecraft:ui.toast.challenge_complete");
    }

    public String getNavigationSound() {
        return config.getString("gui.sounds.navigation", "minecraft:ui.button.click");
    }

    public Material getPreviousPageMaterial() {
        return previousPageMaterial;
    }

    public Material getNextPageMaterial() {
        return nextPageMaterial;
    }

    public Material getClaimAllMaterial() {
        return claimAllMaterial;
    }

    public Material getViewOnlyMaterial() {
        return viewOnlyMaterial;
    }

    public Material getFillerMaterial() {
        return fillerMaterial;
    }

    public Material getSortButtonMaterial() {
        return sortButtonMaterial;
    }

    public Material getCapacityIndicatorMaterial() {
        return capacityIndicatorMaterial;
    }

    // ========================================
    //          Expiry Warning
    // ========================================

    public boolean isExpiryWarningEnabled() {
        return expiryWarningEnabled && isAutoDeleteEnabled();
    }

    public int getExpiryWarningDaysBefore() {
        return expiryWarningDaysBefore;
    }

    // ========================================
    //          Database
    // ========================================

    public enum DatabaseType {
        SQLITE,
        MYSQL
    }

    public DatabaseType getDatabaseType() {
        String type = config.getString("database.type", "SQLITE").toUpperCase();
        try {
            return DatabaseType.valueOf(type);
        } catch (IllegalArgumentException e) {
            return DatabaseType.SQLITE;
        }
    }

    /**
     * Updates the database type in the config file using targeted line replacement
     * via the library's ConfigManager to avoid reformatting.
     */
    public void setDatabaseType(String type) {
        config.set("database.type", type.toUpperCase());
        try {
            config.save();
        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to save database type to config file", e);
        }
    }

    public String getSqliteFilename() {
        return config.getString("database.sqlite.filename", "oakoverflow.db");
    }

    public boolean isSqliteWalMode() {
        return config.getBoolean("database.sqlite.wal-mode", true);
    }

    public String getMysqlHost() {
        return config.getString("database.mysql.host", "localhost");
    }

    public int getMysqlPort() {
        return config.getInt("database.mysql.port", 3306);
    }

    public String getMysqlDatabase() {
        return config.getString("database.mysql.database", "oakoverflow");
    }

    public String getMysqlUsername() {
        return config.getString("database.mysql.username", "root");
    }

    public String getMysqlPassword() {
        return config.getString("database.mysql.password", "password");
    }

    public String getMysqlSslMode() {
        return config.getString("database.mysql.ssl-mode", "DISABLED");
    }

    public String getMysqlProperty(String property, String defaultValue) {
        return config.getString("database.mysql.properties." + property, defaultValue);
    }

    public int getMaxConnections() {
        return config.getInt("database.pool.max-connections", 10);
    }

    public int getMinIdleConnections() {
        return config.getInt("database.pool.min-idle-connections", 2);
    }

    public int getConnectionTimeout() {
        return config.getInt("database.pool.connection-timeout", 5000);
    }

    public long getMaxLifetime() {
        return config.getLong("database.pool.max-lifetime", 1800000L);
    }

    public long getIdleTimeout() {
        return config.getLong("database.pool.idle-timeout", 600000L);
    }

    public boolean isTestOnBorrow() {
        return config.getBoolean("database.pool.test-on-borrow", true);
    }

    // ========================================
    //          Integration Settings
    // ========================================

    public boolean isPhoenixCratesEnabled() {
        return config.getBoolean("integration.phoenixcrates.enabled", false);
    }

    public boolean isPhoenixCratesInterceptRewards() {
        return config.getBoolean("integration.phoenixcrates.intercept-rewards", true);
    }

    public boolean isPlaceholderAPIEnabled() {
        return config.getBoolean("integration.placeholderapi.enabled", true);
    }

    // ========================================
    //          Advanced Settings
    // ========================================

    public boolean isDebugMode() {
        return debugMode;
    }

    public int getAsyncThreads() {
        return config.getInt("advanced.async-threads", 2);
    }

    public boolean isCacheEnabled() {
        return cacheEnabled;
    }

    public int getCacheDuration() {
        return cacheDuration;
    }

    public int getMaxQueryLimit() {
        return config.getInt("advanced.max-query-limit", 10000);
    }

    public boolean hasQueryLimit() {
        return getMaxQueryLimit() > 0;
    }

    public int getConfigVersion() {
        return config.getInt("config-version", 1);
    }
}
