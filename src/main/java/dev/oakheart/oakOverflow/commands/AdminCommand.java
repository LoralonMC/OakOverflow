package dev.oakheart.oakoverflow.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.oakheart.oakoverflow.OakOverflow;
import dev.oakheart.oakoverflow.api.OakOverflowAPI;
import dev.oakheart.oakoverflow.config.ConfigManager;
import dev.oakheart.oakoverflow.gui.UnclaimedItemsGUI;
import dev.oakheart.oakoverflow.message.MessageManager;
import dev.oakheart.oakoverflow.storage.DataStore;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import dev.oakheart.oakoverflow.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

@SuppressWarnings("UnstableApiUsage")
public class AdminCommand {

    private final OakOverflow plugin;
    private final OakOverflowAPI api;
    private final ConfigManager config;
    private final MessageManager messages;

    // Track pending clearall confirmations
    private final Map<UUID, Long> clearallConfirmations = new ConcurrentHashMap<>();
    private volatile long consoleConfirmationTime = 0;
    private static final long CONFIRMATION_TIMEOUT = 30000; // 30 seconds

    public AdminCommand(OakOverflow plugin, OakOverflowAPI api, ConfigManager config, MessageManager messages) {
        this.plugin = plugin;
        this.api = api;
        this.config = config;
        this.messages = messages;
    }

