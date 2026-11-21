package com.friendbook.social.core;

/** Shared pure policy for rows, active strip, chat headers and profiles. */
public final class PresencePolicy {
    private PresencePolicy() {}
    public static final long ONLINE_TTL_MS = 300_000L; // 5 minutes (300 seconds)
    // Profile fallback only: two intervals fit inside the TTL, including a missed renewal.
    public static final long PROFILE_HEARTBEAT_MS = 120_000L;
    public static final long TYPING_IDLE_CLEAR_MS = 4_000L;

    public static long typingPollMs(boolean peerTyping) {
        // Keep the poll shorter than the client clear timer so short bursts remain observable.
        return peerTyping ? 3_000L : 3_500L;
    }

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
