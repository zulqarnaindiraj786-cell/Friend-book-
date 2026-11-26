package com.friendbook.social.core;

/** Visible feed head reuse; explicit refresh and mutation invalidation stay authoritative. */
public final class FeedReadPolicy {
    private FeedReadPolicy(){}
    public static final long REUSE_MS=120_000L;
    public static final long FEED_REUSE_MS=60_000L;
}
