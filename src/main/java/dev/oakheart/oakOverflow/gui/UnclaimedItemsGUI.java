package dev.oakheart.oakoverflow.gui;

import dev.oakheart.oakoverflow.OakOverflow;
import dev.oakheart.oakoverflow.api.OakOverflowAPI;
import dev.oakheart.oakoverflow.model.OverflowItem;
import dev.oakheart.oakoverflow.events.ItemClaimedEvent;
import dev.oakheart.oakoverflow.config.ConfigManager;
import dev.oakheart.oakoverflow.managers.ClaimManager;
import dev.oakheart.oakoverflow.message.MessageManager;
import dev.oakheart.oakoverflow.util.InventoryHelper;
import dev.oakheart.oakoverflow.util.ItemNameUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
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
    private int currentPage;
    private int totalPages;
    private SortMode currentSortMode;

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
                        messages.getMessage("gui.admin-view-empty", "player", targetName)
                                .ifPresent(viewer::sendMessage);
                    } else {
                        messages.send(viewer, "no-unclaimed-items");
                    }
                    return;
                }

                // Sort items according to current preference
                sortItems();

                // Calculate pages
                int itemsPerPage = config.getItemsPerPage();
                this.totalPages = (int) Math.ceil((double) items.size() / itemsPerPage);

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
                    messages.getMessage("commands.database-error").ifPresent(viewer::sendMessage)
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
            MiniMessage mm = messages.getMiniMessage();
            String rawTitle = messages.getRawMessage("gui.admin-view-title");
            String prefix = messages.getRawPrefix();
            title = mm.deserialize(rawTitle,
                    TagResolver.resolver(
                            Placeholder.parsed("prefix", prefix),
                            Placeholder.unparsed("player", targetName)
                    ));
        } else {
            title = config.getGuiTitle();
        }
        inventory = Bukkit.createInventory(this, config.getGuiSize(), title);

        // Add items for current page
        int itemsPerPage = config.getItemsPerPage();
        int startIndex = currentPage * itemsPerPage;
        int endIndex = Math.min(startIndex + itemsPerPage, items.size());

        for (int i = startIndex; i < endIndex; i++) {
            OverflowItem overflowItem = items.get(i);
            ItemStack displayItem = createDisplayItem(overflowItem);
            inventory.setItem(i - startIndex, displayItem);
        }

        // Add navigation buttons
        addNavigationButtons();

        // Fill empty slots with glass pane
        fillEmptySlots();
    }

    /**
     * Creates a display ItemStack for an OverflowItem.
     * Corrupted items are shown as a BARRIER block with red lore.
     */
    private ItemStack createDisplayItem(OverflowItem overflowItem) {
        if (overflowItem.isCorrupted()) {
            return createCorruptedDisplayItem(overflowItem);
        }

        ItemStack displayItem = overflowItem.getItem(); // getItem() already returns a clone
        displayItem.setAmount(overflowItem.getAmount());

        // Append configurable lore: admin view uses admin-item-lore, player view uses player-item-lore
        String loreKey = viewOnly ? "gui.admin-item-lore" : "gui.player-item-lore";

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("amount", String.valueOf(overflowItem.getAmount()));
        placeholders.put("source", overflowItem.getSource());
        placeholders.put("age", overflowItem.getAgeString());

        List<Component> extraLore = messages.getMessageList(loreKey, placeholders);

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

                meta.lore(noItalic(combinedLore));
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
            messages.getMessage("gui.corrupted-item-name")
                    .map(UnclaimedItemsGUI::noItalic)
                    .ifPresent(meta::displayName);

            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("id", String.valueOf(overflowItem.getId()));
            placeholders.put("source", overflowItem.getSource());
            placeholders.put("age", overflowItem.getAgeString());

            meta.lore(noItalic(messages.getMessageList("gui.corrupted-item-lore", placeholders)));
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
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("current", String.valueOf(currentPage + 1));
                placeholders.put("total", String.valueOf(totalPages));

                meta.displayName(noItalic(messages.getMessage("gui.previous-page-button.name")
                        .orElse(Component.empty())));
                meta.lore(noItalic(messages.getMessageList("gui.previous-page-button.lore", placeholders)));
                prevButton.setItemMeta(meta);
            }
            inventory.setItem(previousPageSlot, prevButton);
        }

        // Sort button
        {
            ItemStack sortButton = new ItemStack(config.getSortButtonMaterial());
            ItemMeta meta = sortButton.getItemMeta();
            if (meta != null) {
                String modeLabel = messages.getRawMessage(getSortModeMessageKey());
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("mode", modeLabel);

                meta.displayName(noItalic(messages.getMessage("gui.sort-button-name")
                        .orElse(Component.empty())));
                meta.lore(noItalic(messages.getMessageList("gui.sort-button-lore", placeholders)));
                sortButton.setItemMeta(meta);
            }
            inventory.setItem(sortSlot, sortButton);
        }

        // Capacity indicator (only when limits configured and not view-only)
        if (config.hasItemLimit() && !viewOnly) {
            ItemStack capacityItem = new ItemStack(config.getCapacityIndicatorMaterial());
            ItemMeta meta = capacityItem.getItemMeta();
            if (meta != null) {
                int count = items.size();
                int max = config.getMaxItemsPerPlayer();
                int percentage = max > 0 ? Math.min(100, (count * 100) / max) : 0;

                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("count", String.valueOf(count));
                placeholders.put("max", String.valueOf(max));
                placeholders.put("percentage", String.valueOf(percentage));

                meta.displayName(noItalic(messages.getMessage("gui.capacity-indicator-name", placeholders)
                        .orElse(Component.empty())));
                meta.lore(noItalic(messages.getMessageList("gui.capacity-indicator-lore", placeholders)));
                capacityItem.setItemMeta(meta);
            }
            inventory.setItem(capacitySlot, capacityItem);
        }

        // Claim all button (only show if not view-only mode)
        if (!viewOnly && !items.isEmpty()) {
            ItemStack claimAllButton = new ItemStack(config.getClaimAllMaterial());
            ItemMeta meta = claimAllButton.getItemMeta();
            if (meta != null) {
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("count", String.valueOf(items.size()));

                meta.displayName(noItalic(messages.getMessage("gui.claim-all-button.name")
                        .orElse(Component.empty())));
                meta.lore(noItalic(messages.getMessageList("gui.claim-all-button.lore", placeholders)));
                claimAllButton.setItemMeta(meta);
            }
            inventory.setItem(claimAllSlot, claimAllButton);
        } else if (viewOnly) {
            // Show info button for admins
            ItemStack infoButton = new ItemStack(config.getViewOnlyMaterial());
            ItemMeta meta = infoButton.getItemMeta();
            if (meta != null) {
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("player", targetName);
                meta.displayName(noItalic(messages.getMessage("gui.view-only-button-name")
                        .orElse(Component.empty())));
                meta.lore(noItalic(messages.getMessageList("gui.view-only-button-lore", placeholders)));
                infoButton.setItemMeta(meta);
            }
            inventory.setItem(claimAllSlot, infoButton);
        }

        // Next page button
        if (currentPage < totalPages - 1) {
            ItemStack nextButton = new ItemStack(config.getNextPageMaterial());
            ItemMeta meta = nextButton.getItemMeta();
            if (meta != null) {
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("current", String.valueOf(currentPage + 1));
                placeholders.put("total", String.valueOf(totalPages));

                meta.displayName(noItalic(messages.getMessage("gui.next-page-button.name")
                        .orElse(Component.empty())));
                meta.lore(noItalic(messages.getMessageList("gui.next-page-button.lore", placeholders)));
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
            meta.displayName(noItalic(messages.getMessage("gui.filler-item.name")
                    .orElse(Component.empty())));
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
            sortItems();
            refresh();
            playNavigationSound();
            return;
        }

        // View-only mode blocks all non-navigation clicks
        if (viewOnly) {
            messages.getMessage("gui.view-only-denied").ifPresent(viewer::sendMessage);
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

        int itemIndex = currentPage * itemsPerPage + slot;
        if (itemIndex >= items.size()) {
            return;
        }

        OverflowItem item = items.get(itemIndex);

        if (leftClick) {
            claimItem(item);
        }
    }

    /**
     * Claims a single item by checking space, marking it as claimed in the database,
     * then giving it to the player. This prevents item duplication.
     */
    private void claimItem(OverflowItem item) {
        // Block claiming of corrupted items
        if (item.isCorrupted()) {
            messages.getMessage("commands.corrupted-item").ifPresent(viewer::sendMessage);
            return;
        }

        // Fire claim event
        ItemClaimedEvent event = new ItemClaimedEvent(viewer, item);
        Bukkit.getPluginManager().callEvent(event);

        if (event.isCancelled()) {
            return;
        }

        // Check if player has inventory space BEFORE marking as claimed
        ItemStack itemStack = item.getItem(); // getItem() already returns a clone
        itemStack.setAmount(item.getAmount());

        if (!InventoryHelper.hasSpace(viewer, itemStack, item.getAmount())) {
            messages.send(viewer, "claim-failed-full");
            return;
        }

        // Mark as claimed in database FIRST to prevent duplication
        api.markAsClaimed(item.getId(), item.getPlayerUUID()).thenAccept(success -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!success) {
                    messages.getMessage("commands.database-error").ifPresent(viewer::sendMessage);
                    return;
                }

                // Now give the item to the player
                HashMap<Integer, ItemStack> leftover = viewer.getInventory().addItem(itemStack);
                if (!leftover.isEmpty()) {
                    // Inventory changed between check and give (rare) - re-add to overflow
                    for (ItemStack left : leftover.values()) {
                        api.addOverflowItem(viewer, left, left.getAmount(), item.getSource());
                    }
                }

                // Always remove original from local list — it's marked claimed in DB.
                // Re-overflowed items are new DB entries that appear on next refresh.
                items.remove(item);

                // Send success message (reuse itemStack instead of cloning again)
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("amount", String.valueOf(item.getAmount()));
                placeholders.put("item", ItemNameUtil.getItemDisplayName(itemStack));
                messages.send(viewer, "claim-success", placeholders);

                // Play sound
                if (config.areGuiSoundsEnabled()) {
                    viewer.playSound(viewer.getLocation(), config.getClaimSound(), 1.0f, 1.0f);
                }

                // Close or refresh GUI
                if (items.isEmpty()) {
                    viewer.closeInventory();
                    messages.send(viewer, "no-unclaimed-items");
                } else {
                    refresh();
                }
            });
        }).exceptionally(throwable -> {
            plugin.getLogger().severe("Failed to claim item: " + throwable.getMessage());
            Bukkit.getScheduler().runTask(plugin, () ->
                    messages.getMessage("commands.database-error").ifPresent(viewer::sendMessage)
            );
            return null;
        });
    }

    /**
     * Handles claiming all items via ClaimManager, then refreshes the GUI.
     */
    private void handleClaimAll() {
        if (items.isEmpty()) {
            messages.send(viewer, "no-unclaimed-items");
            return;
        }

        // Early exit if inventory is completely full — avoids claim/re-overflow churn
        if (InventoryHelper.isFull(viewer.getInventory())) {
            messages.send(viewer, "claim-failed-full");
            return;
        }

        claimManager.claimAll(viewer, items).thenAccept(claimedItems -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!viewer.isOnline()) return;
                items.removeAll(claimedItems);

                if (items.isEmpty()) {
                    viewer.closeInventory();
                    messages.send(viewer, "no-unclaimed-items");
                } else {
                    refresh();
                }
            });
        });
    }


    /**
     * Refreshes the GUI contents in-place without creating a new inventory.
     * This prevents visual flickering when items are claimed or pages change.
     */
    private void refresh() {
        // Recalculate pages
        int itemsPerPage = config.getItemsPerPage();
        this.totalPages = Math.max(1, (int) Math.ceil((double) items.size() / itemsPerPage));

        // Ensure current page is valid
        if (currentPage >= totalPages) {
            currentPage = Math.max(0, totalPages - 1);
        }

        // Update inventory contents in-place (no flicker)
        inventory.clear();

        // Add items for current page
        int startIndex = currentPage * itemsPerPage;
        int endIndex = Math.min(startIndex + itemsPerPage, items.size());

        for (int i = startIndex; i < endIndex; i++) {
            OverflowItem overflowItem = items.get(i);
            ItemStack displayItem = createDisplayItem(overflowItem);
            inventory.setItem(i - startIndex, displayItem);
        }

        // Re-add navigation and filler
        addNavigationButtons();
        fillEmptySlots();
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
     * Strips the default italic decoration that Minecraft applies to custom item text.
     */
    private static Component noItalic(Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }

    /**
     * Strips default italic from every component in a lore list.
     */
    private static List<Component> noItalic(List<Component> lore) {
        return lore.stream().map(UnclaimedItemsGUI::noItalic).toList();
    }

    /**
     * Sorts the items list according to the current sort mode.
     */
    private void sortItems() {
        Comparator<OverflowItem> comparator = switch (currentSortMode) {
            case NEWEST_FIRST -> Comparator.comparingLong(OverflowItem::getReceivedTimestamp).reversed();
            case OLDEST_FIRST -> Comparator.comparingLong(OverflowItem::getReceivedTimestamp);
            case BY_TYPE -> Comparator.comparingInt((OverflowItem item) -> item.isCorrupted() ? 1 : 0)
                    .thenComparing(item -> item.isCorrupted() ? "" : item.getItem().getType().name());
            case BY_SOURCE -> Comparator.comparing(OverflowItem::getSource, String.CASE_INSENSITIVE_ORDER);
        };
        items.sort(comparator);
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
        return inventory;
    }
}
