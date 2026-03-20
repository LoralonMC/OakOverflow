package dev.oakheart.oakoverflow.events;

import dev.oakheart.oakoverflow.model.OverflowItem;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Event fired when a player claims an item from overflow storage.
 * This event is cancellable.
 */
public class ItemClaimedEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();
    private boolean cancelled = false;

    private final OverflowItem overflowItem;

    public ItemClaimedEvent(Player player, OverflowItem overflowItem) {
        super(player);
        this.overflowItem = overflowItem;
    }

    /**
     * Gets the OverflowItem being claimed.
     *
     * @return The OverflowItem
     */
    public OverflowItem getOverflowItem() {
        return overflowItem;
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
