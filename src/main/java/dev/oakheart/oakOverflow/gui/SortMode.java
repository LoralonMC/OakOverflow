package dev.oakheart.oakoverflow.gui;

/**
 * Sort modes for the unclaimed items GUI.
 * Players can cycle through these modes using the sort button.
 */
public enum SortMode {

    NEWEST_FIRST,
    OLDEST_FIRST,
    BY_TYPE,
    BY_SOURCE;

    private static final SortMode[] VALUES = values();

    /**
     * Returns the next sort mode in the cycle.
     */
    public SortMode next() {
        return VALUES[(ordinal() + 1) % VALUES.length];
    }
}
