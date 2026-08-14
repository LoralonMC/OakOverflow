package dev.oakheart.oakoverflow.gui;

import dev.oakheart.oakoverflow.OakOverflow;
import dev.oakheart.oakoverflow.api.OakOverflowAPI;
import dev.oakheart.oakoverflow.model.OverflowItem;
import dev.oakheart.oakoverflow.config.ConfigManager;
import dev.oakheart.oakoverflow.managers.ClaimManager;
import dev.oakheart.message.MessageManager;
import dev.oakheart.oakoverflow.util.InventoryHelper;
import dev.oakheart.oakoverflow.util.ItemNameUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GUI for viewing and claiming unclaimed items.
 * Supports pagination and individual item claiming.
 * Can also be used in view-only mode for admins.
 * Supports viewing offline players' items.
 */
public class UnclaimedItemsGUI implements InventoryHolder {

    // Track all open GUIs by viewer UUID for programmatic close
    private static final Map<UUID, UnclaimedItemsGUI> openGUIs = new ConcurrentHashMap<>();

    // Track per-player sort preferences (in-memory only, resets on restart)
    private static final Map<UUID, SortMode> playerSortPreferences = new ConcurrentHashMap<>();

    private final OakOverflow plugin;
    private final Player viewer; // The player viewing the GUI
    private final Player target;  // The player whose items are being viewed (null if offline, may be same as viewer)
    private final UUID targetUUID; // UUID of the target player (for offline support)
    private final String targetName; // Name of the target player (for offline support)
    private final OakOverflowAPI api;
    private final ConfigManager config;
    private final ClaimManager claimManager;
    private final MessageManager messages;
    private final boolean viewOnly; // If true, disable claiming (admin view mode)

    // Navigation slot positions (computed from GUI size)
    private final int navRowStart;
    private final int previousPageSlot;
    private final int sortSlot;
    private final int claimAllSlot;
    private final int capacitySlot;
    private final int nextPageSlot;

    private Inventory inventory;
    // Only accessed from the main thread (via runTask callbacks)
    private List<OverflowItem> items;
    // Display view of `items`: identical stackable rows collapsed into one slot
    private List<ItemEntry> entries;
    private int currentPage;
    private int totalPages;
    private SortMode currentSortMode;

    /**
     * One slot in the GUI. Storage keeps a separate row per item received (that
     * insert-only design is what makes concurrent adds and claims race-free), so
     * a player who picks up 300 emeralds with a full inventory owns 300 rows.
     * Showing those as 300 slots is unusable, so identical rows are gathered and
     * repacked into full stack-sized slots that read exactly like a chest.
     *
     * <p>Slots are filled to the stack size regardless of where row boundaries
     * fall, so a slot can hold part of a row and {@link #total} always equals the
     * rendered amount. That is what makes the layout dense and honest, and it's
     * why claiming works in slices.
     *
     * @param display the stack as rendered
     * @param total   combined amount in this slot, always equal to the rendered amount
     * @param slices  the row portions making up this slot, newest first
     */
    private record ItemEntry(ItemStack display, int total, List<ClaimManager.RowSlice> slices) {
        boolean isCorrupted() {
            return slices.get(0).row().isCorrupted();
        }

        String source() {
            return slices.get(0).row().getSource();
        }

        OverflowItem firstRow() {
            return slices.get(0).row();
        }

        long newestTimestamp() {
            long newest = Long.MIN_VALUE;
            for (ClaimManager.RowSlice slice : slices) {
                newest = Math.max(newest, slice.row().getReceivedTimestamp());
            }
            return newest;
        }

        long oldestTimestamp() {
            long oldest = Long.MAX_VALUE;
            for (ClaimManager.RowSlice slice : slices) {
                oldest = Math.min(oldest, slice.row().getReceivedTimestamp());
            }
            return oldest;
        }
    }

    /**
     * Creates a GUI for a player to view and claim their own items.
     */
    public static UnclaimedItemsGUI forPlayer(OakOverflow plugin, Player player, OakOverflowAPI api,
                                                ConfigManager config, ClaimManager claimManager,
                                                MessageManager messages) {
        return new UnclaimedItemsGUI(plugin, player, player, player.getUniqueId(), player.getName(),
                api, config, claimManager, messages, false);
    }

