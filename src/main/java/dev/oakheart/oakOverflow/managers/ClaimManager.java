package dev.oakheart.oakoverflow.managers;

import dev.oakheart.oakoverflow.OakOverflow;
import dev.oakheart.oakoverflow.api.OakOverflowAPI;
import dev.oakheart.oakoverflow.config.ConfigManager;
import dev.oakheart.oakoverflow.events.ItemClaimedEvent;
import dev.oakheart.oakoverflow.events.ItemsClaimedAllEvent;
import dev.oakheart.oakoverflow.message.MessageManager;
import dev.oakheart.oakoverflow.model.OverflowItem;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Shared claim logic used by both the GUI and the /overflow claimall command.
 * Handles event firing, batch DB marking, item giving, and feedback.
 */
public class ClaimManager {

    private final OakOverflow plugin;
    private final OakOverflowAPI api;
    private final ConfigManager config;
    private final MessageManager messages;

    public ClaimManager(OakOverflow plugin, OakOverflowAPI api, ConfigManager config, MessageManager messages) {
        this.plugin = plugin;
        this.api = api;
        this.config = config;
        this.messages = messages;
    }

    /**
     * Claims all non-corrupted items for a player. Must be called from the main thread.
     * <p>
     * Items that don't fit in the player's inventory are re-added to overflow storage.
     * The player receives a partial-claim message if some items were returned.
     * <p>
     * Flow: filter corrupted → fire individual {@link ItemClaimedEvent}s → batch mark as
     * claimed in DB → give items to player (re-overflow leftovers) → fire
     * {@link ItemsClaimedAllEvent} → send success/partial message and play sound.
     *
     * @param player The player claiming items
     * @param items The list of overflow items to attempt to claim
     * @return CompletableFuture with the list of items that were fully claimed (not re-overflowed)
     */
    public CompletableFuture<List<OverflowItem>> claimAll(Player player, List<OverflowItem> items) {
        List<OverflowItem> itemsToClaim = new ArrayList<>();
        for (OverflowItem item : items) {
            if (item.isCorrupted()) continue;
            ItemClaimedEvent event = new ItemClaimedEvent(player, item);
            Bukkit.getPluginManager().callEvent(event);
            if (!event.isCancelled()) {
                itemsToClaim.add(item);
            }
        }

        if (itemsToClaim.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }

        List<Integer> itemIds = itemsToClaim.stream()
                .map(OverflowItem::getId)
                .toList();

        CompletableFuture<List<OverflowItem>> result = new CompletableFuture<>();

        // Mark as claimed first to prevent duplication on crash.
        // Crash between mark and give = item loss (preferred over duplication).
        api.markAsClaimedBatch(player.getUniqueId(), itemIds).thenAccept(markedCount -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (markedCount <= 0) {
                    messages.getMessage("commands.database-error").ifPresent(player::sendMessage);
                    result.complete(List.of());
                    return;
                }

                // Track which items were fully claimed vs re-overflowed
                List<OverflowItem> fullyClaimed = new ArrayList<>();
                int returnedEntries = 0;

                for (OverflowItem item : itemsToClaim) {
                    ItemStack itemStack = item.getItem();
                    itemStack.setAmount(item.getAmount());

                    HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(itemStack);
                    if (leftover.isEmpty()) {
                        fullyClaimed.add(item);
                    } else {
                        returnedEntries++;
                        for (ItemStack left : leftover.values()) {
                            api.addOverflowItem(player, left, left.getAmount(), item.getSource());
                        }
                    }
                }

                ItemsClaimedAllEvent claimAllEvent = new ItemsClaimedAllEvent(player, fullyClaimed);
                Bukkit.getPluginManager().callEvent(claimAllEvent);

                if (returnedEntries > 0) {
                    Map<String, String> placeholders = new HashMap<>();
                    placeholders.put("claimed", String.valueOf(fullyClaimed.size()));
                    placeholders.put("returned", String.valueOf(returnedEntries));
                    messages.send(player, "claim-all-partial", placeholders);
                } else {
                    messages.send(player, "claim-all-success", "count", String.valueOf(fullyClaimed.size()));
                }

                if (config.areGuiSoundsEnabled()) {
                    player.playSound(player.getLocation(), config.getClaimAllSound(), 1.0f, 1.0f);
                }

                result.complete(fullyClaimed);
            });
        }).exceptionally(throwable -> {
            plugin.getLogger().log(Level.SEVERE, "Failed to batch claim items", throwable);
            Bukkit.getScheduler().runTask(plugin, () -> {
                messages.getMessage("commands.database-error").ifPresent(player::sendMessage);
                result.complete(List.of());
            });
            return null;
        });

        return result;
    }
}
