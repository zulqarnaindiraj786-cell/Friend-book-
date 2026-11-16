package com.friendbook.social.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A D1 row, readable with the same field accessors the screens already use on Firestore
 * documents.
 *
 * <p>It exists so moving a screen off Firestore does not also mean rewriting every line that
 * reads a field: {@code doc.getString("status")} keeps working. Nothing here can reach a
 * network - which is the point, because a screen that can still call {@code get()} is a screen
 * that still depends on Firestore.
 *
 * <p>There is no {@code getReference()}. A reference is a Firestore handle, so a call site that
 * needs one has a write it has not yet moved; better a compile error there than a write that
 * silently goes only to Firestore.
 */
public final class Doc {

    private final String id;
    private final Map<String, Object> data;

    private Doc(String id, Map<String, Object> data) {
        this.id = id;
        this.data = data;
    }

    public static Doc of(String id, Map<String, Object> data) {
        return new Doc(id, data);
    }

    public static List<Doc> ofAll(List<Map<String, Object>> rows) {
        List<Doc> out = new ArrayList<>();
        if (rows == null) return out;
        for (Map<String, Object> row : rows) {
            if (row != null) out.add(new Doc(Format.str(row, "id"), row));
        }
        return out;
    }

    public String getId() { return id; }

    public boolean exists() { return data != null; }

    public Map<String, Object> getData() { return data; }

    public Object get(String field) { return data == null ? null : data.get(field); }

    public String getString(String field) { return Format.str(data, field); }

    public Long getLong(String field) {
        Object v = data == null ? null : data.get(field);
        return v instanceof Number ? ((Number) v).longValue() : null;
    }

    public Double getDouble(String field) {
        Object v = data == null ? null : data.get(field);
        return v instanceof Number ? ((Number) v).doubleValue() : null;
    }

    public Boolean getBoolean(String field) {
        Object v = data == null ? null : data.get(field);
        return v instanceof Boolean ? (Boolean) v : null;
    }

    /** D1 stores instants as epoch millis, so this wraps rather than parses. */
    public com.google.firebase.Timestamp getTimestamp(String field) {
        long ms = Format.ts(data == null ? null : data.get(field));
        return ms > 0
                ? new com.google.firebase.Timestamp(ms / 1000L, (int) ((ms % 1000L) * 1_000_000L))
                : null;
    }
}
