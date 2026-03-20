package dev.oakheart.oakoverflow.util;

import org.bukkit.inventory.ItemStack;

import java.util.Base64;

/**
 * Utility class for serializing and deserializing ItemStacks to/from Base64 strings.
 * Uses Bukkit's built-in serialization to preserve all NBT data, enchantments, lore, etc.
 */
public class ItemSerializer {

    private ItemSerializer() {}

    /**
     * Serializes an ItemStack to a Base64 encoded string.
     * Preserves all NBT data, custom model data, enchantments, lore, and metadata.
     *
     * @param item The ItemStack to serialize
     * @return Base64 encoded string representation of the item
     * @throws IllegalArgumentException if item is null
     */
    public static String serialize(ItemStack item) {
        if (item == null) {
            throw new IllegalArgumentException("Cannot serialize null item");
        }

        try {
            byte[] bytes = item.serializeAsBytes();
            return Base64.getEncoder().encodeToString(bytes);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize item: " + item.getType(), e);
        }
    }

    /**
     * Deserializes a Base64 encoded string back into an ItemStack.
     *
     * @param data Base64 encoded string representation of an item
     * @return The deserialized ItemStack
     * @throws IllegalArgumentException if data is null or empty
     * @throws RuntimeException if deserialization fails
     */
    public static ItemStack deserialize(String data) {
        if (data == null || data.isEmpty()) {
            throw new IllegalArgumentException("Cannot deserialize null or empty data");
        }

        try {
            byte[] bytes = Base64.getDecoder().decode(data);
            return ItemStack.deserializeBytes(bytes);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize item data", e);
        }
    }

    /**
     * Checks if the given data string is valid serialized item data.
     *
     * @param data Base64 encoded string to validate
     * @return true if the data can be deserialized, false otherwise
     */
    public static boolean isValid(String data) {
        if (data == null || data.isEmpty()) {
            return false;
        }

        try {
            deserialize(data);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Gets the approximate size of the serialized data in bytes.
     * Useful for detecting oversized items.
     *
     * @param item The ItemStack to check
     * @return Size in bytes of the serialized data
     */
    public static int getSerializedSize(ItemStack item) {
        if (item == null) {
            return 0;
        }

        try {
            return item.serializeAsBytes().length;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Maximum recommended size for serialized items (64KB).
     * Items larger than this may indicate issues or malicious NBT data.
     */
    public static final int MAX_RECOMMENDED_SIZE = 65536; // 64KB

    /**
     * Checks if an item's serialized size exceeds the recommended limit.
     *
     * @param item The ItemStack to check
     * @return true if the item is too large
     */
    public static boolean isOversized(ItemStack item) {
        return getSerializedSize(item) > MAX_RECOMMENDED_SIZE;
    }

    /**
     * Checks if already-serialized data exceeds the recommended size limit.
     * More efficient than isOversized(ItemStack) when you already have the serialized string.
     *
     * @param serializedData The Base64-encoded serialized item data
     * @return true if the data is too large
     */
    public static boolean isOversized(String serializedData) {
        return serializedData != null && serializedData.length() * 3 / 4 > MAX_RECOMMENDED_SIZE;
    }

    /**
     * Gets the size of an already-serialized string.
     *
     * @param serializedData The Base64-encoded serialized item data
     * @return Size in characters
     */
    public static int getSerializedSize(String serializedData) {
        return serializedData != null ? serializedData.length() : 0;
    }
}
