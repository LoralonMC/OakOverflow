package dev.oakheart.oakoverflow.managers;

import dev.oakheart.oakoverflow.OakOverflow;
import dev.oakheart.oakoverflow.api.OakOverflowAPI;
import dev.oakheart.oakoverflow.config.ConfigManager;
import dev.oakheart.oakoverflow.events.ItemClaimedEvent;
import dev.oakheart.oakoverflow.events.ItemsClaimedAllEvent;
import dev.oakheart.message.MessageManager;
import dev.oakheart.oakoverflow.model.OverflowItem;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Shared claim logic used by the GUI and the /overflow claimall command.
 * Handles fit selection, event firing, batch DB marking, item giving, and feedback.
 */
public class ClaimManager {

    /** Slot count of a player's storage inventory (hotbar + main), used for fit simulation. */
    private static final int STORAGE_SLOTS = 36;

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
     * Part of a storage row, as presented in one GUI slot.
     *
     * <p>The GUI packs rows into stack-sized slots, so a slot can hold only part of a
     * row: three 40-item rows lay out as 64 + 56, and the middle row straddles both
     * slots. Claiming is therefore expressed in slices rather than whole rows.
     *
     * @param row    the storage row this came from
     * @param amount how much of that row this slice represents
     */
    public record RowSlice(OverflowItem row, int amount) {}

    /**
     * Outcome of a claim.
     *
     * @param claimed       rows that were marked claimed and at least partly handed over
     * @param claimedAmount item count actually given to the player
     * @param leftBehind    item count that stayed in overflow because the inventory filled
     */
    public record ClaimResult(List<OverflowItem> claimed, int claimedAmount, int leftBehind) {
        public boolean isPartial() {
            return leftBehind > 0;
        }

        public boolean isEmpty() {
            return claimedAmount <= 0;
        }
    }

    /**
     * Wraps whole rows as full-size slices, for callers that don't work in GUI slots.
     */
    public static List<RowSlice> wholeRows(List<OverflowItem> rows) {
        List<RowSlice> slices = new ArrayList<>(rows.size());
        for (OverflowItem row : rows) {
            slices.add(new RowSlice(row, row.getAmount()));
        }
        return slices;
    }

