package com.friendbook.social.core;

/**
 * On-demand budgets for the screens that used to fetch on every tab switch or on
 * every delivered chat message. Pure Java so the rules stay unit-testable.
 */
public final class OnDemandLoads {
    private OnDemandLoads() { }

    /**
     * Chat badge read (one-shot at launch): at most this many rooms. QUOTA: the badge is a pill
     * showing "99+", so reading 100 rooms to paint it was pure waste; pushes bump it locally.
     */
    public static final int CHAT_BADGE_LIMIT = 20;

    /** Notification and friend-request badges already use a small window. */
    public static final int BADGE_WINDOW = 32;

    /**
     * Friend graph used for feed privacy. Bounded so a huge account cannot force
     * an unbounded read on every cold start.
     */
    public static final int FRIEND_GRAPH_LIMIT = 500;

    /** "People you may know" is served from cache for this long before any refetch. */
    public static final long PYMK_CACHE_MS = 30 * 60 * 1000L;

    /** Suggestions and notes are fetched at most once per process unless forced. */
    public static boolean pymkShouldFetch(String actor, String cachedActor, long lastFetchMs,
                                         long nowMs, boolean forced, boolean alreadyLoaded) {
        if (actor == null || actor.isEmpty()) return false;
        if (forced) return true;
        if (!actor.equals(cachedActor)) return true;      // first load for this account
        if (alreadyLoaded) return false;                   // this process already has it
        return nowMs < lastFetchMs || nowMs - lastFetchMs >= PYMK_CACHE_MS;
    }

    /** Keep a healthy account-scoped registration; age alone never requires a new read. */
    public static boolean profileListenerNeedsRestart(String actor,String registeredActor,
                                                      boolean registered,boolean failed) {
        return actor!=null && !actor.isEmpty()
                && (!registered || failed || !actor.equals(registeredActor));
    }

    /** A tab switch may repaint from cache but must never start a network read. */
    public static boolean pymkShouldFetchOnTabReturn(boolean forced) {
        return forced;
    }
}
