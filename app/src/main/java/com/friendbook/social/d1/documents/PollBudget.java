package com.friendbook.social.d1.documents;

/** Backs off periodic reads only. Explicit refreshes, writes and push invalidations bypass it. */
final class PollBudget {
    private volatile int idle;
    private volatile int failures;

    void success(boolean changed) {
        failures = 0;
        idle = changed ? 0 : Math.min(3, idle + 1);
    }

    void failure() {
        idle = 0;
        failures = Math.min(5, failures + 1);
    }

    boolean chatFallbackDue(long elapsed, boolean openChat) {
        if (failures > 0) return elapsed >= Math.min(120_000L, 5_000L << failures);
        // Quiet sessions back off, but stop short of a long stale interval.
        return elapsed >= Math.min(openChat ? 20_000L : 40_000L, (openChat ? 5_000L : 10_000L) << idle);
    }

    boolean due(String path, boolean foreground, long elapsed) {
        return due(path, foreground, elapsed, false);
    }

    boolean due(String path, boolean foreground, long elapsed, boolean visibleList) {
        long base = ListenerPollingPolicy.intervalMs(path, foreground);
        if (base == Long.MAX_VALUE) return false;
        if (failures > 0) return elapsed >= Math.min(300_000L, Math.max(15_000L, base) << failures);
        // Live discovery is a list, not active-room signaling. After unchanged
        // responses back off from 10s to 20s, returning to 10s after a change.
        // Explicit invalidations/foreground refresh still bypass this budget.
        if (path.equals("lives")) return elapsed >= Math.min(20_000L, base << idle);
        // Keep an open live room/chat and call signaling at their existing cadence.
        if (path.startsWith("chats/") || path.startsWith("lives/")) return elapsed >= base;
        return elapsed >= Math.min(240_000L, base << idle);
    }
}
