package com.friendbook.social.core;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Account-scoped on-bind hydration budget; explicit user opens can refresh. */
final class AdSocialReadGate {
    static final long RETRY_MS = 30_000;
    static final class Attempt {
        final String actor, document;
        boolean pending = true, settled;
        long retryAt;
        Attempt(String actor, String document) { this.actor=actor; this.document=document; }
    }
    private String actor;
    private boolean initialized;
    private final Map<String, Attempt> attempts = new HashMap<>();

    synchronized boolean scope(String currentActor) {
        if (!initialized || !Objects.equals(actor,currentActor)) {
            initialized=true; actor=currentActor; attempts.clear(); return true;
        }
        return false;
    }

    synchronized Attempt begin(String currentActor, String document, long now, boolean explicit) {
        scope(currentActor);
        if (actor==null || actor.isEmpty()) return null;
        Attempt previous=attempts.get(document);
        if (previous!=null && (previous.pending || (!explicit && (previous.settled || now<previous.retryAt)))) return null;
        Attempt attempt=new Attempt(actor,document);
        attempts.put(document,attempt);
        return attempt;
    }

    synchronized boolean current(String currentActor, Attempt attempt) {
        scope(currentActor);
        return Objects.equals(actor,attempt.actor) && attempts.get(attempt.document)==attempt;
    }

    synchronized boolean finish(String currentActor, Attempt attempt, boolean settled, long now) {
        if (!current(currentActor,attempt)) return false;
        attempt.pending=false;
        attempt.settled=settled;
        attempt.retryAt=settled?0:now+RETRY_MS;
        return true;
    }
}
