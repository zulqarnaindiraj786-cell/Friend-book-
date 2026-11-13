package com.friendbook.social.core;

import java.util.Objects;

/** Experimental lifecycle policy only: not wired into App/Presence or a socket transport. */
public final class PresenceSessionPolicy {
    private String uid = "";
    private boolean foreground, visible, pending, connected;
    private long generation, openedAt, refreshAt;
    private int failures;

    /** Caller must close/cancel its transport when true; old callbacks are invalid regardless. */
    public synchronized boolean configure(String actor, boolean inForeground, boolean activeStatus) {
        String next = actor == null ? "" : actor;
        if (Objects.equals(uid, next) && foreground == inForeground && visible == activeStatus) return false;
        boolean close = pending || connected;
        uid = next; foreground = inForeground; visible = activeStatus;
        pending = false; connected = false; openedAt = 0; refreshAt = 0; failures = 0;
        generation++;
        return close;
    }
    private boolean eligible() { return !uid.isEmpty() && foreground && visible; }
    public synchronized long generation() { return generation; }
    public synchronized boolean retryIsCurrent(long ticket) {
        return ticket == generation && eligible() && !pending && !connected;
    }
    public synchronized long begin() {
        if (!eligible() || pending || connected) return 0;
        pending = true;
        return ++generation;
    }
    /** No token bytes retained here. Adapter supplies remaining verified token lifetime. */
    public synchronized boolean opened(long ticket, long leaseMillis, long elapsed) {
        if (ticket != generation || !pending || !eligible()) return false;
        if (elapsed < 0 || leaseMillis <= 30_000L || leaseMillis > 3_600_000L || elapsed > Long.MAX_VALUE - leaseMillis) {
            pending = false; generation++; return false;
        }
        pending = false; connected = true; failures = 0;
        openedAt = elapsed; refreshAt = elapsed + leaseMillis - 30_000L;
        return true;
    }
    public synchronized boolean canSend(long ticket, long elapsed) {
        return ticket == generation && connected && eligible() && elapsed >= openedAt && elapsed < refreshAt;
    }
    public synchronized boolean needsRefresh(long elapsed) {
        return connected && eligible() && (elapsed < openedAt || elapsed >= refreshAt);
    }
    /** Retry delay only; scheduler/adapter must recheck retryIsCurrent before opening. */
    public synchronized long failed(long ticket) {
        if (ticket != generation || !eligible()) return -1;
        pending = false; connected = false; refreshAt = 0; generation++;
        int exponent = Math.min(failures++, 6);
        // Saturate the counter too, so a long offline session cannot overflow it.
        failures = Math.min(failures, 7);
        return Math.min(60_000L, 1_000L << exponent);
    }
}