    /**
     * Claims as much of the given slices as the player's inventory can hold, without
     * sending feedback. Callers own the messaging so a single-slot click and a
     * claim-all can phrase their results differently.
     *
     * <p>Slices that don't fit are <b>left untouched in overflow</b> rather than claimed
     * and re-inserted. The old behaviour marked every row claimed, tried to give them
     * all, then wrote the rejects back as new rows: claiming with a nearly-full
     * inventory churned the whole table and reset every leftover item's age.
     *
     * <h2>Why this can't duplicate items</h2>
     * A row is only ever given from after this call wins its atomic {@code claimed = 0}
     * guarded mark, and a row marks exactly once. Everything the winner does not hand
     * over, whether it was outside the claimed slices or simply didn't fit, is written
     * back as a fresh row. So each row either stays untouched or is consumed once and
     * accounted for in full. A second click on another slice of the same row loses the
     * mark and gives nothing; its items are already accounted for by the winner's
     * write-back, and reappear when the view reloads.
     *
     * <p>Must be called from the main thread.
     *
     * @param player the player claiming
     * @param slices candidate slices, in the order they should be claimed
     * @return the claim outcome
     */
    public CompletableFuture<ClaimResult> claim(Player player, List<RowSlice> slices) {
        List<RowSlice> candidates = new ArrayList<>();
        int candidateAmount = 0;
        for (RowSlice slice : slices) {
            if (slice.row().isCorrupted() || slice.amount() <= 0) continue;
            candidates.add(slice);
            candidateAmount += slice.amount();
        }

        if (candidates.isEmpty()) {
            return CompletableFuture.completedFuture(new ClaimResult(List.of(), 0, 0));
        }

        // Fit selection runs before the events: it stops as soon as the inventory is
        // full, so an /overflow claimall against thousands of rows walks a couple of
        // dozen of them rather than firing an ItemClaimedEvent for every row on the
        // main thread just to discard most of the results.
        List<RowSlice> selected = new ArrayList<>();
        int selectedAmount = 0;
        Set<Integer> eventFired = new HashSet<>();
        Set<Integer> eventCancelled = new HashSet<>();

        for (RowSlice slice : selectFitting(player, candidates)) {
            int rowId = slice.row().getId();
            // One event per row, however many slots it is spread across
            if (eventFired.add(rowId)) {
                ItemClaimedEvent event = new ItemClaimedEvent(player, slice.row());
                Bukkit.getPluginManager().callEvent(event);
                if (event.isCancelled()) {
                    eventCancelled.add(rowId);
                }
            }
            if (eventCancelled.contains(rowId)) continue;
            selected.add(slice);
            selectedAmount += slice.amount();
        }

        int leftBehind = candidateAmount - selectedAmount;

        if (selected.isEmpty()) {
            return CompletableFuture.completedFuture(new ClaimResult(List.of(), 0, leftBehind));
        }

        // Collapse slices back onto their rows. A row can appear in several slots, and
        // a claim-all flattens all of them, so the same row must be marked once and
        // given its summed amount rather than marked repeatedly.
        Map<Integer, OverflowItem> rowsById = new LinkedHashMap<>();
        Map<Integer, Integer> wantedByRow = new LinkedHashMap<>();
        for (RowSlice slice : selected) {
            int rowId = slice.row().getId();
            rowsById.putIfAbsent(rowId, slice.row());
            wantedByRow.merge(rowId, slice.amount(), Integer::sum);
        }

        List<Integer> itemIds = List.copyOf(rowsById.keySet());
        CompletableFuture<ClaimResult> result = new CompletableFuture<>();

        // Mark as claimed first to prevent duplication on crash.
        // Crash between mark and give = item loss (preferred over duplication).
        // The per-id variant reports exactly which rows this call claimed: a row
        // taken by a racing claim (its give is already in flight) must not be given
        // again here, or two clicks within one DB roundtrip duplicate it.
        api.markAsClaimedBatchReturningIds(player.getUniqueId(), itemIds).thenAccept(markedIds -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (markedIds.isEmpty()) {
                    messages.sendCommand(player, "database-error");
                    result.complete(new ClaimResult(List.of(), 0, leftBehind));
                    return;
                }

                List<OverflowItem> given = new ArrayList<>();
                List<CompletableFuture<?>> writeBacks = new ArrayList<>();
                int givenAmount = 0;

                for (int rowId : markedIds) {
                    OverflowItem row = rowsById.get(rowId);
                    if (row == null) continue;

                    // Winning the mark consumes the whole row, so everything not handed
                    // over here has to be written back.
                    int wanted = Math.min(wantedByRow.getOrDefault(rowId, 0), row.getAmount());
                    int handedOver = 0;

                    if (wanted > 0) {
                        ItemStack stack = row.getItem();
                        stack.setAmount(wanted);
                        int rejected = 0;
                        // Pre-selected to fit, but the DB roundtrip above gives the player
                        // a window to pick something up, so keep this path as a net.
                        for (ItemStack left : player.getInventory().addItem(stack).values()) {
                            rejected += left.getAmount();
                        }
                        handedOver = wanted - rejected;
                    }

                    if (handedOver > 0) {
                        given.add(row);
                        givenAmount += handedOver;
                    }

                    int writeBack = row.getAmount() - handedOver;
                    if (writeBack > 0) {
                        writeBacks.add(writeBack(player, row, writeBack));
                    }
                }

                Bukkit.getPluginManager().callEvent(new ItemsClaimedAllEvent(player, given));

                // Only report done once the write-backs have landed, so a caller that
                // reloads the view on completion sees them.
                ClaimResult claimResult = new ClaimResult(given, givenAmount, leftBehind);
                CompletableFuture.allOf(writeBacks.toArray(new CompletableFuture[0]))
                        .whenComplete((ignored, error) -> Bukkit.getScheduler().runTask(plugin,
                                () -> result.complete(claimResult)));
            });
        }).exceptionally(throwable -> {
            plugin.getLogger().log(Level.SEVERE, "Failed to batch claim items", throwable);
            Bukkit.getScheduler().runTask(plugin, () -> {
                messages.sendCommand(player, "database-error");
                result.complete(new ClaimResult(List.of(), 0, leftBehind));
            });
            return null;
        });

        return result;
    }

    /**
     * Returns the part of a consumed row that the player didn't receive.
     * <p>
     * The row is already marked claimed, so a failed write here loses the items.
     * It must never fail silently.
     */
    private CompletableFuture<?> writeBack(Player player, OverflowItem row, int amount) {
        return api.addOverflowItem(player, row.getItem(), amount, row.getSource())
                .exceptionally(throwable -> {
                    plugin.getLogger().log(Level.SEVERE, "Lost " + amount + "x " + row.getItem().getType()
                            + " for " + player.getName() + ": writing back the unclaimed remainder of row "
                            + row.getId() + " failed", throwable);
                    return null;
                });
    }

    /**
     * Claims everything that fits and sends the standard claim-all feedback.
     * Used by {@code /overflow claimall} and the GUI's Claim All button.
     */
    public CompletableFuture<ClaimResult> claimAll(Player player, List<OverflowItem> rows) {
        return claimAllSlices(player, wholeRows(rows));
    }

    /**
     * Claim-all over slices, so the GUI can claim in the order its slots are shown.
     */
    public CompletableFuture<ClaimResult> claimAllSlices(Player player, List<RowSlice> slices) {
        return claim(player, slices).thenApply(claimResult -> {
            if (claimResult.isEmpty()) {
                if (claimResult.isPartial()) {
                    messages.send(player, "claim-failed-full");
                }
                return claimResult;
            }

            if (claimResult.isPartial()) {
                messages.send(player, "claim-all-partial",
                        Placeholder.unparsed("claimed", String.valueOf(claimResult.claimedAmount())),
                        Placeholder.unparsed("returned", String.valueOf(claimResult.leftBehind())));
            } else {
                messages.send(player, "claim-all-success",
                        Placeholder.unparsed("count", String.valueOf(claimResult.claimedAmount())));
            }

            playClaimAllSound(player);
            return claimResult;
        });
    }

    /**
     * Plays the claim-all sound if GUI sounds are enabled.
     */
    public void playClaimAllSound(Player player) {
        if (config.areGuiSoundsEnabled()) {
            player.playSound(player.getLocation(), config.getClaimAllSound(), 1.0f, 1.0f);
        }
    }

    /**
     * Walks the slices in order against a throwaway copy of the player's inventory,
     * returning the prefix that genuinely fits.
     *
     * <p>The copy is a real Bukkit inventory so the packing rules (partial stacks
     * first, then empty slots, per-item max stack sizes) are the server's own rather
     * than an approximation. It holds copies of the player's items, so nothing the
     * simulation does touches the live inventory.
     *
     * <p>Stops at the first slice that doesn't fit instead of skipping ahead to smaller
     * ones: a partially-added stack would have already mutated the simulation, and
     * "claims in order until full" is the predictable behaviour anyway.
     */
    private List<RowSlice> selectFitting(Player player, List<RowSlice> slices) {
        Inventory simulation = Bukkit.createInventory(null, STORAGE_SLOTS);
        simulation.setContents(player.getInventory().getStorageContents());

        List<RowSlice> fitting = new ArrayList<>();
        for (RowSlice slice : slices) {
            ItemStack stack = slice.row().getItem();
            stack.setAmount(slice.amount());
            if (!simulation.addItem(stack).isEmpty()) {
                break;
            }
            fitting.add(slice);
        }
        return fitting;
    }
}
