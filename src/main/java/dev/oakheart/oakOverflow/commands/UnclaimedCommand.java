package dev.oakheart.oakoverflow.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.oakheart.oakoverflow.OakOverflow;
import dev.oakheart.oakoverflow.api.OakOverflowAPI;
import dev.oakheart.oakoverflow.config.ConfigManager;
import dev.oakheart.oakoverflow.gui.UnclaimedItemsGUI;
import dev.oakheart.oakoverflow.managers.ClaimManager;
import dev.oakheart.oakoverflow.message.MessageManager;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.logging.Level;

@SuppressWarnings("UnstableApiUsage")
public class UnclaimedCommand {

    private final OakOverflow plugin;
    private final OakOverflowAPI api;
    private final ConfigManager config;
    private final MessageManager messages;
    private final ClaimManager claimManager;

    public UnclaimedCommand(OakOverflow plugin, OakOverflowAPI api, ConfigManager config,
                           MessageManager messages, ClaimManager claimManager) {
        this.plugin = plugin;
        this.api = api;
        this.config = config;
        this.messages = messages;
        this.claimManager = claimManager;
    }

    public void register() {
        LifecycleEventManager<Plugin> manager = plugin.getLifecycleManager();
        manager.registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Commands commands = event.registrar();
            commands.register(buildCommand(), "Manage your overflow storage items", List.of("of", "unclaimed", "uc"));
        });
    }

    private LiteralCommandNode<CommandSourceStack> buildCommand() {
        return Commands.literal("overflow")
                .requires(src -> src.getSender() instanceof Player
                        && src.getSender().hasPermission("oakoverflow.use"))
                // Base command - open GUI
                .executes(ctx -> {
                    openGUI((Player) ctx.getSource().getSender());
                    return Command.SINGLE_SUCCESS;
                })
                // help
                .then(Commands.literal("help")
                        .executes(ctx -> {
                            sendHelp((Player) ctx.getSource().getSender());
                            return Command.SINGLE_SUCCESS;
                        }))
                // list
                .then(Commands.literal("list")
                        .executes(ctx -> {
                            openGUI((Player) ctx.getSource().getSender());
                            return Command.SINGLE_SUCCESS;
                        }))
                // count
                .then(Commands.literal("count")
                        .executes(ctx -> {
                            handleCount((Player) ctx.getSource().getSender());
                            return Command.SINGLE_SUCCESS;
                        }))
                // claimall
                .then(Commands.literal("claimall")
                        .executes(ctx -> {
                            handleClaimAll((Player) ctx.getSource().getSender());
                            return Command.SINGLE_SUCCESS;
                        }))
                .build();
    }

    private void sendHelp(Player player) {
        messages.getMessage("commands.player-help-header").ifPresent(player::sendMessage);
        messages.getMessage("commands.player-help-open").ifPresent(player::sendMessage);
        messages.getMessage("commands.player-help-count").ifPresent(player::sendMessage);
        messages.getMessage("commands.player-help-claimall").ifPresent(player::sendMessage);
        messages.getMessage("commands.player-help-footer").ifPresent(player::sendMessage);
    }

    private void openGUI(Player player) {
        UnclaimedItemsGUI gui = UnclaimedItemsGUI.forPlayer(plugin, player, api, config, claimManager, messages);
        gui.open();
    }

    private void handleCount(Player player) {
        api.getUnclaimedCount(player).thenAccept(count -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (count == 0) {
                    messages.send(player, "no-unclaimed-items");
                } else {
                    messages.send(player, "items-count", "count", String.valueOf(count));
                }
            });
        }).exceptionally(throwable -> {
            plugin.getLogger().log(Level.SEVERE, "Failed to get unclaimed count for " + player.getName(), throwable);
            Bukkit.getScheduler().runTask(plugin, () ->
                    messages.getMessage("commands.database-error").ifPresent(player::sendMessage)
            );
            return null;
        });
    }

    private void handleClaimAll(Player player) {
        api.getUnclaimedItems(player).thenAccept(items -> {
            if (items.isEmpty()) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        messages.send(player, "no-unclaimed-items")
                );
                return;
            }

            Bukkit.getScheduler().runTask(plugin, () -> claimManager.claimAll(player, items));
        }).exceptionally(throwable -> {
            plugin.getLogger().log(Level.SEVERE, "Failed to get unclaimed items for claimall", throwable);
            Bukkit.getScheduler().runTask(plugin, () ->
                    messages.getMessage("commands.database-error").ifPresent(player::sendMessage)
            );
            return null;
        });
    }
}
