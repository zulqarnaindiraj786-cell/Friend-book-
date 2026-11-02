package com.friendbook.social.core;

/** Epoch milliseconds. Keep days (including 7d/14d), never silently change to weeks. */
public final class TimeAgoUtil {
    private TimeAgoUtil() {}
    public static String formatLastSeen(long timestamp) { return formatLastSeen(timestamp, System.currentTimeMillis()); }
    public static String formatLastSeen(long timestamp, long now) {
        String shortTime = formatShortLastSeen(timestamp, now);
        return shortTime.isEmpty() ? "" : "now".equals(shortTime) ? "Active now" : "Active " + shortTime + " ago";
    }
    public static String formatShortLastSeen(long timestamp, long now) {
        if (timestamp <= 0) return "";
        long minutes = Math.max(0L, now - timestamp) / 60_000L;
        if (minutes < 1) return "now";
        if (minutes < 60) return minutes + "m";
        long hours = minutes / 60;
        if (hours < 24) return hours + "h";
        return (hours / 24) + "d";
    }
}
