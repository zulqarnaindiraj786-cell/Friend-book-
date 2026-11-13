package com.friendbook.social.core;

/** Shared pure policy for rows, active strip, chat headers and profiles. */
public final class PresencePolicy {
    private PresencePolicy() {}
    public static final long ONLINE_TTL_MS = 10_000L; // 10 seconds - instant offline on app kill
    public static boolean isFreshOnline(boolean active, long seen, long now) {
        long age = now - seen;
        return active && seen > 0 && age >= -30_000L && age < ONLINE_TTL_MS;
    }
    public static boolean resolve(boolean allowed, boolean rtdbKnown, boolean rtdbActive, long rtdbSeen,
                                  boolean fsServerKnown, boolean fsActive, long fsSeen, long now) {
        if (!allowed) return false;
        // Once RTDB answers (even offline/missing), a stale Firestore online flag MUST NOT resurrect a dot.
        if (rtdbKnown) return isFreshOnline(rtdbActive, rtdbSeen, now);
        return fsServerKnown && isFreshOnline(fsActive, fsSeen, now);
    }
}
