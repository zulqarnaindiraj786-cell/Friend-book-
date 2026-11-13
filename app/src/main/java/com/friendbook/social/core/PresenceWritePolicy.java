package com.friendbook.social.core;

/** Process-local write coalescing, not a presence cache or per-user rate limit. */
public final class PresenceWritePolicy {
    public static final long ONLINE_WRITE_INTERVAL_MS = 5_000L; // 5s - must be < ONLINE_TTL_MS (300s)
    private String actor;
    private long generation, lastSuccess;
    private boolean pendingOnline, hasSuccess;

    public synchronized long begin(String uid, boolean online, long elapsed) {
        if (uid == null || uid.isEmpty()) return 0;
        if (!uid.equals(actor)) {
            actor = uid;
            hasSuccess = false;
            pendingOnline = false;
        }
        if (online && (pendingOnline || (hasSuccess && elapsed >= lastSuccess
                && elapsed - lastSuccess < ONLINE_WRITE_INTERVAL_MS))) return 0;
        // Offline/privacy changes always go through, invalidate pending online callbacks,
        // and allow a later foreground session to publish online immediately.
        if (!online) hasSuccess = false;
        pendingOnline = online;
        return ++generation;
    }

    public synchronized void complete(long ticket, boolean success, long elapsed) {
        if (ticket != generation) return;
        if (pendingOnline && success) {
            lastSuccess = elapsed;
            hasSuccess = true;
        }
        pendingOnline = false;
    }
}
