package dev.oakheart.oakoverflow.listeners;

import dev.oakheart.oakoverflow.OakOverflow;
import dev.oakheart.oakoverflow.api.OakOverflowAPI;
import dev.oakheart.oakoverflow.events.ItemAddedToOverflowEvent;
import dev.oakheart.oakoverflow.message.MessageManager;
import dev.oakheart.oakoverflow.model.OverflowItem;
import dev.oakheart.oakoverflow.notifications.NotificationManager;
import dev.oakheart.oakoverflow.config.ConfigManager;
import dev.oakheart.oakoverflow.util.InventoryHelper;
import dev.oakheart.oakoverflow.util.ItemNameUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Listens for item gives from plugins/commands and intercepts them.
 * Implements the overflow storage system.
 */
public class ItemInterceptListener implements Listener {

    private final OakOverflow plugin;
    private final OakOverflowAPI api;
    private final ConfigManager config;
    private final NotificationManager notificationManager;
    private final MessageManager messages;

    // Track processed item entities to avoid duplicate handling
    private final Map<UUID, Long> processedItems;
    private final BukkitTask cleanupTask;

    public ItemInterceptListener(OakOverflow plugin, OakOverflowAPI api,
                                  ConfigManager config, NotificationManager notificationManager,
                                  MessageManager messages) {
        this.plugin = plugin;
        this.api = api;
        this.config = config;
        this.notificationManager = notificationManager;
        this.messages = messages;
        this.processedItems = new ConcurrentHashMap<>();

        // Clean up old processed items every 10 seconds
        cleanupTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long now = System.currentTimeMillis();
            processedItems.entrySet().removeIf(entry -> now - entry.getValue() > 5000);
        }, 200L, 200L);
    }

    /**
     * Shuts down the listener, cancelling scheduled tasks.
     */
    public void shutdown() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
        }
    }


    // ========================================
    //      Item Spawn Interception
    // ========================================

    /**
     * Universal item spawn interceptor - works with ANY plugin automatically.
     * Intercepts items spawning near players based on spawn reason and context.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        Item itemEntity = event.getEntity();
        ItemStack item = itemEntity.getItemStack();

        // Get spawn reason
        SpawnReason spawnReason = itemEntity.getEntitySpawnReason();

        // FILTER OUT natural gameplay - these should NEVER be intercepted
        if (shouldIgnoreSpawnReason(spawnReason)) {
            return;
        }

        // Skip items tagged by other plugins as "don't intercept"
        if (itemEntity.getScoreboardTags().stream().anyMatch(tag -> tag.startsWith("oaktools_"))) {
            return;
        }

        // Check if item was dropped by a player (Q key) - NEVER intercept
        if (itemEntity.getOwner() != null || itemEntity.getThrower() != null) {
            return;
        }

        // Find nearest player within configured radius using spatial index
        double maxRadius = config.getInterceptionRadius();
        Player nearestPlayer = null;
        double nearestDistance = Double.MAX_VALUE;
        double secondNearestDistance = Double.MAX_VALUE;

        for (Player player : itemEntity.getLocation().getNearbyPlayers(maxRadius)) {
            double distance = player.getLocation().distance(itemEntity.getLocation());
            if (distance <= nearestDistance) {
                secondNearestDistance = nearestDistance;
                nearestDistance = distance;
                nearestPlayer = player;
            } else if (distance < secondNearestDistance) {
                secondNearestDistance = distance;
            }
        }

        // No player nearby
        if (nearestPlayer == null) {
            plugin.debug("No player within radius " + maxRadius);
            return;
        }

        // Ambiguity check: if another player is almost as close, skip interception
        // to avoid attributing the item to the wrong player
        double ambiguityThreshold = config.getInterceptionAmbiguityThreshold();
        if (ambiguityThreshold > 0 && secondNearestDistance < Double.MAX_VALUE
                && (secondNearestDistance - nearestDistance) < ambiguityThreshold) {
            plugin.debug("Skipped ambiguous item interception: two players within " +
                    ambiguityThreshold + " blocks of each other");
            return;
        }

        // Check if player has bypass permission
        if (api.hasBypass(nearestPlayer)) {
            return;
        }

        // Atomically check and mark as processed
        UUID itemUUID = itemEntity.getUniqueId();
        if (processedItems.putIfAbsent(itemUUID, System.currentTimeMillis()) != null) {
            return; // Already processed
        }

        // Determine source based on spawn reason
        String source = getSourceFromSpawnReason(spawnReason);

        // OVERFLOW mode: Only intercept if inventory would overflow
        if (InventoryHelper.wouldOverflow(nearestPlayer, item, item.getAmount())) {
            event.setCancelled(true);
            handleAddedItem(nearestPlayer, item, source);

            plugin.debug("Intercepted: " + ItemNameUtil.getItemDisplayName(item) +
                    " x" + item.getAmount() + " for " + nearestPlayer.getName() +
                    " (reason: " + spawnReason + ")");
        }
    }

    /**
     * Determines if a spawn reason should be completely ignored (natural gameplay).
     * Conservative approach: ONLY allow CUSTOM and COMMAND, ignore everything else.
     */
    private boolean shouldIgnoreSpawnReason(SpawnReason reason) {
        // ONLY intercept these specific spawn reasons from plugins/commands
        if (reason == SpawnReason.CUSTOM || reason == SpawnReason.COMMAND) {
            return false; // Don't ignore - allow interception
        }

        // Ignore EVERYTHING else (natural gameplay, block drops, player drops, etc.)
        return true;
    }

    /**
     * Gets a user-friendly source description from spawn reason.
     */
    private String getSourceFromSpawnReason(SpawnReason reason) {
        return switch (reason) {
            case COMMAND -> "Command";
            case CUSTOM -> "Plugin";
            default -> "Unknown";
        };
    }

    // ========================================
    //      Item Handling Logic
    // ========================================

    /**
     * Handles an item that spawned from a command.
     * Splits between inventory and overflow based on available space.
     *
     * @param player The player
     * @param item The item that was added
     * @param source The source of the item
     */
    private void handleAddedItem(Player player, ItemStack item, String source) {
        int amount = item.getAmount();

        // Split between inventory and overflow (called only when inventory would overflow)
        InventoryHelper.SplitResult split = InventoryHelper.splitAmount(player, item, amount);
        int fitsAmount = split.fits();
        int overflowAmount = split.overflow();

        if (fitsAmount > 0) {
            // Some fits in inventory — check for leftover in case inventory changed
            int leftover = InventoryHelper.addItem(player, item, fitsAmount);
            overflowAmount += leftover;
        }

        if (overflowAmount > 0) {
            // Rest goes to overflow
            sendToOverflow(player, item, overflowAmount, source);
        }
    }

    /**
     * Sends an item to overflow storage.
     * If the player has reached their item limit, the item is dropped naturally
     * instead of being silently discarded.
     *
     * @param player The player
     * @param item The item
     * @param amount The amount
     * @param source The source
     */
    private void sendToOverflow(Player player, ItemStack item, int amount, String source) {
        ItemStack itemCopy = item.clone();
        itemCopy.setAmount(amount);

        api.hasReachedLimit(player).thenCompose(reachedLimit -> {
            if (reachedLimit) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    // Drop the item naturally so it isn't lost
                    player.getWorld().dropItemNaturally(player.getLocation(), itemCopy);
                    messages.send(player, "limit-reached");
                    plugin.getLogger().warning("Player " + player.getName() + " has reached overflow item limit — item dropped naturally");
                });
                return CompletableFuture.completedFuture((OverflowItem) null);
            }

            // Fire event on main thread, then add to DB
            CompletableFuture<OverflowItem> result = new CompletableFuture<>();
            Bukkit.getScheduler().runTask(plugin, () -> {
                ItemAddedToOverflowEvent event = new ItemAddedToOverflowEvent(player, itemCopy, amount, source);
                Bukkit.getPluginManager().callEvent(event);

                if (event.isCancelled()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), itemCopy);
                    result.complete(null);
                    return;
                }

                api.addOverflowItem(player, itemCopy, amount, source)
                        .thenAccept(result::complete)
                        .exceptionally(t -> {
                            result.completeExceptionally(t);
                            return null;
                        });
            });
            return result;
        }).thenAccept(overflowItem -> {
            if (overflowItem == null) return;

            Bukkit.getScheduler().runTask(plugin, () -> {
                String itemName = ItemNameUtil.getItemDisplayName(itemCopy);
                notificationManager.notifyItemReceived(player, itemName, amount, source);

                plugin.debug("Added to overflow: " + amount + "x " + itemName +
                        " for " + player.getName() + " (source: " + source + ")");
            });
        }).exceptionally(throwable -> {
            plugin.getLogger().log(Level.SEVERE, "Failed to add overflow item", throwable);
            return null;
        });
    }
}
