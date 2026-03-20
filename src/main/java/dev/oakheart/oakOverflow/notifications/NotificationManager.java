package dev.oakheart.oakoverflow.notifications;

import dev.oakheart.oakoverflow.OakOverflow;
import dev.oakheart.oakoverflow.api.OakOverflowAPI;
import dev.oakheart.oakoverflow.config.ConfigManager;
import dev.oakheart.oakoverflow.gui.UnclaimedItemsGUI;
import dev.oakheart.oakoverflow.message.MessageManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages player notifications for unclaimed items.
 * Handles join notifications, receive notifications, and action bar updates.
 */
public class NotificationManager implements Listener {

    private final OakOverflow plugin;
    private final OakOverflowAPI api;
    private final ConfigManager config;
    private final MessageManager messages;

    private BukkitTask actionBarTask;

    // Players known to have unclaimed items — only these receive action bar updates
    // when only-when-items is enabled, avoiding iteration of all online players
    private final Set<UUID> actionBarPlayers = ConcurrentHashMap.newKeySet();

    public NotificationManager(OakOverflow plugin, OakOverflowAPI api, ConfigManager config, MessageManager messages) {
        this.plugin = plugin;
        this.api = api;
        this.config = config;
        this.messages = messages;
    }

    /**
     * Starts the action bar task if enabled.
     * Called from main class during startup and reload.
     * <p>
     * When only-when-items is enabled (default), only iterates the tracked set of
     * players known to have unclaimed items instead of all online players.
     */
    public void startActionBarTask() {
        if (config.isActionBarEnabled()) {
            int updateInterval = config.getActionBarUpdateInterval();
            actionBarTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                if (Bukkit.getOnlinePlayers().isEmpty()) {
                    return;
                }
                if (config.isActionBarOnlyWhenItems()) {
                    for (UUID uuid : actionBarPlayers) {
                        Player player = Bukkit.getPlayer(uuid);
                        if (player != null && player.isOnline()) {
                            updatePlayerActionBar(player);
                        } else {
                            actionBarPlayers.remove(uuid);
                        }
                    }
                } else {
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        updatePlayerActionBar(player);
                    }
                }
            }, updateInterval, updateInterval);
        }
    }

    /**
     * Restarts the action bar task after a config reload.
     * Seeds the tracked set with all online permitted players so the first
     * tick can prune those with 0 items — avoids missing anyone mid-session.
     */
    public void restartActionBarTask() {
        if (actionBarTask != null) {
            actionBarTask.cancel();
            actionBarTask = null;
        }
        actionBarPlayers.clear();
        if (config.isActionBarEnabled() && config.isActionBarOnlyWhenItems()) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.hasPermission("oakoverflow.notify")) {
                    actionBarPlayers.add(player.getUniqueId());
                }
            }
        }
        startActionBarTask();
    }

    /**
     * Shuts down the notification manager.
     * Cancels all active tasks.
     */
    public void shutdown() {
        if (actionBarTask != null) {
            actionBarTask.cancel();
            actionBarTask = null;
        }
        actionBarPlayers.clear();
    }

    // ========================================
    //          Join Notifications
    // ========================================

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        if (!player.hasPermission("oakoverflow.notify")) {
            return;
        }

        boolean needsJoinNotification = config.isJoinNotificationEnabled();
        boolean needsActionBarTracking = config.isActionBarEnabled() && config.isActionBarOnlyWhenItems();

        if (!needsJoinNotification && !needsActionBarTracking) {
            return;
        }

        // Single count check serves both join notification and action bar tracking
        api.getUnclaimedCount(player).thenAccept(count -> {
            if (needsActionBarTracking && count > 0) {
                actionBarPlayers.add(player.getUniqueId());
            }

            if (needsJoinNotification && count >= config.getJoinNotificationMinItems()) {
                int delay = config.getJoinNotificationDelay();
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (player.isOnline()) {
                        sendJoinNotification(player, count);
                    }
                }, delay);
            }

            // Expiry warning check (separate from join notification)
            if (config.isExpiryWarningEnabled() && count > 0) {
                int warningDays = config.getExpiryWarningDaysBefore();
                api.getExpiringItemCount(player.getUniqueId(), warningDays).thenAccept(expiringCount -> {
                    if (expiringCount > 0) {
                        int delay = config.getJoinNotificationDelay() + 40; // 2 seconds after join notification
                        Bukkit.getScheduler().runTaskLater(plugin, () -> {
                            if (player.isOnline()) {
                                Map<String, String> placeholders = new HashMap<>();
                                placeholders.put("count", String.valueOf(expiringCount));
                                placeholders.put("days", String.valueOf(warningDays));
                                messages.send(player, "expiry-warning", placeholders);
                            }
                        }, delay);
                    }
                }).exceptionally(throwable -> {
                    plugin.getLogger().warning("Failed to check expiring items for " + player.getName() + ": " + throwable.getMessage());
                    return null;
                });
            }
        });
    }

    /**
     * Sends join notification to player. The MiniMessage template in config
     * already contains click/hover events, so no programmatic wrapping needed.
     */
    private void sendJoinNotification(Player player, int count) {
        messages.send(player, "join-notification", "count", String.valueOf(count));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        api.invalidateCache(uuid);
        actionBarPlayers.remove(uuid);
        UnclaimedItemsGUI.clearSortPreference(uuid);
    }

    // ========================================
    //          Receive Notifications
    // ========================================

    /**
     * Notifies a player that they received a new item in overflow.
     * Reads the display mode from the message's config entry.
     *
     * @param player The player
     * @param itemName Item name/type
     * @param amount Item amount
     * @param source Source of the item
     */
    public void notifyItemReceived(Player player, String itemName, int amount, String source) {
        if (!player.hasPermission("oakoverflow.notify")) {
            return;
        }

        // Player now has at least 1 unclaimed item — track for action bar
        if (config.isActionBarEnabled() && config.isActionBarOnlyWhenItems()) {
            actionBarPlayers.add(player.getUniqueId());
        }

        if (!config.isReceiveNotificationEnabled()) {
            return;
        }

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("item", itemName);
        placeholders.put("amount", String.valueOf(amount));
        placeholders.put("source", source);

        messages.send(player, "item-received", placeholders);
    }

    // ========================================
    //          Action Bar Updates
    // ========================================

    /**
     * Updates action bar for a single player.
     * Automatically untracks the player if their count drops to 0.
     */
    private void updatePlayerActionBar(Player player) {
        if (!player.hasPermission("oakoverflow.notify")) {
            actionBarPlayers.remove(player.getUniqueId());
            return;
        }

        api.getUnclaimedCount(player).thenAccept(count -> {
            if (config.isActionBarOnlyWhenItems() && count == 0) {
                actionBarPlayers.remove(player.getUniqueId());
                return;
            }

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    messages.send(player, "action-bar-unclaimed", "count", String.valueOf(count));
                }
            });
        });
    }

    /**
     * Manually triggers an action bar update for a player.
     * Useful after claiming items.
     *
     * @param player The player
     */
    public void updateActionBar(Player player) {
        if (config.isActionBarEnabled()) {
            updatePlayerActionBar(player);
        }
    }
}
