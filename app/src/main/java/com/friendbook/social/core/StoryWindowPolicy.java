package com.friendbook.social.core;

/** Refresh the expiry anchor only on visible demand, never from a timer. */
public final class StoryWindowPolicy {
    private StoryWindowPolicy() { }
    public static final long REUSE_MS=FeedReadPolicy.REUSE_MS;
    public static boolean fresh(long anchor,long now) {
        return anchor>0 && now>=anchor && now-anchor<REUSE_MS;
    }
    public static long anchor(long previous,long now) {
        return fresh(previous,now)?previous:now;
    }
}
