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

    boolean due(String path, boolean foreground, long elapsed) {
        long base = ListenerPollingPolicy.intervalMs(path, foreground);
        if (base == Long.MAX_VALUE) return false;
        if (failures > 0) return elapsed >= Math.min(300_000L, Math.max(15_000L, base) << failures);
        if ("chats".equals(path)) {
            // Inbox doubles as incoming-call fallback; keep even idle inbox delay bounded.
            return elapsed >= Math.min(foreground ? 15_000L : 20_000L, base << idle);
        }
        if (path.startsWith("chats/") || path.equals("lives") || path.startsWith("lives/")) return elapsed >= base;
        return elapsed >= Math.min(240_000L, base << idle);
    }
}
