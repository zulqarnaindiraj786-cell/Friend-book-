package com.friendbook.social.core;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded local HTTP-attempt counts. Never retains URLs, IDs, bodies or credentials. */
final class RequestWindowCounter {
    static final long WINDOW_MS = 60_000;
    private long deadline;
    private boolean active;
    private final Map<String, Integer> counts = new LinkedHashMap<>();

    synchronized boolean begin(long now) {
        if (active) return false; // Activity recreation must not reset the window.
        active = true;
        deadline = now + WINDOW_MS;
        counts.clear();
        return true;
    }

    synchronized void record(long now, String method, String route, String resource) {
        if (!active || now >= deadline || now < deadline - WINDOW_MS) return;
        String category;
        if ("/v1/firestore/document".equals(route)) {
            if ("GET".equals(method)) {
                String root = resource == null ? "" : resource.split("/", 2)[0];
                switch (root) {
                    case "users": case "posts": case "reels": case "chats":
                    case "notifications": case "groups": case "stories":
                        category = "document_read_" + root; break;
                    default: category = "document_read_other";
                }
            } else category = "document_write";
        } else if ("/v1/firestore/query".equals(route)) category = "document_query";
        else if ("/v1/firestore/count".equals(route)) category = "document_count";
        else if ("/v1/firestore/batch".equals(route)) category = "document_batch";
        else category = "other_api";
        counts.put(category, counts.getOrDefault(category, 0) + 1);
    }

    synchronized Map<String, Integer> finish() {
        Map<String, Integer> result = new LinkedHashMap<>(counts);
        active = false;
        counts.clear();
        return result;
    }
}
