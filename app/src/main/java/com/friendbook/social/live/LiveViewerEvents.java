package com.friendbook.social.live;

import java.util.Set;

/** Apply each immutable chat membership packet once, not on every snapshot replay. */
final class LiveViewerEvents {
    private LiveViewerEvents() { }

    static boolean apply(Set<String> viewers, String uid, boolean joined, boolean newPacket) {
        if (!newPacket || uid == null || uid.isEmpty()) return false;
        return joined ? viewers.add(uid) : viewers.remove(uid);
    }
}
