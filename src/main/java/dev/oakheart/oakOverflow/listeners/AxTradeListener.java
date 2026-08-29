package dev.oakheart.oakoverflow.listeners;

import com.artillexstudios.axtrade.api.events.AxTradeAbortEvent;
import com.artillexstudios.axtrade.trade.Trade;
import com.artillexstudios.axtrade.trade.TradeGui;
import com.artillexstudios.axtrade.trade.TradePlayer;
import dev.oakheart.message.MessageManager;
import dev.oakheart.oakoverflow.OakOverflow;
import dev.oakheart.oakoverflow.api.OakOverflowAPI;
import dev.oakheart.oakoverflow.events.ItemAddedToOverflowEvent;
import dev.oakheart.oakoverflow.model.OverflowItem;
import dev.oakheart.oakoverflow.notifications.NotificationManager;
import dev.oakheart.oakoverflow.util.ItemNameUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Rescues the items a player had staged in an AxTrade window when the trade is
 * cancelled by that player dying.
 *
 * <p><b>The bug this works around.</b> AxTrade registers no death listener. Dying
 * force-closes the trade window, which makes AxTrade abort the trade and hand each
 * side's offer back with
 * {@code addOrDrop(player.getInventory(), items, player.getLocation())} and no
 * liveness check. For the player who just died, that inventory was emptied a moment
 * earlier by the death drop, so the add succeeds, the ground-drop fallback never
 * fires, and the server discards that inventory when it builds the respawned player.
 * The offer is destroyed with no drop and no trace. The surviving partner is
 * unaffected, which is why this only acts on a dead player.</p>
 *
 * <p><b>Why this cannot duplicate items.</b> {@link AxTradeAbortEvent} is fired at the
 * top of {@code Trade#abort}, before {@code Trade#end} and before the hand-back runs,
 * and AxTrade reads the offer back out of the trade window's own inventory at that
 * later point. Emptying those slots here therefore turns AxTrade's hand-back into a
 * no-op and leaves this plugin holding the only copy. Nothing is assumed about
 * whether the void would actually have happened, so a {@code keepInventory} death or
 * a future AxTrade fix cannot produce a second copy.</p>
 *
 * <p>If the trade window cannot be read the rescue does nothing at all, which leaves
 * AxTrade's own behaviour in place rather than risking a partial claim.</p>
 */
public class AxTradeListener implements Listener {

    private static final String SOURCE = "AxTrade";

    /** {@code TradeGui.gui}, the storage GUI backing the trade window. Protected, so reflected. */
    private static final Field GUI_FIELD = findField("gui");

    /** {@code TradeGui.slots}, the slot indices holding this side's offer. Protected, so reflected. */
    private static final Field SLOTS_FIELD = findField("slots");

    private final OakOverflow plugin;
    private final OakOverflowAPI api;
    private final MessageManager messages;
    private final NotificationManager notificationManager;

    public AxTradeListener(OakOverflow plugin, OakOverflowAPI api, MessageManager messages,
                           NotificationManager notificationManager) {
        this.plugin = plugin;
        this.api = api;
        this.messages = messages;
        this.notificationManager = notificationManager;
    }

    private static Field findField(String name) {
        try {
            Field field = TradeGui.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Whether the trade window can be read on the installed AxTrade build. False means
     * AxTrade changed internally and the rescue must stay switched off.
     *
     * <p>Only call this once AxTrade is known to be present: touching this class at all
     * loads AxTrade types.</p>
     */
    public static boolean isSupported() {
        return GUI_FIELD != null && SLOTS_FIELD != null;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTradeAbort(AxTradeAbortEvent event) {
        Trade trade = event.getTrade();
        if (trade == null) {
            return;
        }

        rescue(trade.getPlayer1());
        rescue(trade.getPlayer2());
    }

    /**
     * Moves one side's offer into overflow, but only if that side died mid-trade.
     */
    private void rescue(TradePlayer tradePlayer) {
        if (tradePlayer == null) {
            return;
        }

        Player player = tradePlayer.getPlayer();
        // A living player gets their offer back from AxTrade correctly, so leave it alone.
        // isDead() is true only between the death and the respawn, which is exactly the
        // window in which the hand-back is destroyed.
        if (player == null || !player.isDead()) {
            return;
        }

        TradeGui gui = tradePlayer.getTradeGui();
        if (gui == null) {
            return;
        }

        List<ItemStack> offer = takeOffer(gui, player);
        if (offer.isEmpty()) {
            return;
        }

        // Their body, and the rest of what they were carrying, is here. Anything the
        // rescue cannot store ends up alongside it rather than at a respawn point they
        // may already have walked away from.
        Location deathLocation = player.getLocation().clone();

        for (ItemStack item : offer) {
            store(player, item, deathLocation);
        }

        messages.send(player, "trade-rescued");
        plugin.getLogger().info("Rescued " + offer.size() + " stack(s) of " + player.getName()
                + "'s trade offer into overflow (they died mid-trade).");
    }

    /**
     * Empties this side's offer slots and returns what was in them.
     *
     * <p>Taking the items out of the window is what makes the rescue dupe-proof: AxTrade
     * reads these same slots after this event to hand the offer back, so once they are
     * empty its hand-back has nothing to give and this plugin owns the only copy.</p>
     */
    private List<ItemStack> takeOffer(TradeGui gui, Player player) {
        if (!isSupported()) {
            return List.of();
        }

        // AxTrade only aborts from the main thread (its ticker uses the sync timer, and the
        // other abort paths are Bukkit events), so this should never trip. If it ever does,
        // leave the window alone rather than edit an inventory off-thread.
        if (!Bukkit.isPrimaryThread()) {
            plugin.getLogger().warning("AxTrade aborted a trade off the main thread; skipped the "
                    + "rescue for " + player.getName() + " rather than edit their trade window there.");
            return List.of();
        }

        // Held outside the try so a failure partway through still returns the slots that
        // were already emptied. Those items exist nowhere else and must not be dropped
        // on the floor of an exception handler.
        List<ItemStack> taken = new ArrayList<>();

        try {
            if (!(GUI_FIELD.get(gui) instanceof InventoryHolder holder)
                    || !(SLOTS_FIELD.get(gui) instanceof List<?> slots)) {
                return taken;
            }

            Inventory inventory = holder.getInventory();

            for (Object rawSlot : slots) {
                if (!(rawSlot instanceof Integer slot) || slot < 0 || slot >= inventory.getSize()) {
                    continue;
                }

                ItemStack item = inventory.getItem(slot);
                if (item == null || item.getType().isAir()) {
                    continue;
                }

                // Clear first: if setItem throws, the clone is not yet on the list and the
                // item is still AxTrade's to hand back. Adding first could hand the same
                // stack to both.
                inventory.setItem(slot, null);
                taken.add(item.clone());
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Whatever is still in the window stays AxTrade's to hand back.
            plugin.getLogger().log(Level.WARNING, "Could not fully read AxTrade's trade window for "
                    + player.getName() + "; rescued " + taken.size()
                    + " stack(s) and left the rest to AxTrade.", e);
        }

        return taken;
    }

    /**
     * Stores one rescued stack in overflow, dropping it at {@code fallbackLocation} if it
     * cannot be stored. The item exists nowhere else by this point, so every branch has to
     * end with the player either holding it or able to pick it up.
     */
    private void store(Player player, ItemStack item, Location fallbackLocation) {
        int amount = item.getAmount();

        api.hasReachedLimit(player).thenCompose(reachedLimit -> {
            if (reachedLimit) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    drop(fallbackLocation, item);
                    messages.send(player, "limit-reached");
                    plugin.getLogger().warning(player.getName() + " is at the overflow item limit; "
                            + "dropped a rescued trade item at their death location instead.");
                });
                return CompletableFuture.completedFuture((OverflowItem) null);
            }

            CompletableFuture<OverflowItem> result = new CompletableFuture<>();
            Bukkit.getScheduler().runTask(plugin, () -> {
                ItemAddedToOverflowEvent event = new ItemAddedToOverflowEvent(player, item, amount, SOURCE);
                Bukkit.getPluginManager().callEvent(event);

                if (event.isCancelled()) {
                    drop(fallbackLocation, item);
                    result.complete(null);
                    return;
                }

                api.addOverflowItem(player, item, amount, SOURCE)
                        .thenAccept(result::complete)
                        .exceptionally(throwable -> {
                            result.completeExceptionally(throwable);
                            return null;
                        });
            });
            return result;
        }).thenAccept(stored -> {
            if (stored == null) {
                return;
            }

            Bukkit.getScheduler().runTask(plugin, () -> {
                String itemName = ItemNameUtil.getItemDisplayName(item);
                notificationManager.notifyItemReceived(player, itemName, amount, SOURCE);
                plugin.debug("Rescued from a cancelled trade: " + amount + "x " + itemName
                        + " for " + player.getName());
            });
        }).exceptionally(throwable -> {
            Bukkit.getScheduler().runTask(plugin, () -> drop(fallbackLocation, item));
            plugin.getLogger().log(Level.SEVERE, "Failed to store a rescued trade item for "
                    + player.getName() + "; dropped it at their death location instead.", throwable);
            return null;
        });
    }

    /**
     * Tagged so {@link ItemInterceptListener} leaves the drop alone, matching how that
     * listener drops items it cannot store.
     */
    private void drop(Location location, ItemStack item) {
        if (location.getWorld() == null) {
            plugin.getLogger().severe("Lost a rescued trade item: its death location has no world.");
            return;
        }

        location.getWorld().dropItemNaturally(location, item,
                dropped -> dropped.addScoreboardTag(ItemInterceptListener.IGNORE_TAG));
    }
}
