package dev.oakheart.oakoverflow.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * Utility class for getting user-friendly item names.
 * Uses Paper's {@code ItemStack.displayName()} which returns the custom display name
 * if set, otherwise the vanilla translated name. Falls back to formatted material name.
 */
public class ItemNameUtil {

    private ItemNameUtil() {}

    /**
     * Gets the display name of an item.
     * {@code ItemStack.displayName()} on modern Paper always returns a non-null Component
     * representing either the custom display name or the vanilla translated name.
     *
     * @param item The ItemStack to get the name for
     * @return A user-friendly display name for the item
     */
    public static String getItemDisplayName(ItemStack item) {
        if (item == null) {
            return "Unknown Item";
        }

        // displayName() is @NotNull on modern Paper — returns custom name or vanilla translated name
        Component displayName = item.displayName();
        String plainName = PlainTextComponentSerializer.plainText().serialize(displayName);
        if (!plainName.trim().isEmpty()) {
            return cleanItemName(plainName.trim());
        }

        // Fallback: Manual formatting only if everything else fails
        return formatMaterialName(item.getType());
    }

    /**
     * Cleans item name by removing unwanted brackets and formatting.
     *
     * @param name The name to clean
     * @return The cleaned name
     */
    private static String cleanItemName(String name) {
        if (name == null) {
            return "";
        }

        // Remove surrounding square brackets if present (common in some plugins)
        String cleaned = name.trim();
        if (cleaned.startsWith("[") && cleaned.endsWith("]")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }

        return cleaned;
    }

    /**
     * Formats a material type into a readable name.
     * Converts DIAMOND_SWORD to "Diamond Sword"
     *
     * @param material The material to format
     * @return A formatted, human-readable name
     */
    public static String formatMaterialName(Material material) {
        if (material == null) {
            return "Unknown";
        }

        String[] words = material.toString().toLowerCase().split("_");
        StringBuilder result = new StringBuilder();

        for (int i = 0; i < words.length; i++) {
            if (i > 0) {
                result.append(" ");
            }
            // Capitalize first letter of each word
            String word = words[i];
            result.append(Character.toUpperCase(word.charAt(0)))
                  .append(word.substring(1));
        }

        return result.toString();
    }
}
