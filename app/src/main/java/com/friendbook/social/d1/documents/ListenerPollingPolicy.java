package com.friendbook.social.d1.documents;

/** Chat messages already have their own faster schedule; never also poll them on the general tick. */
final class ListenerPollingPolicy {
    private ListenerPollingPolicy() { }
    static long intervalMs(String path, boolean foreground) {
        if (path == null) return Long.MAX_VALUE;
        boolean chatRoom = "chats".equals(path) || (path.startsWith("chats/") && path.indexOf('/', 6) < 0);
        if (chatRoom) return foreground ? 5_000 : 10_000; // Ringing still uses the inbox, not FCM.
        if (!foreground) return Long.MAX_VALUE; // Explicit writes/FCM bypass the periodic schedule.
        if (isChatMessages(path)) return 2_000;
        if (path.equals("lives") || path.startsWith("lives/")) return 5_000;
        if (path.equals("notifications") || path.equals("friend_requests") || path.equals("stories")) return 15_000;
        if (path.startsWith("config/")) return 60_000;
        return 30_000;
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
