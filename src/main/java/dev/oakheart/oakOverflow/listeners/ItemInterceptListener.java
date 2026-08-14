package dev.oakheart.oakoverflow.listeners;

import dev.oakheart.oakoverflow.OakOverflow;
import dev.oakheart.oakoverflow.api.OakOverflowAPI;
import dev.oakheart.oakoverflow.events.ItemAddedToOverflowEvent;
import dev.oakheart.message.MessageManager;
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
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;

import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Listens for item gives from plugins/commands and intercepts them.
 * Implements the overflow storage system.
 */
public class ItemInterceptListener implements Listener {

    /**
     * Scoreboard tag placed on items this listener drops on the ground itself.
     * Without it the interceptor would pick its own drop straight back up, and a
     * player at their overflow limit with a full inventory would loop forever.
     */
    public static final String IGNORE_TAG = "oakoverflow_ignore";

    /** Source label for items rescued from a drop another plugin cancelled. */
    private static final String SOURCE_BLOCKED_DROP = "Blocked Drop";

    private final OakOverflow plugin;
    private final OakOverflowAPI api;
    private final ConfigManager config;
    private final NotificationManager notificationManager;
    private final MessageManager messages;

    public ItemInterceptListener(OakOverflow plugin, OakOverflowAPI api,
                                  ConfigManager config, NotificationManager notificationManager,
                                  MessageManager messages) {
        this.plugin = plugin;
        this.api = api;
        this.config = config;
        this.notificationManager = notificationManager;
        this.messages = messages;
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

        // Skip items tagged by other plugins as "don't intercept", and our own
        // ground drops (see IGNORE_TAG)
        if (itemEntity.getScoreboardTags().stream()
                .anyMatch(tag -> tag.startsWith("oaktools_") || tag.equals(IGNORE_TAG))) {
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

    // ========================================
    //      Blocked Drop Rescue
    // ========================================

    /**
     * Rescues items the server destroys when another plugin cancels a drop.
     *
     * <p>When a container closes with items still in it (a merchant's payment slots,
     * a crafting grid, an anvil) and the player's inventory is full, vanilla falls
     * back to {@code player.drop(...)}. If a protection plugin cancels the resulting
     * {@link PlayerDropItemEvent}, CraftBukkit hands the stack back via
     * {@code Inventory#addItem} and <b>silently discards whatever doesn't fit</b>.
     * That is how villager-trade change and crafting-grid contents vanish inside a
     * claim with item drops disabled.
     *
     * <p>Running at MONITOR means the inventory is still in its pre-fallback state,
     * so the portion that won't fit is exactly the portion about to be destroyed.
     * Only that remainder is taken into overflow; everything that fits still reaches
     * the player through CraftBukkit's own fallback, so nothing is duplicated.
     *
     * <p>A player-initiated drop (Q) can never reach this branch: the slot the item
     * just left is free, so the whole stack always fits and the rescue no-ops.
     *
     * <p>{@code oakoverflow.bypass} is deliberately <b>not</b> honoured here. Bypass
     * means "let my items drop normally instead of going to overflow", and on this
     * path they cannot drop at all, so honouring it would mean deleting them.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (!event.isCancelled() || !config.isDropRescueEnabled()) {
            return;
        }

        ItemStack stack = event.getItemDrop().getItemStack();
        if (stack.getType().isAir()) {
            return;
        }

        Player player = event.getPlayer();
        int destroyed = stack.getAmount() - InventoryHelper.calculateMaxFit(player, stack);
        if (destroyed <= 0) {
            return; // CraftBukkit's fallback returns the whole stack, nothing is lost
        }

        sendToOverflow(player, stack, destroyed, SOURCE_BLOCKED_DROP);

        plugin.debug("Rescued blocked drop: " + ItemNameUtil.getItemDisplayName(stack) +
                " x" + destroyed + " for " + player.getName());
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
                    dropIgnored(player, itemCopy);
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
                    dropIgnored(player, itemCopy);
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

    /**
     * Drops an item on the ground for a player, tagged so {@link #onItemSpawn} skips it.
     * <p>
     * Without the tag the interceptor sees its own drop land next to a player whose
     * inventory is still full, sends it back to overflow, hits the same limit, drops
     * it again. An endless loop that never terminates while the player stands there.
     */
    private void dropIgnored(Player player, ItemStack item) {
        player.getWorld().dropItemNaturally(player.getLocation(), item,
                dropped -> dropped.addScoreboardTag(IGNORE_TAG));
    }
}
