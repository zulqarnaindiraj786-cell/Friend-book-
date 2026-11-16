package com.friendbook.social.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure change-detection for a re-read page. No Android types, no Handler, no network: given
 * the previous window and the newly polled one, it says what actually changed.
 *
 * <p>Kept separate from its callers so the rules that decide whether a feed re-renders can
 * be tested without a Looper, and so a bug here cannot be masked by a bug there.
 */
public final class DocDelta {

    private DocDelta() {}

    /** What changed between two polls. */
    public static final class Result {
        public final List<Map<String, Object>> added;
        public final List<Map<String, Object>> updated;
        public final List<String> removed;
        public final List<Map<String, Object>> documents;

        Result(List<Map<String, Object>> added, List<Map<String, Object>> updated,
               List<String> removed, List<Map<String, Object>> documents) {
            this.added = added;
            this.updated = updated;
            this.removed = removed;
            this.documents = documents;
        }

        public boolean changed() {
            return !added.isEmpty() || !updated.isEmpty() || !removed.isEmpty();
        }
    }

    /**
     * Stable identity for a document. The store always sets {@code id}; falling back to the
     * last segment of {@code path} keeps a document from looking new on every poll.
     */
    public static String idOf(Map<String, Object> doc) {
        if (doc == null) return null;
        Object id = doc.get("id");
        if (id instanceof String && !((String) id).isEmpty()) return (String) id;
        Object path = doc.get("path");
        if (path instanceof String) {
            String p = (String) path;
            int slash = p.lastIndexOf('/');
            if (slash >= 0 && slash + 1 < p.length()) return p.substring(slash + 1);
        }
        return null;
    }

    /**
     * Deterministic text for a document. Two documents with the same content must produce the
     * same signature regardless of map iteration order, otherwise every poll would look like an
     * update. Keys are sorted for exactly that reason.
     */
    public static String signature(Object value) {
        StringBuilder sb = new StringBuilder();
        append(sb, value);
        return sb.toString();
    }

    private static void append(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            List<String> keys = new ArrayList<>();
            for (Object k : map.keySet()) if (k != null) keys.add(String.valueOf(k));
            Collections.sort(keys);
            sb.append('{');
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(quote(keys.get(i))).append(':');
                append(sb, map.get(keys.get(i)));
            }
            sb.append('}');
        } else if (value instanceof List) {
            // Order matters in a list: [a,b] and [b,a] really are different values.
            sb.append('[');
            boolean first = true;
            for (Object item : (List<?>) value) {
                if (!first) sb.append(',');
                first = false;
                append(sb, item);
            }
            sb.append(']');
        } else if (value instanceof Boolean || value instanceof Number) {
            sb.append(value);
        } else {
            sb.append(quote(String.valueOf(value)));
        }
    }

    private static String quote(String s) {
        StringBuilder out = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default: out.append(c);
            }
        }
        return out.append('"').toString();
    }

    /**
     * Compares the previously seen window to a freshly polled one.
     *
     * @param previous signatures of the last delivery, keyed by document id
     * @param documents the new window, in query order
     */
    public static Result compute(Map<String, String> previous, List<Map<String, Object>> documents) {
        List<Map<String, Object>> added = new ArrayList<>();
        List<Map<String, Object>> updated = new ArrayList<>();
        List<Map<String, Object>> current = new ArrayList<>();
        Map<String, String> seen = new LinkedHashMap<>();

        for (Map<String, Object> doc : documents) {
            if (doc == null) continue;
            String id = idOf(doc);
            // A document with no identity cannot be tracked; adding it every poll would make
            // the feed look permanently busy.
            if (id == null) continue;
            current.add(doc);
            String sig = signature(doc);
            seen.put(id, sig);
            String before = previous == null ? null : previous.get(id);
            if (before == null) added.add(doc);
            else if (!before.equals(sig)) updated.add(doc);
        }

        List<String> removed = new ArrayList<>();
        if (previous != null) {
            for (String id : previous.keySet()) {
                if (!seen.containsKey(id)) removed.add(id);
            }
        }
        return new Result(added, updated, removed, current);
    }
}
