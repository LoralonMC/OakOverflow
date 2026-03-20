package dev.oakheart.oakoverflow.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Event fired when an item is about to be added to a player's overflow storage.
 * This event is cancellable.
 */
public class ItemAddedToOverflowEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();
    private boolean cancelled = false;

    private final ItemStack item;
    private final int amount;
    private final String source;

    public ItemAddedToOverflowEvent(Player player, ItemStack item, int amount, String source) {
        super(player);
        this.item = item;
        this.amount = amount;
        this.source = source;
    }

    /**
     * Gets the ItemStack being added to overflow.
     *
     * @return The ItemStack
     */
    public ItemStack getItem() {
        return item;
    }

    /**
     * Gets the amount of items being added.
     *
     * @return The amount
     */
    public int getAmount() {
        return amount;
    }

    /**
     * Gets the source of the item (e.g., plugin name, command name).
     *
     * @return The source string
     */
    public String getSource() {
        return source;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @NotNull
    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    @NotNull
    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
