package com.friendbook.social.d1.documents;

/**
 * Nothing polls. Live rooms, call signaling, chat lists and profiles are all
 * push-driven (FCM invalidation) or refreshed on demand by the visible screen,
 * so the periodic interval for every path is "never".
 */
final class ListenerPollingPolicy {
    private ListenerPollingPolicy() { }

    static long intervalMs(String path, boolean foreground) { return Long.MAX_VALUE; }

    /**
     * How long a cached snapshot may be painted without a network read.
     * Live rooms and an open call room stay fresh; everything else is served
     * from cache and refreshed only on an explicit open, refresh or push.
     */
    static long cacheFreshMs(String path) {
        if (path == null) return 0;
        if (path.equals("lives") || path.startsWith("lives/")) return 0;
        if (path.startsWith("chats/") && path.indexOf('/', 6) < 0) return 0;
        return 60_000;
    }

    static boolean due(String path, boolean foreground, long elapsedMs) {
        return elapsedMs >= intervalMs(path, foreground);
    }

    static boolean isChatMessages(String path) {
        if(path==null) return false;
        String[] parts=path.split("/",-1);
        return parts.length==3 && "chats".equals(parts[0]) && !parts[1].isEmpty() && "messages".equals(parts[2]);
    }
}
