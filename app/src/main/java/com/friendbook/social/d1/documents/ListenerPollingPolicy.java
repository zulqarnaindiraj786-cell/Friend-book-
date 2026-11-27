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
    static boolean documentCacheFresh(String path,long nowElapsedMs,long fetchedAtElapsedMs) {
        long age=cacheFreshMs(path);
        return age>0 && fetchedAtElapsedMs>0 && nowElapsedMs>=fetchedAtElapsedMs
                && nowElapsedMs-fetchedAtElapsedMs<age;
    }

    static long cacheFreshMs(String path) {
        if (path == null) return 0;
        if (path.equals("lives") || path.startsWith("lives/")) return 0;
        if (path.startsWith("chats/") && path.indexOf('/', 6) < 0) return 0;
        if (path.startsWith("users/")) return 300_000L;
        if (path.startsWith("posts/") || path.startsWith("reels/")) return 120_000L;
        return 60_000;
    }

    static boolean due(String path, boolean foreground, long elapsedMs) {
        return elapsedMs >= intervalMs(path, foreground);
    }

    /** A lifecycle resume is a demand, but not a reason to reread a still-fresh listener. */
    static boolean foregroundRefreshDue(String path, long nowElapsedMs, long lastAttemptElapsedMs) {
        long freshMs = cacheFreshMs(path);
        return freshMs == 0 || lastAttemptElapsedMs <= 0 || nowElapsedMs < lastAttemptElapsedMs
                || nowElapsedMs - lastAttemptElapsedMs >= freshMs;
    }

    static boolean isChatMessages(String path) {
        if(path==null) return false;
        String[] parts=path.split("/",-1);
        return parts.length==3 && "chats".equals(parts[0]) && !parts[1].isEmpty() && "messages".equals(parts[2]);
    }
}
