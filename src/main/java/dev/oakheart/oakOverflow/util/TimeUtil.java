package dev.oakheart.oakoverflow.util;

/**
 * Shared time formatting utility.
 */
public class TimeUtil {

    private TimeUtil() {}

    /**
     * Formats a duration in milliseconds into a human-readable relative time string.
     *
     * @param durationMs Duration in milliseconds
     * @return Formatted string like "3 days ago", "1 hour ago", or "Just now"
     */
    public static String formatDuration(long durationMs) {
        if (durationMs <= 0) return "N/A";

        long days = durationMs / (24 * 60 * 60 * 1000L);
        if (days > 0) return days + (days == 1 ? " day ago" : " days ago");

        long hours = durationMs / (60 * 60 * 1000L);
        if (hours > 0) return hours + (hours == 1 ? " hour ago" : " hours ago");

        long minutes = durationMs / (60 * 1000L);
        if (minutes > 0) return minutes + (minutes == 1 ? " minute ago" : " minutes ago");

        return "Just now";
    }
}
