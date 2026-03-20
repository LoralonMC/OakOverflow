package dev.oakheart.oakoverflow.model;

import dev.oakheart.oakoverflow.util.TimeUtil;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;
import java.util.UUID;

/**
 * Represents an item stored in the overflow storage system.
 * This is the core data model for unclaimed items.
 * All fields are final; the ItemStack is defensively cloned on construction and access.
 */
public class OverflowItem {
    private final int id;
    private final UUID playerUUID;
    private final String playerName;
    private final ItemStack item;
    private final int amount;
    private final long receivedTimestamp;
    private final String source;
    private final boolean claimed;
    private final Long claimedTimestamp;
    private final boolean corrupted;

    public OverflowItem(int id, UUID playerUUID, String playerName, ItemStack item, int amount,
                       long receivedTimestamp, String source, boolean claimed, Long claimedTimestamp) {
        this.id = id;
        this.playerUUID = playerUUID;
        this.playerName = playerName;
        this.item = item != null ? item.clone() : null;
        this.amount = amount;
        this.receivedTimestamp = receivedTimestamp;
        this.source = source;
        this.claimed = claimed;
        this.claimedTimestamp = claimedTimestamp;
        this.corrupted = false;
    }

    // Private constructor for corrupted items
    private OverflowItem(int id, UUID playerUUID, String playerName, int amount,
                         long receivedTimestamp, String source) {
        this.id = id;
        this.playerUUID = playerUUID;
        this.playerName = playerName;
        this.item = null;
        this.amount = amount;
        this.receivedTimestamp = receivedTimestamp;
        this.source = source;
        this.claimed = false;
        this.claimedTimestamp = null;
        this.corrupted = true;
    }

    /**
     * Creates a placeholder for an item that failed to deserialize.
     * The item field will be null and isCorrupted() will return true.
     */
    public static OverflowItem corrupted(int id, UUID playerUUID, String playerName, int amount,
                                          long receivedTimestamp, String source) {
        return new OverflowItem(id, playerUUID, playerName, amount, receivedTimestamp, source);
    }

    public int getId() { return id; }
    public UUID getPlayerUUID() { return playerUUID; }
    public String getPlayerName() { return playerName; }
    public ItemStack getItem() { return item != null ? item.clone() : null; }
    public int getAmount() { return amount; }
    public long getReceivedTimestamp() { return receivedTimestamp; }
    public String getSource() { return source != null ? source : "Unknown"; }
    public boolean isCorrupted() { return corrupted; }
    public boolean isClaimed() { return claimed; }
    public Long getClaimedTimestamp() { return claimedTimestamp; }

    public long getAge() {
        return System.currentTimeMillis() - receivedTimestamp;
    }

    public String getAgeString() {
        return TimeUtil.formatDuration(getAge());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        OverflowItem that = (OverflowItem) o;
        return id == that.id;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "OverflowItem{" +
                "id=" + id +
                ", player=" + playerName +
                ", item=" + (corrupted ? "CORRUPTED" : item.getType()) +
                ", amount=" + amount +
                ", source='" + source + '\'' +
                ", age=" + getAgeString() +
                ", claimed=" + claimed +
                '}';
    }
}
