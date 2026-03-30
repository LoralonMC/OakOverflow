package dev.oakheart.oakoverflow.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.oakheart.command.CommandRegistrar;
import dev.oakheart.oakoverflow.OakOverflow;
import dev.oakheart.oakoverflow.api.OakOverflowAPI;
import dev.oakheart.oakoverflow.config.ConfigManager;
import dev.oakheart.oakoverflow.gui.UnclaimedItemsGUI;
import dev.oakheart.message.MessageManager;
import dev.oakheart.oakoverflow.storage.DataStore;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import dev.oakheart.oakoverflow.util.TimeUtil;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

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
        CommandRegistrar.register(plugin, buildCommand(),
                "OakOverflow admin commands", List.of("oof", "oa"));
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
        messages.sendCommand(sender, "help-header");
        if (sender.hasPermission("oakoverflow.admin.view")) {
            messages.sendCommand(sender, "help-view");
        }
        if (sender.hasPermission("oakoverflow.admin.clear")) {
            messages.sendCommand(sender, "help-clear");
        }
        if (sender.hasPermission("oakoverflow.admin.clearall")) {
            messages.sendCommand(sender, "help-clearall");
        }
        if (sender.hasPermission("oakoverflow.admin.stats")) {
            messages.sendCommand(sender, "help-stats");
        }
        if (sender.hasPermission("oakoverflow.admin.reload")) {
            messages.sendCommand(sender, "help-reload");
        }
        if (sender.hasPermission("oakoverflow.admin.purge")) {
            messages.sendCommand(sender, "help-purge");
        }
        if (sender.hasPermission("oakoverflow.admin.migrate")) {
            messages.sendCommand(sender, "help-migrate");
        }
        messages.sendCommand(sender, "help-footer");
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
                            messages.sendCommand(admin, "player-never-joined",
                                    Placeholder.unparsed("player", targetName));
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
                            messages.sendCommand(admin, "player-not-found",
                                    Placeholder.unparsed("player", targetName))
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
                        messages.sendCommand(sender, "player-not-found",
                                Placeholder.unparsed("player", targetName));
                        return;
                    }
                    executeClear(sender, targetName, target.getUniqueId(), target.getPlayer());
                });
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to look up player " + targetName, e);
                Bukkit.getScheduler().runTask(plugin, () ->
                        messages.sendCommand(sender, "player-not-found",
                                Placeholder.unparsed("player", targetName))
                );
            }
        });
    }

    private void executeClear(CommandSender sender, String targetName, UUID targetUUID, Player onlineTarget) {
        api.getUnclaimedCount(targetUUID).thenCompose(count -> {
            if (count == 0) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        messages.sendCommand(sender, "no-items-to-clear",
                                Placeholder.unparsed("player", targetName))
                );
                return CompletableFuture.completedFuture(0);
            }

            return api.clearPlayerItems(targetUUID).thenApply(cleared -> {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    messages.sendCommand(sender, "clear-success",
                            Placeholder.unparsed("count", String.valueOf(cleared)),
                            Placeholder.unparsed("player", targetName));

                    plugin.getLogger().info("[ADMIN] " + sender.getName() + " cleared " + cleared + " items for " + targetName);

                    if (onlineTarget != null && onlineTarget.isOnline()) {
                        messages.sendCommand(onlineTarget, "clear-notification");
                    }
                });
                return cleared;
            });
        }).exceptionally(throwable -> {
            plugin.getLogger().log(Level.SEVERE, "Failed to clear items for " + targetName, throwable);
            Bukkit.getScheduler().runTask(plugin, () ->
                    messages.sendCommand(sender, "database-error")
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
            messages.sendCommand(sender, "clearall-warning");
            return;
        }

        // Check if confirmation is valid
        if (isPlayer) {
            Long confirmTime = clearallConfirmations.get(senderId);
            if (confirmTime == null || System.currentTimeMillis() - confirmTime > CONFIRMATION_TIMEOUT) {
                messages.sendCommand(sender, "clearall-confirm-expired");
                clearallConfirmations.remove(senderId);
                return;
            }
            clearallConfirmations.remove(senderId);
        } else {
            if (consoleConfirmationTime == 0 || System.currentTimeMillis() - consoleConfirmationTime > CONFIRMATION_TIMEOUT) {
                messages.sendCommand(sender, "clearall-confirm-expired");
                consoleConfirmationTime = 0;
                return;
            }
            consoleConfirmationTime = 0;
        }

        messages.sendCommand(sender, "clearall-processing");

        api.clearAllItems().thenAccept(cleared -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                messages.sendCommand(sender, "clear-all-success",
                        Placeholder.unparsed("count", String.valueOf(cleared)));

                plugin.getLogger().warning("[ADMIN] " + sender.getName() + " cleared ALL unclaimed items (" + cleared + " total)");

                messages.parse("commands.clearall-broadcast").ifPresent(broadcastMsg -> {
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
                    messages.sendCommand(sender, "database-error")
            );
            return null;
        });
    }

    // ===== Stats =====

    private void handleStats(CommandSender sender) {
        messages.sendCommand(sender, "loading-stats");

        api.getStatistics().thenAccept(stats -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                DecimalFormat df = new DecimalFormat("#,###");
                int claimedItems = stats.totalItems() - stats.unclaimedItems();

                messages.sendCommand(sender, "stats-header");
                messages.sendCommand(sender, "stats-total",
                        Placeholder.unparsed("total", df.format(stats.totalItems())));
                messages.sendCommand(sender, "stats-unclaimed",
                        Placeholder.unparsed("unclaimed", df.format(stats.unclaimedItems())));
                messages.sendCommand(sender, "stats-claimed",
                        Placeholder.unparsed("claimed", df.format(claimedItems)));
                messages.sendCommand(sender, "stats-players",
                        Placeholder.unparsed("players", df.format(stats.uniquePlayers())));
                messages.sendCommand(sender, "stats-schema-version",
                        Placeholder.unparsed("version", String.valueOf(api.getSchemaVersion())));
                messages.sendCommand(sender, "stats-oldest",
                        Placeholder.unparsed("age", TimeUtil.formatDuration(stats.oldestItemAge())));
                messages.sendCommand(sender, "stats-footer");
            });
        }).exceptionally(throwable -> {
            plugin.getLogger().log(Level.SEVERE, "Failed to get statistics", throwable);
            Bukkit.getScheduler().runTask(plugin, () ->
                    messages.sendCommand(sender, "database-error")
            );
            return null;
        });
    }

    // ===== Reload =====

    private void handleReload(CommandSender sender) {
        messages.sendCommand(sender, "reload-processing");

        try {
            boolean success = plugin.reloadPluginConfig();
            if (success) {
                messages.sendCommand(sender, "reload-success");
                plugin.getLogger().info("[ADMIN] " + sender.getName() + " reloaded the configuration");
            } else {
                messages.sendCommand(sender, "reload-failed");
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to reload configuration", e);
            messages.sendCommand(sender, "reload-failed");
        }
    }

    // ===== Purge =====

    private void handlePurge(CommandSender sender) {
        messages.sendCommand(sender, "purge-processing");

        api.purgeCorruptedItems().thenAccept(count -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (count > 0) {
                    messages.sendCommand(sender, "purge-success",
                            Placeholder.unparsed("count", String.valueOf(count)));
                    plugin.getLogger().info("[ADMIN] " + sender.getName() + " purged " + count + " corrupted items");
                } else {
                    messages.sendCommand(sender, "purge-none");
                }
            });
        }).exceptionally(throwable -> {
            plugin.getLogger().log(Level.SEVERE, "Failed to purge corrupted items", throwable);
            Bukkit.getScheduler().runTask(plugin, () ->
                    messages.sendCommand(sender, "database-error")
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
            messages.sendCommand(sender, "migrate-same-type",
                    Placeholder.unparsed("type", targetType.toUpperCase()));
            return;
        }

        // Validate MySQL settings if migrating to MySQL
        if ("mysql".equals(targetType)) {
            String host = config.getMysqlHost();
            String database = config.getMysqlDatabase();
            if (host.isBlank() || database.isBlank()
                    || ("localhost".equals(host) && "root".equals(config.getMysqlUsername()) && "password".equals(config.getMysqlPassword()))) {
                messages.sendCommand(sender, "migrate-invalid-target",
                        Placeholder.unparsed("target", targetType.toUpperCase()));
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
            messages.sendCommand(sender, "migrate-warning",
                    Placeholder.unparsed("source", currentType.toUpperCase()),
                    Placeholder.unparsed("target", targetType.toUpperCase()));
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
            messages.sendCommand(sender, "migrate-confirm-expired");
            return;
        }

        executeMigration(sender, currentType, targetType);
    }

    private void executeMigration(CommandSender sender, String sourceType, String targetType) {
        messages.sendCommand(sender, "migrate-started");

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
                        messages.sendCommand(sender, "migrate-progress",
                                Placeholder.unparsed("count", String.valueOf(totalItems)))
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

                    messages.sendCommand(sender, "migrate-success",
                            Placeholder.unparsed("count", String.valueOf(imported)),
                            Placeholder.unparsed("target", targetType.toUpperCase()));

                    plugin.getLogger().info("[ADMIN] " + sender.getName() + " migrated " + imported +
                            " items from " + sourceType.toUpperCase() + " to " + targetType.toUpperCase());
                });

            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Database migration failed", e);
                if (targetStore != null) {
                    targetStore.close();
                }
                Bukkit.getScheduler().runTask(plugin, () ->
                        messages.sendCommand(sender, "migrate-failed")
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