    /**
     * Creates a GUI for viewing another player's items (admin mode).
     * Supports both online and offline targets.
     */
    public static UnclaimedItemsGUI forAdmin(OakOverflow plugin, Player viewer, Player targetPlayer,
                                               UUID targetUUID, String targetName,
                                               OakOverflowAPI api, ConfigManager config,
                                               MessageManager messages) {
        return new UnclaimedItemsGUI(plugin, viewer, targetPlayer, targetUUID, targetName,
                api, config, null, messages, true);
    }

    private UnclaimedItemsGUI(OakOverflow plugin, Player viewer, Player targetPlayer, UUID targetUUID, String targetName,
                               OakOverflowAPI api, ConfigManager config, ClaimManager claimManager,
                               MessageManager messages, boolean viewOnly) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.target = targetPlayer;
        this.targetUUID = targetUUID;
        this.targetName = targetName;
        this.api = api;
        this.config = config;
        this.claimManager = claimManager;
        this.messages = messages;
        this.viewOnly = viewOnly;
        this.currentPage = 0;
        this.items = new ArrayList<>();
        this.entries = new ArrayList<>();
        this.currentSortMode = playerSortPreferences.getOrDefault(viewer.getUniqueId(), SortMode.NEWEST_FIRST);

        int guiSize = config.getGuiSize();
        this.navRowStart = guiSize - 9;
        this.previousPageSlot = navRowStart;
        this.sortSlot = navRowStart + 3;
        this.claimAllSlot = navRowStart + 4;
        this.capacitySlot = navRowStart + 5;
        this.nextPageSlot = navRowStart + 8;
    }

    /**
     * Opens the GUI for the viewer.
     */
    public void open() {
        // Load items asynchronously (from target player by UUID - supports offline)
        api.getUnclaimedItems(targetUUID).thenAccept(loadedItems -> {
            // Run on main thread to open inventory (items assigned on main thread only)
            Bukkit.getScheduler().runTask(plugin, () -> {
                this.items = new ArrayList<>(loadedItems);

                if (!viewer.isOnline()) {
                    return;
                }

                if (items.isEmpty()) {
                    if (viewOnly) {
                        messages.parse("gui.admin-view-empty",
                                Placeholder.unparsed("player", targetName))
                                .ifPresent(viewer::sendMessage);
                    } else {
                        messages.send(viewer, "no-unclaimed-items");
                    }
                    return;
                }

                // Collapse identical rows into display entries, then sort
                rebuildEntries();

                // Calculate pages
                int itemsPerPage = config.getItemsPerPage();
                this.totalPages = Math.max(1, (int) Math.ceil((double) entries.size() / itemsPerPage));

                // Create and open inventory
                buildInventory();

                openGUIs.put(viewer.getUniqueId(), this);
                viewer.openInventory(inventory);

                // Play sound
                if (config.areGuiSoundsEnabled()) {
                    viewer.playSound(viewer.getLocation(), config.getOpenSound(), 1.0f, 1.0f);
                }
            });
        }).exceptionally(throwable -> {
            plugin.getLogger().severe("Failed to load unclaimed items for GUI: " + throwable.getMessage());
            Bukkit.getScheduler().runTask(plugin, () ->
                    messages.sendCommand(viewer, "database-error")
            );
            return null;
        });
    }

    /**
     * Builds the inventory GUI.
     */
    private void buildInventory() {
        // Create inventory with appropriate title
        Component title;
        if (viewOnly && !viewer.getUniqueId().equals(targetUUID)) {
            // Admin viewing another player's items
            String rawTitle = messages.getMessageText("gui.admin-view-title");
            title = rawTitle != null
                    ? messages.deserialize(rawTitle, Placeholder.unparsed("player", targetName))
                    : config.getGuiTitle();
        } else {
            title = config.getGuiTitle();
        }
        inventory = Bukkit.createInventory(this, config.getGuiSize(), title);

        renderPage();

        // Add navigation buttons
        addNavigationButtons();

        // Fill empty slots with glass pane
        fillEmptySlots();
    }

    /**
     * Creates a display ItemStack for an OverflowItem.
     * Corrupted items are shown as a BARRIER block with red lore.
     */
    private ItemStack createDisplayItem(ItemEntry entry) {
        if (entry.isCorrupted()) {
            return createCorruptedDisplayItem(entry.firstRow());
        }

        ItemStack displayItem = entry.display().clone();

        // Append configurable lore: admin view uses admin-item-lore, player view uses player-item-lore
        String loreKey = viewOnly ? "gui.admin-item-lore" : "gui.player-item-lore";

        TagResolver resolvers = TagResolver.resolver(
                Placeholder.unparsed("amount", String.valueOf(entry.total())),
                Placeholder.unparsed("stacks", String.valueOf(entry.slices().size())),
                Placeholder.unparsed("source", entry.source()),
                Placeholder.unparsed("age", entry.firstRow().getAgeString()));

        List<Component> extraLore = new ArrayList<>(deserializeStringList(loreKey, resolvers));

        if (!extraLore.isEmpty()) {
            ItemMeta meta = displayItem.getItemMeta();
            if (meta != null) {
                List<Component> existingLore = meta.lore();
                if (existingLore == null) {
                    existingLore = new ArrayList<>();
                }

                List<Component> combinedLore = new ArrayList<>(existingLore);
                if (!existingLore.isEmpty()) {
                    combinedLore.add(Component.empty());
                }
                combinedLore.addAll(extraLore);

                meta.lore(combinedLore);
                displayItem.setItemMeta(meta);
            }
        }

        return displayItem;
    }

    /**
     * Creates a BARRIER display item for a corrupted/undeserializable overflow item.
     * Uses config-driven MiniMessage text from gui.corrupted-item-name and gui.corrupted-item-lore.
     */
    private ItemStack createCorruptedDisplayItem(OverflowItem overflowItem) {
        ItemStack barrier = new ItemStack(Material.BARRIER);
        ItemMeta meta = barrier.getItemMeta();
        if (meta != null) {
            String nameText = messages.getMessageText("gui.corrupted-item-name");
            if (nameText != null && !nameText.isEmpty()) {
                meta.displayName(messages.deserialize(nameText));
            }

            TagResolver resolvers = TagResolver.resolver(
                    Placeholder.unparsed("id", String.valueOf(overflowItem.getId())),
                    Placeholder.unparsed("source", overflowItem.getSource()),
                    Placeholder.unparsed("age", overflowItem.getAgeString()));

            meta.lore(deserializeStringList("gui.corrupted-item-lore", resolvers));
            barrier.setItemMeta(meta);
        }
        return barrier;
    }

    /**
     * Adds navigation buttons to the GUI.
     */
    private void addNavigationButtons() {
        // Previous page button
        if (currentPage > 0) {
            ItemStack prevButton = new ItemStack(config.getPreviousPageMaterial());
            ItemMeta meta = prevButton.getItemMeta();
            if (meta != null) {
                TagResolver resolvers = TagResolver.resolver(
                        Placeholder.unparsed("current", String.valueOf(currentPage + 1)),
                        Placeholder.unparsed("total", String.valueOf(totalPages)));

                String nameText = messages.getMessageText("gui.previous-page-button-name");
                if (nameText != null) {
                    meta.displayName(messages.deserialize(nameText));
                }
                meta.lore(deserializeStringList("gui.previous-page-button-lore", resolvers));
                prevButton.setItemMeta(meta);
            }
            inventory.setItem(previousPageSlot, prevButton);
        }

        // Sort button
        {
            ItemStack sortButton = new ItemStack(config.getSortButtonMaterial());
            ItemMeta meta = sortButton.getItemMeta();
            if (meta != null) {
                String modeLabel = messages.getMessageText(getSortModeMessageKey());
                TagResolver resolvers = TagResolver.resolver(
                        Placeholder.unparsed("mode", modeLabel != null ? modeLabel : ""));

                String nameText = messages.getMessageText("gui.sort-button-name");
                if (nameText != null) {
                    meta.displayName(messages.deserialize(nameText));
                }
                meta.lore(deserializeStringList("gui.sort-button-lore", resolvers));
                sortButton.setItemMeta(meta);
            }
            inventory.setItem(sortSlot, sortButton);
        }

        // Capacity indicator (only when limits configured and not view-only)
        if (config.hasItemLimit() && !viewOnly) {
            ItemStack capacityItem = new ItemStack(config.getCapacityIndicatorMaterial());
            ItemMeta meta = capacityItem.getItemMeta();
            if (meta != null) {
                // max-items-per-player counts items, so the indicator must too
                int count = totalItemCount();
                int max = config.getMaxItemsPerPlayer();
                int percentage = max > 0 ? Math.min(100, (count * 100) / max) : 0;

                TagResolver resolvers = TagResolver.resolver(
                        Placeholder.unparsed("count", String.valueOf(count)),
                        Placeholder.unparsed("max", String.valueOf(max)),
                        Placeholder.unparsed("percentage", String.valueOf(percentage)));

                String nameText = messages.getMessageText("gui.capacity-indicator-name");
                if (nameText != null) {
                    meta.displayName(messages.deserialize(nameText, resolvers));
                }
                meta.lore(deserializeStringList("gui.capacity-indicator-lore", resolvers));
                capacityItem.setItemMeta(meta);
            }
            inventory.setItem(capacitySlot, capacityItem);
        }

        // Claim all button (only show if not view-only mode)
        if (!viewOnly && !items.isEmpty()) {
            ItemStack claimAllButton = new ItemStack(config.getClaimAllMaterial());
            ItemMeta meta = claimAllButton.getItemMeta();
            if (meta != null) {
                TagResolver resolvers = TagResolver.resolver(
                        Placeholder.unparsed("count", String.valueOf(totalItemCount())),
                        Placeholder.unparsed("stacks", String.valueOf(entries.size())));

                String nameText = messages.getMessageText("gui.claim-all-button-name");
                if (nameText != null) {
                    meta.displayName(messages.deserialize(nameText));
                }
                meta.lore(deserializeStringList("gui.claim-all-button-lore", resolvers));
                claimAllButton.setItemMeta(meta);
            }
            inventory.setItem(claimAllSlot, claimAllButton);
        } else if (viewOnly) {
            // Show info button for admins
            ItemStack infoButton = new ItemStack(config.getViewOnlyMaterial());
            ItemMeta meta = infoButton.getItemMeta();
            if (meta != null) {
                TagResolver resolvers = TagResolver.resolver(
                        Placeholder.unparsed("player", targetName));

                String nameText = messages.getMessageText("gui.view-only-button-name");
                if (nameText != null) {
                    meta.displayName(messages.deserialize(nameText));
                }
                meta.lore(deserializeStringList("gui.view-only-button-lore", resolvers));
                infoButton.setItemMeta(meta);
            }
            inventory.setItem(claimAllSlot, infoButton);
        }

        // Next page button
        if (currentPage < totalPages - 1) {
            ItemStack nextButton = new ItemStack(config.getNextPageMaterial());
            ItemMeta meta = nextButton.getItemMeta();
            if (meta != null) {
                TagResolver resolvers = TagResolver.resolver(
                        Placeholder.unparsed("current", String.valueOf(currentPage + 1)),
                        Placeholder.unparsed("total", String.valueOf(totalPages)));

                String nameText = messages.getMessageText("gui.next-page-button-name");
                if (nameText != null) {
                    meta.displayName(messages.deserialize(nameText));
                }
                meta.lore(deserializeStringList("gui.next-page-button-lore", resolvers));
                nextButton.setItemMeta(meta);
            }
            inventory.setItem(nextPageSlot, nextButton);
        }
    }

    /**
     * Fills empty slots in the navigation row with filler items.
     */
    private void fillEmptySlots() {
        ItemStack filler = new ItemStack(config.getFillerMaterial());
        ItemMeta meta = filler.getItemMeta();
        if (meta != null) {
            String nameText = messages.getMessageText("gui.filler-item-name");
            if (nameText != null) {
                meta.displayName(messages.deserialize(nameText));
            }
            filler.setItemMeta(meta);
        }

        int guiSize = config.getGuiSize();
        for (int i = navRowStart; i < guiSize; i++) {
            if (inventory.getItem(i) == null) {
                inventory.setItem(i, filler);
            }
        }
    }

    /**
     * Handles clicking an item in the GUI.
     */
    public void handleClick(int slot, boolean leftClick) {
        // Navigation buttons work in both normal and view-only mode
        if (slot == previousPageSlot && currentPage > 0) {
            currentPage--;
            refresh();
            playNavigationSound();
            return;
        }

        if (slot == nextPageSlot && currentPage < totalPages - 1) {
            currentPage++;
            refresh();
            playNavigationSound();
            return;
        }

        // Sort button works in both modes
        if (slot == sortSlot) {
            currentSortMode = currentSortMode.next();
            playerSortPreferences.put(viewer.getUniqueId(), currentSortMode);
            currentPage = 0;
            rebuildEntries();
            refresh();
            playNavigationSound();
            return;
        }

        // View-only mode blocks all non-navigation clicks
        if (viewOnly) {
            messages.parse("gui.view-only-denied").ifPresent(viewer::sendMessage);
            return;
        }

        // Claim all button
        if (slot == claimAllSlot) {
            handleClaimAll();
            return;
        }

        // Item slot — attempt to claim
        int itemsPerPage = config.getItemsPerPage();
        if (slot >= itemsPerPage) {
            return;
        }

        int entryIndex = currentPage * itemsPerPage + slot;
        if (entryIndex >= entries.size()) {
            return;
        }

        if (leftClick) {
            claimEntry(entries.get(entryIndex));
        }
    }

    /**
     * Claims a whole display entry: every storage row behind that slot, in order,
     * for as much as the player's inventory can hold. A stack too big to fit at once
     * is claimed partially and the remainder stays in overflow.
     */
    private void claimEntry(ItemEntry entry) {
        // Block claiming of corrupted items
        if (entry.isCorrupted()) {
            messages.sendCommand(viewer, "corrupted-item");
            return;
        }

        String itemName = ItemNameUtil.getItemDisplayName(entry.display());

        claimManager.claim(viewer, entry.slices()).thenAccept(result -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!viewer.isOnline()) return;

                if (result.isEmpty()) {
                    messages.send(viewer, "claim-failed-full");
                    return;
                }

                messages.send(viewer, "claim-success",
                        Placeholder.unparsed("amount", String.valueOf(result.claimedAmount())),
                        Placeholder.unparsed("item", itemName));

                if (config.areGuiSoundsEnabled()) {
                    viewer.playSound(viewer.getLocation(), config.getClaimSound(), 1.0f, 1.0f);
                }

                afterClaim();
            });
        });
    }

    /**
     * Handles claiming everything via ClaimManager, then refreshes the GUI.
     */
    private void handleClaimAll() {
        if (items.isEmpty()) {
            messages.send(viewer, "no-unclaimed-items");
            return;
        }

        // Early exit if the inventory can't take anything. ClaimManager would reach
        // the same conclusion, but this saves the round trip
        if (InventoryHelper.isFull(viewer.getInventory())) {
            messages.send(viewer, "claim-failed-full");
            return;
        }

        // Claim in the order shown, so what the player sees at the top goes first
        List<ClaimManager.RowSlice> ordered = new ArrayList<>();
        for (ItemEntry entry : entries) {
            ordered.addAll(entry.slices());
        }

        claimManager.claimAllSlices(viewer, ordered).thenAccept(result -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!viewer.isOnline()) return;
                afterClaim();
            });
        });
    }

    /**
     * Total items on display, across every entry. Matches what the unclaimed count
     * and max-items-per-player measure.
     */
    private int totalItemCount() {
        int total = 0;
        for (ItemEntry entry : entries) {
            total += entry.total();
        }
        return total;
    }

    /**
     * Reloads from storage after a claim and redraws, closing the GUI once nothing
     * is left.
     * <p>
     * A claim can consume a row and write part of it back as a new row, so the local
     * list can't be patched by removing what was claimed. Re-reading is the only way
     * to learn the write-back's identity, and it also picks up anything that landed in
     * overflow while the GUI was open. {@link ClaimManager#claim} only completes once
     * its write-backs have committed, so this never races them.
     */
    private void afterClaim() {
        api.getUnclaimedItems(targetUUID).thenAccept(loaded -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!viewer.isOnline()) return;

                this.items = new ArrayList<>(loaded);
                if (items.isEmpty()) {
                    viewer.closeInventory();
                    messages.send(viewer, "no-unclaimed-items");
                    return;
                }
                rebuildEntries();
                refresh();
            });
        }).exceptionally(throwable -> {
            plugin.getLogger().log(java.util.logging.Level.SEVERE,
                    "Failed to reload overflow items after a claim for " + viewer.getName(), throwable);
            Bukkit.getScheduler().runTask(plugin, () ->
                    messages.sendCommand(viewer, "database-error"));
            return null;
        });
    }


    /**
     * Refreshes the GUI contents in-place without creating a new inventory.
     * This prevents visual flickering when items are claimed or pages change.
     */
    private void refresh() {
        // Recalculate pages
        int itemsPerPage = config.getItemsPerPage();
        this.totalPages = Math.max(1, (int) Math.ceil((double) entries.size() / itemsPerPage));

        // Ensure current page is valid
        if (currentPage >= totalPages) {
            currentPage = Math.max(0, totalPages - 1);
        }

        // Update inventory contents in-place (no flicker)
        inventory.clear();

        renderPage();

        // Re-add navigation and filler
        addNavigationButtons();
        fillEmptySlots();
    }

    /**
     * Draws the current page's entries into the item slots.
     */
    private void renderPage() {
        int itemsPerPage = config.getItemsPerPage();
        int startIndex = currentPage * itemsPerPage;
        int endIndex = Math.min(startIndex + itemsPerPage, entries.size());

        for (int i = startIndex; i < endIndex; i++) {
            inventory.setItem(i - startIndex, createDisplayItem(entries.get(i)));
        }
    }

    /**
     * Plays navigation sound.
     */
    private void playNavigationSound() {
        if (config.areGuiSoundsEnabled()) {
            viewer.playSound(viewer.getLocation(), config.getNavigationSound(), 1.0f, 1.0f);
        }
    }

    /**
     * Reads a string list from messages.yml config and deserializes each line
     * using the library's MessageManager (which suppresses italic for item lore).
     */
    private List<Component> deserializeStringList(String key, TagResolver resolvers) {
        List<String> lines = messages.getConfig().getStringList(key);
        List<Component> result = new ArrayList<>(lines.size());
        for (String line : lines) {
            result.add(messages.deserialize(line, resolvers));
        }
        return result;
    }

    /**
     * Rebuilds the display entries from the current row list, collapsing identical
     * rows into one entry each, then sorts the entries by the active sort mode.
     *
     * <p>Rows group on item <i>and</i> source, so every lore field an entry shows
     * (source, and sorting by it) stays true of every row behind it. The grouping
     * key is a clone normalised to amount 1: {@code ItemStack} equality includes
     * the amount, while the items are otherwise identical, so normalising is what
     * makes "same item, different stack size" land in the same bucket.
     *
     * <p>Corrupted rows can't be compared (they have no ItemStack) and each stay
     * their own entry.
     */
    private void rebuildEntries() {
        // Bucket by material first (cheap and exact), then compare within the bucket
        // with isSimilar. Going straight to a hash map keyed on ItemStack would lean on
        // ItemMeta hashCode agreeing across implementations for every custom item on the
        // server; isSimilar is the comparison Bukkit itself uses for stacking, and the
        // material bucket keeps it from becoming an O(n²) scan.
        Map<Material, List<OpenGroup>> buckets = new LinkedHashMap<>();
        List<ItemEntry> rebuilt = new ArrayList<>();

        for (OverflowItem row : items) {
            if (row.isCorrupted()) {
                rebuilt.add(new ItemEntry(null, row.getAmount(),
                        List.of(new ClaimManager.RowSlice(row, row.getAmount()))));
                continue;
            }

            ItemStack normalised = row.getItem();
            normalised.setAmount(1);

            List<OpenGroup> bucket = buckets.computeIfAbsent(normalised.getType(), k -> new ArrayList<>());
            OpenGroup match = null;
            for (OpenGroup candidate : bucket) {
                if (candidate.item.isSimilar(normalised) && candidate.source.equals(row.getSource())) {
                    match = candidate;
                    break;
                }
            }
            if (match == null) {
                match = new OpenGroup(normalised, row.getSource());
                bucket.add(match);
            }
            match.rows.add(row);
            match.total += row.getAmount();
        }

        for (List<OpenGroup> bucket : buckets.values()) {
            for (OpenGroup group : bucket) {
                packIntoStacks(group, rebuilt);
            }
        }

        rebuilt.sort(entryComparator());
        this.entries = rebuilt;
    }

    /**
     * Packs a group's rows into full stack-sized display entries, so the GUI reads
     * exactly like a chest: 300 emeralds are 64/64/64/64/44 across five slots, and every
     * slot shows its own true count.
     *
     * <p>Slots are filled to the stack size without regard for row boundaries, so a row
     * can straddle two slots: three rows of 40 lay out as 64 + 56, with the middle row
     * split 24/16 between them. Each slot therefore carries slices rather than whole
     * rows, and {@link ClaimManager#claim} writes back whatever part of a consumed row
     * the player didn't take.
     *
     * <p>Rows arrive capped at one stack (the API splits larger grants on write), but
     * this handles an oversized legacy row correctly anyway: it simply spans as many
     * slots as it needs.
     */
    private void packIntoStacks(OpenGroup group, List<ItemEntry> out) {
        int maxStack = Math.max(1, group.item.getMaxStackSize());
        List<ClaimManager.RowSlice> current = new ArrayList<>();
        int currentTotal = 0;

        for (OverflowItem row : group.rows) {
            int unplaced = row.getAmount();
            while (unplaced > 0) {
                int take = Math.min(unplaced, maxStack - currentTotal);
                if (take <= 0) {
                    break; // unreachable: a slot is flushed the moment it fills
                }
                current.add(new ClaimManager.RowSlice(row, take));
                currentTotal += take;
                unplaced -= take;

                if (currentTotal >= maxStack) {
                    out.add(entryOf(group.item, currentTotal, current));
                    current = new ArrayList<>();
                    currentTotal = 0;
                }
            }
        }

        if (!current.isEmpty()) {
            out.add(entryOf(group.item, currentTotal, current));
        }
    }

    /**
     * Builds a display entry. Packing never overfills a slot, so the rendered amount
     * is the slot's true content.
     */
    private ItemEntry entryOf(ItemStack prototype, int total, List<ClaimManager.RowSlice> slices) {
        ItemStack display = prototype.clone();
        display.setAmount(Math.max(1, Math.min(total, display.getMaxStackSize())));
        return new ItemEntry(display, total, List.copyOf(slices));
    }

    /**
     * Mutable accumulator used only while grouping.
     */
    private static final class OpenGroup {
        private final ItemStack item;
        private final String source;
        private final List<OverflowItem> rows = new ArrayList<>();
        private int total;

        private OpenGroup(ItemStack item, String source) {
            this.item = item;
            this.source = source;
        }
    }

    /**
     * Comparator for display entries under the active sort mode. Corrupted entries
     * always sort last so a broken row never heads the list.
     */
    private Comparator<ItemEntry> entryComparator() {
        Comparator<ItemEntry> corruptedLast = Comparator.comparingInt(entry -> entry.isCorrupted() ? 1 : 0);
        return switch (currentSortMode) {
            case NEWEST_FIRST -> corruptedLast.thenComparing(
                    Comparator.comparingLong(ItemEntry::newestTimestamp).reversed());
            case OLDEST_FIRST -> corruptedLast.thenComparingLong(ItemEntry::oldestTimestamp);
            case BY_TYPE -> corruptedLast.thenComparing(
                    entry -> entry.isCorrupted() ? "" : entry.display().getType().name());
            case BY_SOURCE -> corruptedLast.thenComparing(ItemEntry::source, String.CASE_INSENSITIVE_ORDER);
        };
    }

    /**
     * Returns the config message key suffix for the current sort mode label.
     */
    private String getSortModeMessageKey() {
        return switch (currentSortMode) {
            case NEWEST_FIRST -> "gui.sort-mode-newest";
            case OLDEST_FIRST -> "gui.sort-mode-oldest";
            case BY_TYPE -> "gui.sort-mode-type";
            case BY_SOURCE -> "gui.sort-mode-source";
        };
    }

    // ========================================
    //          GUI Tracking
    // ========================================

    /**
     * Removes this GUI from the open tracking map.
     * Called by GUIListener on inventory close.
     */
    public static void untrack(UUID viewerUUID) {
        openGUIs.remove(viewerUUID);
    }

    /**
     * Removes a player's sort preference. Called on player quit to prevent
     * unbounded growth of the sort preferences map during long sessions.
     */
    public static void clearSortPreference(UUID playerUUID) {
        playerSortPreferences.remove(playerUUID);
    }

    /**
     * Closes all open OakOverflow GUIs.
     * Called on plugin disable and reload.
     */
    public static void closeAll() {
        for (UnclaimedItemsGUI gui : new ArrayList<>(openGUIs.values())) {
            if (gui.viewer.isOnline()) {
                gui.viewer.closeInventory();
            }
        }
        openGUIs.clear();
        playerSortPreferences.clear();
    }

    @Override
    public @NotNull Inventory getInventory() {
        if (inventory == null) {
            // Contract is @NotNull, but the inventory isn't built until the async item
            // load returns. Hand back an empty one rather than an NPE if anything asks
            // in that window.
            inventory = Bukkit.createInventory(this, config.getGuiSize(), config.getGuiTitle());
        }
        return inventory;
    }
}