    public void register() {
        LifecycleEventManager<Plugin> manager = plugin.getLifecycleManager();
        manager.registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Commands commands = event.registrar();
            commands.register(buildCommand(), "OakOverflow admin commands", List.of("oof", "oa"));
        });
    }

    /**
     * Shared suggestion provider for online player names.
     */
    private static final SuggestionProvider<CommandSourceStack> PLAYER_SUGGESTIONS = (ctx, builder) -> {
        String input = builder.getRemainingLowerCase();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getName().toLowerCase().startsWith(input)) {
                builder.suggest(p.getName());
            }
        }
        return builder.buildFuture();
    };

    private LiteralCommandNode<CommandSourceStack> buildCommand() {
        return Commands.literal("oakoverflow")
                .requires(src -> src.getSender().hasPermission("oakoverflow.admin"))
                // Base command - help
                .executes(ctx -> {
                    sendAdminHelp(ctx.getSource().getSender());
                    return Command.SINGLE_SUCCESS;
                })
                // view <player>
                .then(Commands.literal("view")
                        .requires(src -> src.getSender().hasPermission("oakoverflow.admin.view")
                                && src.getSender() instanceof Player)
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(PLAYER_SUGGESTIONS)
                                .executes(ctx -> {
                                    handleView((Player) ctx.getSource().getSender(),
                                            StringArgumentType.getString(ctx, "player"));
                                    return Command.SINGLE_SUCCESS;
                                })))
                // clear <player>
                .then(Commands.literal("clear")
                        .requires(src -> src.getSender().hasPermission("oakoverflow.admin.clear"))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(PLAYER_SUGGESTIONS)
                                .executes(ctx -> {
                                    handleClear(ctx.getSource().getSender(),
                                            StringArgumentType.getString(ctx, "player"));
                                    return Command.SINGLE_SUCCESS;
                                })))
                // clearall [confirm]
                .then(Commands.literal("clearall")
                        .requires(src -> src.getSender().hasPermission("oakoverflow.admin.clearall"))
                        .executes(ctx -> {
                            handleClearAll(ctx.getSource().getSender(), false);
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.literal("confirm")
                                .executes(ctx -> {
                                    handleClearAll(ctx.getSource().getSender(), true);
                                    return Command.SINGLE_SUCCESS;
                                })))
                // stats
                .then(Commands.literal("stats")
                        .requires(src -> src.getSender().hasPermission("oakoverflow.admin.stats"))
                        .executes(ctx -> {
                            handleStats(ctx.getSource().getSender());
                            return Command.SINGLE_SUCCESS;
                        }))
                // reload
                .then(Commands.literal("reload")
                        .requires(src -> src.getSender().hasPermission("oakoverflow.admin.reload"))
                        .executes(ctx -> {
                            handleReload(ctx.getSource().getSender());
                            return Command.SINGLE_SUCCESS;
                        }))
                // purge
                .then(Commands.literal("purge")
                        .requires(src -> src.getSender().hasPermission("oakoverflow.admin.purge"))
                        .executes(ctx -> {
                            handlePurge(ctx.getSource().getSender());
                            return Command.SINGLE_SUCCESS;
                        }))
                // migrate
                .then(Commands.literal("migrate")
                        .requires(src -> src.getSender().hasPermission("oakoverflow.admin.migrate"))
                        .then(Commands.literal("sqlite")
                                .executes(ctx -> {
                                    handleMigrate(ctx.getSource().getSender(), "sqlite", false);
                                    return Command.SINGLE_SUCCESS;
                                })
                                .then(Commands.literal("confirm")
                                        .executes(ctx -> {
                                            handleMigrate(ctx.getSource().getSender(), "sqlite", true);
                                            return Command.SINGLE_SUCCESS;
                                        })))
                        .then(Commands.literal("mysql")
                                .executes(ctx -> {
                                    handleMigrate(ctx.getSource().getSender(), "mysql", false);
                                    return Command.SINGLE_SUCCESS;
                                })
                                .then(Commands.literal("confirm")
                                        .executes(ctx -> {
                                            handleMigrate(ctx.getSource().getSender(), "mysql", true);
                                            return Command.SINGLE_SUCCESS;
                                        }))))
                .build();
    }

    // ===== Help =====

    private void sendAdminHelp(CommandSender sender) {
        messages.getMessage("commands.help-header").ifPresent(sender::sendMessage);
        if (sender.hasPermission("oakoverflow.admin.view")) {
            messages.getMessage("commands.help-view").ifPresent(sender::sendMessage);
        }
        if (sender.hasPermission("oakoverflow.admin.clear")) {
            messages.getMessage("commands.help-clear").ifPresent(sender::sendMessage);
        }
        if (sender.hasPermission("oakoverflow.admin.clearall")) {
            messages.getMessage("commands.help-clearall").ifPresent(sender::sendMessage);
        }
        if (sender.hasPermission("oakoverflow.admin.stats")) {
            messages.getMessage("commands.help-stats").ifPresent(sender::sendMessage);
        }
        if (sender.hasPermission("oakoverflow.admin.reload")) {
            messages.getMessage("commands.help-reload").ifPresent(sender::sendMessage);
        }
        if (sender.hasPermission("oakoverflow.admin.purge")) {
            messages.getMessage("commands.help-purge").ifPresent(sender::sendMessage);
        }
        if (sender.hasPermission("oakoverflow.admin.migrate")) {
            messages.getMessage("commands.help-migrate").ifPresent(sender::sendMessage);
        }
        messages.getMessage("commands.help-footer").ifPresent(sender::sendMessage);
    }

    // ===== View =====

    private void handleView(Player admin, String targetName) {
        Player onlineTarget = Bukkit.getPlayer(targetName);

        if (onlineTarget != null) {
            UnclaimedItemsGUI gui = UnclaimedItemsGUI.forAdmin(plugin, admin, onlineTarget,
                    onlineTarget.getUniqueId(), onlineTarget.getName(), api, config, messages);
            gui.open();
            plugin.getLogger().info("[ADMIN] " + admin.getName() + " viewed " + onlineTarget.getName() + "'s unclaimed items (online)");
        } else {
            // getOfflinePlayer(String) is deprecated as a reminder to use UUIDs for persistent
            // storage, but no non-deprecated alternative exists for name-to-UUID resolution.
            // Running async since it may query the Mojang API for unknown players.
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    @SuppressWarnings("deprecation")
                    OfflinePlayer offlineTarget = Bukkit.getOfflinePlayer(targetName);

                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!offlineTarget.hasPlayedBefore()) {
                            messages.getMessage("commands.player-never-joined", "player", targetName)
                                    .ifPresent(admin::sendMessage);
                            return;
                        }

                        UnclaimedItemsGUI gui = UnclaimedItemsGUI.forAdmin(
                                plugin, admin, null,
                                offlineTarget.getUniqueId(),
                                offlineTarget.getName() != null ? offlineTarget.getName() : targetName,
                                api, config, messages
                        );
                        gui.open();
                        plugin.getLogger().info("[ADMIN] " + admin.getName() + " viewed " + targetName + "'s unclaimed items (offline)");
                    });
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE, "Failed to look up player " + targetName, e);
                    Bukkit.getScheduler().runTask(plugin, () ->
                            messages.getMessage("commands.player-not-found", "player", targetName)
                                    .ifPresent(admin::sendMessage)
                    );
                }
            });
        }
    }

    // ===== Clear =====

    private void handleClear(CommandSender sender, String targetName) {
        Player onlineTarget = Bukkit.getPlayer(targetName);
        if (onlineTarget != null) {
            executeClear(sender, targetName, onlineTarget.getUniqueId(), onlineTarget);
            return;
        }

        // See handleView() — no non-deprecated alternative for name-to-UUID resolution
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                @SuppressWarnings("deprecation")
                OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);

                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!target.hasPlayedBefore()) {
                        messages.getMessage("commands.player-not-found", "player", targetName)
                                .ifPresent(sender::sendMessage);
                        return;
                    }
                    executeClear(sender, targetName, target.getUniqueId(), target.getPlayer());
                });
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to look up player " + targetName, e);
                Bukkit.getScheduler().runTask(plugin, () ->
                        messages.getMessage("commands.player-not-found", "player", targetName)
                                .ifPresent(sender::sendMessage)
                );
            }
        });
    }

    private void executeClear(CommandSender sender, String targetName, UUID targetUUID, Player onlineTarget) {
        api.getUnclaimedCount(targetUUID).thenCompose(count -> {
            if (count == 0) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        messages.getMessage("commands.no-items-to-clear", "player", targetName)
                                .ifPresent(sender::sendMessage)
                );
                return CompletableFuture.completedFuture(0);
            }

            return api.clearPlayerItems(targetUUID).thenApply(cleared -> {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Map<String, String> placeholders = new HashMap<>();
                    placeholders.put("count", String.valueOf(cleared));
                    placeholders.put("player", targetName);
                    messages.getMessage("commands.clear-success", placeholders)
                            .ifPresent(sender::sendMessage);

                    plugin.getLogger().info("[ADMIN] " + sender.getName() + " cleared " + cleared + " items for " + targetName);

                    if (onlineTarget != null && onlineTarget.isOnline()) {
                        messages.getMessage("commands.clear-notification")
                                .ifPresent(onlineTarget::sendMessage);
                    }
                });
                return cleared;
            });
        }).exceptionally(throwable -> {
            plugin.getLogger().log(Level.SEVERE, "Failed to clear items for " + targetName, throwable);
            Bukkit.getScheduler().runTask(plugin, () ->
                    messages.getMessage("commands.database-error").ifPresent(sender::sendMessage)
            );
            return null;
        });
    }

    // ===== ClearAll =====

    private void handleClearAll(CommandSender sender, boolean confirmed) {
        // Clean up expired confirmations
        clearallConfirmations.entrySet().removeIf(entry ->
                System.currentTimeMillis() - entry.getValue() > CONFIRMATION_TIMEOUT);

        boolean isPlayer = sender instanceof Player;
        UUID senderId = isPlayer ? ((Player) sender).getUniqueId() : null;

        if (!confirmed) {
            if (isPlayer) {
                clearallConfirmations.put(senderId, System.currentTimeMillis());
            } else {
                consoleConfirmationTime = System.currentTimeMillis();
            }
            messages.getMessage("commands.clearall-warning").ifPresent(sender::sendMessage);
            return;
        }

        // Check if confirmation is valid
        if (isPlayer) {
            Long confirmTime = clearallConfirmations.get(senderId);
            if (confirmTime == null || System.currentTimeMillis() - confirmTime > CONFIRMATION_TIMEOUT) {
                messages.getMessage("commands.clearall-confirm-expired").ifPresent(sender::sendMessage);
                clearallConfirmations.remove(senderId);
                return;
            }
            clearallConfirmations.remove(senderId);
        } else {
            if (consoleConfirmationTime == 0 || System.currentTimeMillis() - consoleConfirmationTime > CONFIRMATION_TIMEOUT) {
                messages.getMessage("commands.clearall-confirm-expired").ifPresent(sender::sendMessage);
                consoleConfirmationTime = 0;
                return;
            }
            consoleConfirmationTime = 0;
        }

        messages.getMessage("commands.clearall-processing").ifPresent(sender::sendMessage);

        api.clearAllItems().thenAccept(cleared -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                messages.getMessage("commands.clear-all-success", "count", String.valueOf(cleared))
                        .ifPresent(sender::sendMessage);

                plugin.getLogger().warning("[ADMIN] " + sender.getName() + " cleared ALL unclaimed items (" + cleared + " total)");

                messages.getMessage("commands.clearall-broadcast").ifPresent(broadcastMsg -> {
                    for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                        if (onlinePlayer.hasPermission("oakoverflow.notify")) {
                            onlinePlayer.sendMessage(broadcastMsg);
                        }
                    }
                });
            });
        }).exceptionally(throwable -> {
            plugin.getLogger().log(Level.SEVERE, "Failed to clear all items", throwable);
            Bukkit.getScheduler().runTask(plugin, () ->
                    messages.getMessage("commands.database-error").ifPresent(sender::sendMessage)
            );
            return null;
        });
    }

    // ===== Stats =====

    private void handleStats(CommandSender sender) {
        messages.getMessage("commands.loading-stats").ifPresent(sender::sendMessage);

        api.getStatistics().thenAccept(stats -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                DecimalFormat df = new DecimalFormat("#,###");
                int claimedItems = stats.totalItems() - stats.unclaimedItems();

                messages.getMessage("commands.stats-header").ifPresent(sender::sendMessage);
                messages.getMessage("commands.stats-total", "total", df.format(stats.totalItems()))
                        .ifPresent(sender::sendMessage);
                messages.getMessage("commands.stats-unclaimed", "unclaimed", df.format(stats.unclaimedItems()))
                        .ifPresent(sender::sendMessage);
                messages.getMessage("commands.stats-claimed", "claimed", df.format(claimedItems))
                        .ifPresent(sender::sendMessage);
                messages.getMessage("commands.stats-players", "players", df.format(stats.uniquePlayers()))
                        .ifPresent(sender::sendMessage);
                messages.getMessage("commands.stats-schema-version", "version", String.valueOf(api.getSchemaVersion()))
                        .ifPresent(sender::sendMessage);
                messages.getMessage("commands.stats-oldest", "age", TimeUtil.formatDuration(stats.oldestItemAge()))
                        .ifPresent(sender::sendMessage);
                messages.getMessage("commands.stats-footer").ifPresent(sender::sendMessage);
            });
        }).exceptionally(throwable -> {
            plugin.getLogger().log(Level.SEVERE, "Failed to get statistics", throwable);
            Bukkit.getScheduler().runTask(plugin, () ->
                    messages.getMessage("commands.database-error").ifPresent(sender::sendMessage)
            );
            return null;
        });
    }

    // ===== Reload =====

    private void handleReload(CommandSender sender) {
        messages.getMessage("commands.reload-processing").ifPresent(sender::sendMessage);

        try {
            boolean success = plugin.reloadPluginConfig();
            if (success) {
                messages.getMessage("commands.reload-success").ifPresent(sender::sendMessage);
                plugin.getLogger().info("[ADMIN] " + sender.getName() + " reloaded the configuration");
            } else {
                messages.getMessage("commands.reload-failed").ifPresent(sender::sendMessage);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to reload configuration", e);
            messages.getMessage("commands.reload-failed").ifPresent(sender::sendMessage);
        }
    }

    // ===== Purge =====

    private void handlePurge(CommandSender sender) {
        messages.getMessage("commands.purge-processing").ifPresent(sender::sendMessage);

        api.purgeCorruptedItems().thenAccept(count -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (count > 0) {
                    messages.getMessage("commands.purge-success", "count", String.valueOf(count))
                            .ifPresent(sender::sendMessage);
                    plugin.getLogger().info("[ADMIN] " + sender.getName() + " purged " + count + " corrupted items");
                } else {
                    messages.getMessage("commands.purge-none").ifPresent(sender::sendMessage);
                }
            });
        }).exceptionally(throwable -> {
            plugin.getLogger().log(Level.SEVERE, "Failed to purge corrupted items", throwable);
            Bukkit.getScheduler().runTask(plugin, () ->
                    messages.getMessage("commands.database-error").ifPresent(sender::sendMessage)
            );
            return null;
        });
    }

    // ===== Migrate =====

    // Track pending migration confirmations
    private final Map<UUID, MigrationRequest> migrateConfirmations = new ConcurrentHashMap<>();
    private volatile MigrationRequest consoleMigrationRequest = null;

    private record MigrationRequest(String targetType, long timestamp) {}

    private void handleMigrate(CommandSender sender, String targetType, boolean confirmed) {
        String currentType = config.getDatabaseType().name().toLowerCase();

        // Check if migrating to same type
        if (currentType.equals(targetType)) {
            messages.getMessage("commands.migrate-same-type", "type", targetType.toUpperCase())
                    .ifPresent(sender::sendMessage);
            return;
        }

        // Validate MySQL settings if migrating to MySQL
        if ("mysql".equals(targetType)) {
            String host = config.getMysqlHost();
            String database = config.getMysqlDatabase();
            if (host.isBlank() || database.isBlank()
                    || ("localhost".equals(host) && "root".equals(config.getMysqlUsername()) && "password".equals(config.getMysqlPassword()))) {
                messages.getMessage("commands.migrate-invalid-target", "target", targetType.toUpperCase())
                        .ifPresent(sender::sendMessage);
                return;
            }
        }

        boolean isPlayer = sender instanceof Player;
        UUID senderId = isPlayer ? ((Player) sender).getUniqueId() : null;

        if (!confirmed) {
            if (isPlayer) {
                migrateConfirmations.put(senderId, new MigrationRequest(targetType, System.currentTimeMillis()));
            } else {
                consoleMigrationRequest = new MigrationRequest(targetType, System.currentTimeMillis());
            }
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("source", currentType.toUpperCase());
            placeholders.put("target", targetType.toUpperCase());
            messages.getMessage("commands.migrate-warning", placeholders)
                    .ifPresent(sender::sendMessage);
            return;
        }

        // Validate confirmation
        MigrationRequest request;
        if (isPlayer) {
            request = migrateConfirmations.remove(senderId);
        } else {
            request = consoleMigrationRequest;
            consoleMigrationRequest = null;
        }

        if (request == null || !request.targetType().equals(targetType)
                || System.currentTimeMillis() - request.timestamp() > CONFIRMATION_TIMEOUT) {
            messages.getMessage("commands.migrate-confirm-expired").ifPresent(sender::sendMessage);
            return;
        }

        executeMigration(sender, currentType, targetType);
    }

    private void executeMigration(CommandSender sender, String sourceType, String targetType) {
        messages.getMessage("commands.migrate-started").ifPresent(sender::sendMessage);

        // Close all open GUIs first
        UnclaimedItemsGUI.closeAll();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            DataStore targetStore = null;
            try {
                // Create target data store
                targetStore = createDataStore(targetType);
                if (!targetStore.initialize()) {
                    throw new RuntimeException("Failed to initialize target " + targetType + " database");
                }

                // Export from source
                DataStore sourceStore = plugin.getAPIImpl().getDataStore();
                List<DataStore.RawOverflowItem> items = sourceStore.exportAllItems().join();

                final int totalItems = items.size();
                DataStore finalTargetStore = targetStore;

                Bukkit.getScheduler().runTask(plugin, () ->
                        messages.getMessage("commands.migrate-progress", "count", String.valueOf(totalItems))
                                .ifPresent(sender::sendMessage)
                );

                // Import to target
                int imported = finalTargetStore.importItems(items).join();

                // Swap on main thread
                DataStore oldStore = sourceStore;
                DataStore newStore = finalTargetStore;

                Bukkit.getScheduler().runTask(plugin, () -> {
                    plugin.swapDataStore(newStore);
                    config.setDatabaseType(targetType);
                    oldStore.close();

                    Map<String, String> placeholders = new HashMap<>();
                    placeholders.put("count", String.valueOf(imported));
                    placeholders.put("target", targetType.toUpperCase());
                    messages.getMessage("commands.migrate-success", placeholders)
                            .ifPresent(sender::sendMessage);

                    plugin.getLogger().info("[ADMIN] " + sender.getName() + " migrated " + imported +
                            " items from " + sourceType.toUpperCase() + " to " + targetType.toUpperCase());
                });

            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Database migration failed", e);
                if (targetStore != null) {
                    targetStore.close();
                }
                Bukkit.getScheduler().runTask(plugin, () ->
                        messages.getMessage("commands.migrate-failed").ifPresent(sender::sendMessage)
                );
            }
        });
    }

    private DataStore createDataStore(String type) {
        return switch (type.toLowerCase()) {
            case "sqlite" -> new dev.oakheart.oakoverflow.storage.SQLiteDatabase(
                    plugin.getDataFolder(), plugin.getLogger(), config);
            case "mysql" -> new dev.oakheart.oakoverflow.storage.MySQLDatabase(config, plugin.getLogger());
            default -> throw new IllegalArgumentException("Unknown database type: " + type);
        };
    }

}
