package com.friendbook.social.post;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Per-adapter cosmetic author lookups. A missing profile is a result, not a retry loop. */
final class CommentAuthorLookupGate {
    private String actor;
    private long generation;
    private final Map<String, Long> nextAttempt = new HashMap<>();
    private static final long RETRY_MS = 10_000L;

    long begin(String currentActor, String author, long now) {
        if (!Objects.equals(actor, currentActor)) {
            actor = currentActor;
            generation++;
            nextAttempt.clear();
        }
        if (actor == null || author == null || author.isEmpty()) return -1;
        Long next = nextAttempt.get(author);
        if (next != null && now < next) return -1;
        nextAttempt.put(author, Long.MAX_VALUE);
        return generation;
    }

    boolean finish(String currentActor, String author, long ticket, boolean success, long now) {
        if (!Objects.equals(actor, currentActor) || ticket != generation) return false;
        nextAttempt.put(author, success ? Long.MAX_VALUE : now + RETRY_MS);
        return true;
    }
}
