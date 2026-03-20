package dev.oakheart.oakoverflow.message;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Manages MiniMessage template resolution for all player-facing messages.
 * <p>
 * Message routing:
 * <ul>
 *   <li>Gameplay messages (e.g., "no-unclaimed-items") → {@code messages.<key>.text} with {@code messages.<key>.display}</li>
 *   <li>Command messages (e.g., "commands.reload-success") → flat string at {@code messages.<key>}</li>
 *   <li>GUI messages (e.g., "gui.item-lore") → flat string/list at {@code messages.<key>}</li>
 * </ul>
 * <p>
 * The {@code <prefix>} tag is automatically resolved in all messages via {@code Placeholder.parsed()}.
 * User-supplied values use {@code Placeholder.unparsed()} to prevent MiniMessage injection.
 */
public class MessageManager {

    private final JavaPlugin plugin;
    private final Logger logger;
    private final MiniMessage miniMessage;
    private final Map<String, Component> cache;

    private FileConfiguration config;

    public MessageManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.miniMessage = MiniMessage.miniMessage();
        this.cache = new ConcurrentHashMap<>();
    }

    /**
     * Sets the config reference and clears the cache.
     * Called after ConfigManager loads or reloads.
     */
    public void setConfig(FileConfiguration config) {
        this.config = config;
        this.cache.clear();
    }

    // ========================================
    //          Core Message Methods
    // ========================================

    /**
     * Gets a message with no dynamic placeholders.
     * Returns {@link Optional#empty()} if the message is blank (disabled by admin).
     *
     * @param key Message key (e.g., "no-unclaimed-items", "commands.reload-success")
     * @return Parsed Component, or empty if the message is disabled
     */
    public Optional<Component> getMessage(String key) {
        Component cached = cache.get(key);
        if (cached != null) {
            return Optional.of(cached);
        }

        String raw = getMessageString(key);
        if (raw.isEmpty()) {
            return Optional.empty();
        }

        try {
            TagResolver prefixResolver = Placeholder.parsed("prefix", getRawPrefix());
            Component component = miniMessage.deserialize(raw, prefixResolver);
            cache.put(key, component);
            return Optional.of(component);
        } catch (Exception e) {
            logger.warning("Invalid MiniMessage syntax in messages." + key + ": " + e.getMessage());
            Component fallback = Component.text(raw);
            cache.put(key, fallback);
            return Optional.of(fallback);
        }
    }

    /**
     * Gets a message with a map of placeholder replacements.
     * User values use {@code Placeholder.unparsed()} for safety.
     *
     * @param key Message key
     * @param placeholders Map of placeholder name → value
     * @return Parsed Component, or empty if the message is disabled
     */
    public Optional<Component> getMessage(String key, Map<String, String> placeholders) {
        String raw = getMessageString(key);
        if (raw.isEmpty()) {
            return Optional.empty();
        }

        try {
            TagResolver.Builder builder = TagResolver.builder();
            builder.resolver(Placeholder.parsed("prefix", getRawPrefix()));
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                builder.resolver(Placeholder.unparsed(entry.getKey(), entry.getValue()));
            }
            return Optional.of(miniMessage.deserialize(raw, builder.build()));
        } catch (Exception e) {
            logger.warning("Invalid MiniMessage syntax in messages." + key + ": " + e.getMessage());
            return Optional.of(Component.text(raw));
        }
    }

    /**
     * Gets a message with a single placeholder.
     *
     * @param key Message key
     * @param placeholder Placeholder name
     * @param value Placeholder value
     * @return Parsed Component, or empty if the message is disabled
     */
    public Optional<Component> getMessage(String key, String placeholder, String value) {
        String raw = getMessageString(key);
        if (raw.isEmpty()) {
            return Optional.empty();
        }

        try {
            TagResolver resolver = TagResolver.resolver(
                    Placeholder.parsed("prefix", getRawPrefix()),
                    Placeholder.unparsed(placeholder, value)
            );
            return Optional.of(miniMessage.deserialize(raw, resolver));
        } catch (Exception e) {
            logger.warning("Invalid MiniMessage syntax in messages." + key + ": " + e.getMessage());
            return Optional.of(Component.text(raw));
        }
    }

    // ========================================
    //          Display Mode & Delivery
    // ========================================

    /**
     * Gets the display mode for a gameplay message.
     *
     * @param key Message key (e.g., "item-received")
     * @return "chat" or "action_bar"
     */
    public String getMessageDisplay(String key) {
        return config.getString("messages." + key + ".display", "chat");
    }

    /**
     * Sends a gameplay message to a player, respecting the configured display mode
     * (chat or action_bar). No-ops if the message is disabled (empty).
     *
     * @param player The player to send to
     * @param key Gameplay message key (e.g., "no-unclaimed-items")
     */
    public void send(Player player, String key) {
        getMessage(key).ifPresent(msg -> {
            if ("action_bar".equals(getMessageDisplay(key))) {
                player.sendActionBar(msg);
            } else {
                player.sendMessage(msg);
            }
        });
    }

    /**
     * Sends a gameplay message with a single placeholder to a player,
     * respecting the configured display mode.
     *
     * @param player The player to send to
     * @param key Gameplay message key
     * @param placeholder Placeholder name
     * @param value Placeholder value
     */
    public void send(Player player, String key, String placeholder, String value) {
        getMessage(key, placeholder, value).ifPresent(msg -> {
            if ("action_bar".equals(getMessageDisplay(key))) {
                player.sendActionBar(msg);
            } else {
                player.sendMessage(msg);
            }
        });
    }

    /**
     * Sends a gameplay message with multiple placeholders to a player,
     * respecting the configured display mode.
     *
     * @param player The player to send to
     * @param key Gameplay message key
     * @param placeholders Map of placeholder name to value
     */
    public void send(Player player, String key, Map<String, String> placeholders) {
        getMessage(key, placeholders).ifPresent(msg -> {
            if ("action_bar".equals(getMessageDisplay(key))) {
                player.sendActionBar(msg);
            } else {
                player.sendMessage(msg);
            }
        });
    }

    // ========================================
    //          List Messages (GUI lore)
    // ========================================

    /**
     * Gets a list of message lines with no placeholders.
     *
     * @param key Config path under messages (e.g., "gui.item-lore")
     * @return List of parsed Components
     */
    public List<Component> getMessageList(String key) {
        List<String> lines = getMessageStringList(key);
        TagResolver prefixResolver = Placeholder.parsed("prefix", getRawPrefix());

        return lines.stream()
                .map(line -> miniMessage.deserialize(line, prefixResolver))
                .toList();
    }

    /**
     * Gets a list of message lines with placeholder replacements.
     *
     * @param key Config path under messages (e.g., "gui.item-lore")
     * @param placeholders Map of placeholder name → value
     * @return List of parsed Components
     */
    public List<Component> getMessageList(String key, Map<String, String> placeholders) {
        List<String> lines = getMessageStringList(key);

        TagResolver.Builder builder = TagResolver.builder();
        builder.resolver(Placeholder.parsed("prefix", getRawPrefix()));
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            builder.resolver(Placeholder.unparsed(entry.getKey(), entry.getValue()));
        }
        TagResolver resolver = builder.build();

        return lines.stream()
                .map(line -> miniMessage.deserialize(line, resolver))
                .toList();
    }

    // ========================================
    //          Raw Access
    // ========================================

    /**
     * Gets a raw message string without MiniMessage parsing.
     * Useful when callers need to apply their own TagResolvers.
     *
     * @param key Message key (e.g., "commands.stats-total")
     * @return Raw MiniMessage string from config, or empty if disabled
     */
    public String getRawMessage(String key) {
        return getMessageString(key);
    }

    /**
     * Gets the raw prefix string from config.
     */
    public String getRawPrefix() {
        return config.getString("messages.prefix", "");
    }

    /**
     * Gets the MiniMessage instance for custom parsing.
     */
    public MiniMessage getMiniMessage() {
        return miniMessage;
    }

    // ========================================
    //          Internal Helpers
    // ========================================

    /**
     * Routes a message key to the correct config path.
     * <ul>
     *   <li>"commands.*" or "gui.*" → flat string at messages.{key}</li>
     *   <li>everything else → gameplay message at messages.{key}.text, fallback to messages.{key}</li>
     * </ul>
     */
    private String getMessageString(String key) {
        if (key.startsWith("commands.") || key.startsWith("gui.")) {
            return config.getString("messages." + key, "");
        }

        // Gameplay message — nested text/display structure
        String text = config.getString("messages." + key + ".text", null);
        if (text != null) {
            return text;
        }

        // Fallback: flat string (prefix, etc.)
        return config.getString("messages." + key, "");
    }

    /**
     * Gets a list of strings from the messages section.
     */
    private List<String> getMessageStringList(String key) {
        return config.getStringList("messages." + key);
    }
}
