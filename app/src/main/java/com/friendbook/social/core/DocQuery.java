package com.friendbook.social.core;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * Firestore-shaped query for the Cloudflare D1 document store.
 *
 * <p>Mirrors the subset of {@code com.google.firebase.firestore.Query} this app actually
 * uses so call sites can move off Firestore without rewriting their intent:
 *
 * <pre>
 *   DocQuery.of("posts")
 *       .whereEqualTo("authorId", uid)
 *       .orderBy("createdAt", DocQuery.DESC)
 *       .limit(20)
 *       .build();
 * </pre>
 *
 * <p>Instances are immutable once built; {@link #build()} returns the serialised GET path,
 * e.g. {@code /v1/docs?collection=posts&where=authorId:eq:u1&orderBy=createdAt:desc&limit=20}.
 *
 * <p>Supported operators map onto the worker's allowlist: eq, ne, gt, gte, lt, lte, in, nin,
 * array_contains, array_contains_any, exists, not_exists. Anything outside that set is a
 * programming error and throws, rather than being silently dropped and returning wrong data.
 */
public final class DocQuery {

    public static final String ASC = "asc";
    public static final String DESC = "desc";

    /** Hard server-side ceiling; asking for more is clamped rather than rejected. */
    public static final int MAX_LIMIT = 100;

    private final String collection;
    private final List<String> wheres;
    private final String orderBy;
    private final int limit;
    private final String after;

    private DocQuery(String collection, List<String> wheres, String orderBy, int limit, String after) {
        this.collection = collection;
        this.wheres = wheres;
        this.orderBy = orderBy;
        this.limit = limit;
        this.after = after;
    }

    public static Builder of(String collection) {
        return new Builder(collection);
    }

    public String collection() { return collection; }
    public String after() { return after; }
    public int limit() { return limit; }

    /** Serialises to the path (and query) relative to the worker origin. */
    public String toPath() {
        StringBuilder sb = new StringBuilder("/v1/docs?collection=").append(encode(collection));
        for (String w : wheres) sb.append("&where=").append(encode(w));
        if (orderBy != null) sb.append("&orderBy=").append(encode(orderBy));
        sb.append("&limit=").append(limit);
        if (after != null && !after.isEmpty()) sb.append("&after=").append(encode(after));
        return sb.toString();
    }

    /**
     * A copy of this query with a different page size.
     *
     * <p>{@link Builder#limit} only exists on the builder, so a pager that owns a finished
     * query had no way to change the page size and passed the builder around instead.
     */
    public DocQuery limit(int size) {
        return new DocQuery(collection, wheres, orderBy, size, after);
    }

    /** A copy of this query positioned after {@code cursor}, for the next page. */
    public DocQuery nextPage(String cursor) {
        return new DocQuery(collection, wheres, orderBy, limit, cursor);
    }

    static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            // UTF-8 is guaranteed by the platform.
            throw new IllegalStateException(e);
        }
    }

    public static final class Builder {
        private final String collection;
        private final List<String> wheres = new ArrayList<>();
        private String orderBy;
        private int limit = 20;
        private String after;

        Builder(String collection) {
            if (collection == null || collection.isEmpty()) {
                throw new IllegalArgumentException("collection is required");
            }
            this.collection = collection;
        }

        private void add(String field, String op, String value) {
            if (field == null || field.isEmpty()) throw new IllegalArgumentException("field is required");
            if (value == null) throw new IllegalArgumentException("value is required for " + field);
            // The wire format is field:op:value, so neither the field nor the operator may
            // contain the separator.
            if (field.indexOf(':') >= 0) throw new IllegalArgumentException("field may not contain ':'");
            wheres.add(field + ":" + op + ":" + value);
        }

        public Builder whereEqualTo(String field, Object value) { add(field, "eq", str(value)); return this; }
        public Builder whereNotEqualTo(String field, Object value) { add(field, "ne", str(value)); return this; }
        public Builder whereGreaterThan(String field, Object value) { add(field, "gt", str(value)); return this; }
        public Builder whereGreaterThanOrEqualTo(String field, Object value) { add(field, "gte", str(value)); return this; }
        public Builder whereLessThan(String field, Object value) { add(field, "lt", str(value)); return this; }
        public Builder whereLessThanOrEqualTo(String field, Object value) { add(field, "lte", str(value)); return this; }

        /** Membership in an array field, like Firestore {@code whereArrayContains}. */
        public Builder whereArrayContains(String field, Object value) { add(field, "array_contains", str(value)); return this; }

        /** Any-of membership in an array field, like Firestore {@code whereArrayContainsAny}. */
        public Builder whereArrayContainsAny(String field, List<?> values) {
            add(field, "array_contains_any", join(values));
            return this;
        }

        /** Field presence, like Firestore {@code whereNotEqualTo(field, null)}. */
        public Builder whereExists(String field) { add(field, "exists", "1"); return this; }
        public Builder whereNotExists(String field) { add(field, "not_exists", "1"); return this; }

        public Builder orderBy(String field, String direction) {
            if (field == null || field.isEmpty()) throw new IllegalArgumentException("field is required");
            if (field.indexOf(':') >= 0) throw new IllegalArgumentException("field may not contain ':'");
            String dir = DESC.equalsIgnoreCase(direction) ? DESC : ASC;
            this.orderBy = field + ":" + dir;
            return this;
        }

        public Builder orderByDescending(String field) { return orderBy(field, DESC); }

        public Builder limit(int value) {
            // Clamp instead of throwing: a caller that asks for 500 wants "as many as you can",
            // and the server clamps to the same ceiling.
            this.limit = Math.max(1, Math.min(MAX_LIMIT, value));
            return this;
        }

        /** Continue from a cursor returned as {@code nextCursor} by a previous page. */
        public Builder startAfter(String cursor) {
            this.after = cursor;
            return this;
        }

        public DocQuery build() {
            return new DocQuery(collection, new ArrayList<>(wheres), orderBy, limit, after);
        }

        private static String str(Object value) {
            if (value == null) throw new IllegalArgumentException("value is required");
            if (value instanceof Boolean) return ((Boolean) value) ? "true" : "false";
            return String.valueOf(value);
        }

        private static String join(List<?> values) {
            if (values == null || values.isEmpty()) {
                throw new IllegalArgumentException("values are required");
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < values.size(); i++) {
                Object v = values.get(i);
                if (v == null) continue;
                String s = String.valueOf(v);
                // Commas separate values on the wire.
                if (s.indexOf(',') >= 0) throw new IllegalArgumentException("value may not contain ','");
                if (sb.length() > 0) sb.append(',');
                sb.append(s);
            }
            if (sb.length() == 0) throw new IllegalArgumentException("values are required");
            return sb.toString();
        }
    }
}
