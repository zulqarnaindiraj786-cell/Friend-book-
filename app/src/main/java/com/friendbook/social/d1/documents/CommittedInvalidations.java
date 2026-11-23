package com.friendbook.social.d1.documents;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** One atomic commit makes each document and parent collection stale once. */
final class CommittedInvalidations {
    private final LinkedHashSet<String> documents = new LinkedHashSet<>();
    private final LinkedHashSet<String> collections = new LinkedHashSet<>();

    void add(String path) {
        if (path == null || path.isEmpty() || !documents.add(path)) return;
        int index = path.lastIndexOf('/');
        if (index > 0) collections.add(path.substring(0, index));
    }

    Set<String> documents() { return Collections.unmodifiableSet(documents); }
    Set<String> collections() { return Collections.unmodifiableSet(collections); }
}
