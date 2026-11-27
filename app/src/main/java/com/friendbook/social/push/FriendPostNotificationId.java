package com.friendbook.social.push;

/** Stable per-author/recipient notification identity in fixed 12-hour epoch windows. */
final class FriendPostNotificationId {
    static final long WINDOW_MS = 12L * 60 * 60 * 1000;
    private FriendPostNotificationId() { }

    static long windowBucket(long nowMs) { return Math.floorDiv(nowMs, WINDOW_MS); }

    static String of(String author, String friend, long bucket) {
        if (author == null || author.isEmpty() || friend == null || friend.isEmpty())
            throw new IllegalArgumentException("Missing friend-post identity");
        return "friend_post_" + author + "_" + friend + "_" + bucket;
    }
}
