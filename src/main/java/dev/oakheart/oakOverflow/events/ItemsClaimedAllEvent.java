package dev.oakheart.oakoverflow.events;

import dev.oakheart.oakoverflow.model.OverflowItem;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Event fired when a player claims all items from overflow storage.
 * This event is NOT cancellable (individual claims are handled by ItemClaimedEvent).
 */
public class ItemsClaimedAllEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final List<OverflowItem> claimedItems;

    public ItemsClaimedAllEvent(Player player, List<OverflowItem> claimedItems) {
        super(player);
        this.claimedItems = claimedItems;
    }

    /**
     * Gets the list of items that were claimed.
     *
     * @return List of OverflowItems
     */
    public List<OverflowItem> getClaimedItems() {
        return claimedItems;
    }

    /**
     * Gets the total number of items claimed.
     *
     * @return Number of items claimed
     */
    public int getTotalClaimed() {
        return claimedItems.size();
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
