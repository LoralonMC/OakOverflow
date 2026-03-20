package dev.oakheart.oakoverflow.listeners;

import com.phoenixplugins.phoenixcrates.api.crate.events.CrateRewardPlayerEvent;
import com.phoenixplugins.phoenixcrates.api.internal.Item;
import com.phoenixplugins.phoenixcrates.api.reward.BaseReward;
import dev.oakheart.oakoverflow.OakOverflow;
import dev.oakheart.oakoverflow.api.OakOverflowAPI;
import dev.oakheart.oakoverflow.config.ConfigManager;
import dev.oakheart.oakoverflow.notifications.NotificationManager;
import dev.oakheart.oakoverflow.util.InventoryHelper;
import dev.oakheart.oakoverflow.util.ItemNameUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.logging.Level;

/**
 * Integrates with PhoenixCratesPro to intercept crate rewards
 * and redirect them to OakOverflow when inventory is full.
 *
 * <p><b>Known limitation:</b> PhoenixCratesPro's {@code CrateRewardPlayerEvent} is not
 * cancellable. Items will exist in both PhoenixCrates' internal claim system and OakOverflow
 * simultaneously. Disable PhoenixCrates' built-in {@code /crate claim} command in its own
 * config to prevent duplication.</p>
 */
public class PhoenixCratesListener implements Listener {

    private final OakOverflow plugin;
    private final OakOverflowAPI api;
    private final ConfigManager config;
    private final NotificationManager notificationManager;

    public PhoenixCratesListener(OakOverflow plugin, OakOverflowAPI api, ConfigManager config,
                                  NotificationManager notificationManager) {
        this.plugin = plugin;
        this.api = api;
        this.config = config;
        this.notificationManager = notificationManager;
    }

    /**
     * Intercepts PhoenixCratesPro reward events.
     *
     * Note: This event is NOT cancellable, so items will still go to PhoenixCrates' claim system
     * if inventory is full. We intercept them here to ALSO add to OakOverflow, and then block
     * access to /crate claim via command interception.
     *
     * Priority HIGHEST ensures we process before most other plugins.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCrateReward(CrateRewardPlayerEvent event) {
        Player player = event.getPlayer();
        BaseReward reward = event.getReward();

        // Check if player has bypass permission
        if (api.hasBypass(player)) {
            return; // Let items go normally
        }

        // Get items from reward
        List<? extends Item> winItems = reward.getWinItems();
        if (winItems == null || winItems.isEmpty()) {
            return; // No items to process
        }

        // Process each item
        for (Item phoenixItem : winItems) {
            try {
                // Convert PhoenixCrates Item to Bukkit ItemStack
                ItemStack itemStack = phoenixItem.build();
                if (itemStack == null || itemStack.getType().isAir()) {
                    continue;
                }

                // Get the amount (PhoenixCrates Item may have custom amount)
                int amount = phoenixItem.getAmount();
                if (amount > 0) {
                    itemStack.setAmount(amount);
                }

                // Calculate how many items will overflow (PhoenixCrates gives fitting items itself)
                int overflowAmount = amount - InventoryHelper.calculateMaxFit(player, itemStack);
                if (overflowAmount <= 0) {
                    continue;
                }

                {
                    final ItemStack overflowCopy = itemStack.clone();
                    overflowCopy.setAmount(overflowAmount);

                    api.addOverflowItem(player, overflowCopy, overflowAmount, "PhoenixCratesPro")
                        .thenAccept(overflowItem -> {
                            if (overflowItem != null) {
                                String itemName = ItemNameUtil.getItemDisplayName(overflowCopy);
                                org.bukkit.Bukkit.getScheduler().runTask(plugin, () ->
                                        notificationManager.notifyItemReceived(player, itemName, overflowAmount, "PhoenixCratesPro"));
                                plugin.getLogger().log(Level.FINE,
                                    "Intercepted PhoenixCrates reward for " + player.getName() +
                                    ": " + itemName + " x" + overflowAmount);
                            }
                        })
                        .exceptionally(throwable -> {
                            plugin.getLogger().log(Level.WARNING,
                                "Failed to add PhoenixCrates reward to overflow for " + player.getName(),
                                throwable);
                            return null;
                        });
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING,
                    "Error processing PhoenixCrates reward item for " + player.getName(), e);
            }
        }

        // Note: Items will still go to PhoenixCrates' /crate claim due to non-cancellable event
        // We handle this by intercepting the /crate claim command and redirecting users
    }
}
