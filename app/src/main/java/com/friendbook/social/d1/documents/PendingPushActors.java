package com.friendbook.social.d1.documents;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** One pending notification-list refresh per account; unrelated accounts stay independent. */
final class PendingPushActors {
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    boolean begin(String actor) { return actor != null && !actor.isEmpty() && pending.add(actor); }
    void finish(String actor) { if (actor != null) pending.remove(actor); }
}
