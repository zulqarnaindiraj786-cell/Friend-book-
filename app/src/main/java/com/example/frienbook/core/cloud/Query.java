package com.example.frienbook.core.cloud;

import com.google.android.gms.tasks.Task;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * A read against one collection, built by chaining and then executed.
 *
 * The chain looks like the one 280 call sites already write, and each clause maps onto a bounded
 * parameter the Worker understands: whereEqualTo becomes eq[field], whereIn becomes in[field],
 * whereArrayContains becomes has[field], the four comparisons become gt/gte/lt/lte[field], orderBy
 * becomes order plus dir. The Worker checks every one of them against an allowlist derived from the
 * queries this app actually runs, and binds values as parameters - a filter never reaches SQL as text,
 * and a field nobody declared cannot be sorted or compared on.
 *
 * Two honest limits follow from that, and both are handled here rather than left for a screen to
 * discover:
 *  - The Worker answers at most 50 rows per request, so a bigger page is fetched as successive slices
 *    using the cursor the previous answer returned. A list of 400 users is four requests, not one -
 *    and a list of 13 friends is still exactly one, because slicing stops as soon as there is no next
 *    page. Nothing is truncated to fit a limit nobody asked for.
 *  - limitToLast(n) is served by reading the newest n rows in the opposite direction and reversing
 *    them, which is what "the last n in this order" means. A chat thread still opens on its most
 *    recent messages, oldest first.
 */
public class Query {

    /** Which end of the ordering a query starts from. */
    public enum Direction {
        ASCENDING, DESCENDING
    }

    /** Chat threads and live rooms are read this often while they are on screen. */
    private static final long FAST_POLL_MS = 2500L;
    /** Feeds, friend lists and admin tables do not need to be faster than this. */
    private static final long SLOW_POLL_MS = 8000L;
    /** Collections where a second of delay is visible to two people talking. */
    private static final String[] LIVE_LEAVES = {"messages", "chat", "typing", "reactions"};

    private final String collectionPath;
    private final Map<String, String> filters;
    private final String orderField;
    private final Direction direction;
    private final int limit;
    private final boolean tail;
    private final String after;

    Query(String collectionPath) {
        this(collectionPath, new LinkedHashMap<String, String>(), null, null, 0, false, null);
    }

    private Query(String collectionPath, Map<String, String> filters, String orderField,
                  Direction direction, int limit, boolean tail, String after) {
        this.collectionPath = collectionPath;
        this.filters = filters;
        this.orderField = orderField;
        this.direction = direction;
        this.limit = limit;
        this.tail = tail;
        this.after = after;
    }

    /** Every clause returns a new query, so a base query can be reused without surprises. */
    private Query with(Map<String, String> nextFilters, String nextOrder, Direction nextDirection,
                       int nextLimit, boolean nextTail, String nextAfter) {
        return new Query(collectionPath, nextFilters, nextOrder, nextDirection, nextLimit, nextTail, nextAfter);
    }

    private Map<String, String> plus(String key, String value) {
        Map<String, String> next = new LinkedHashMap<>(filters);
        next.put(key, value);
        return next;
    }

    // ------------------------------------------------------------------ clauses

    public Query whereEqualTo(String field, Object value) {
        return with(plus("eq[" + field + "]", Cloud.param(value)), orderField, direction, limit, tail, after);
    }

    /** FieldPath.documentId() compares ids, which the Worker answers from a bounded id set. */
    public Query whereEqualTo(FieldPath field, Object value) {
        if (field != null && field.isDocumentId()) {
            return with(plus("inId", Cloud.param(value)), orderField, direction, limit, tail, after);
        }
        return whereEqualTo(String.valueOf(field), value);
    }

    public Query whereIn(String field, Collection<?> values) {
        return with(plus("in[" + field + "]", Cloud.param(values)), orderField, direction, limit, tail, after);
    }

    public Query whereIn(FieldPath field, Collection<?> values) {
        if (field != null && field.isDocumentId()) {
            return with(plus("inId", Cloud.param(values)), orderField, direction, limit, tail, after);
        }
        return whereIn(String.valueOf(field), values);
    }

    /** "This uid is inside that array" - likedBy, participants, members, viewers. */
    public Query whereArrayContains(String field, Object value) {
        return with(plus("has[" + field + "]", Cloud.param(value)), orderField, direction, limit, tail, after);
    }

    public Query whereGreaterThan(String field, Object value) {
        return with(plus("gt[" + field + "]", Cloud.param(value)), orderField, direction, limit, tail, after);
    }

    public Query whereGreaterThanOrEqualTo(String field, Object value) {
        return with(plus("gte[" + field + "]", Cloud.param(value)), orderField, direction, limit, tail, after);
    }

    public Query whereLessThan(String field, Object value) {
        return with(plus("lt[" + field + "]", Cloud.param(value)), orderField, direction, limit, tail, after);
    }

    public Query whereLessThanOrEqualTo(String field, Object value) {
        return with(plus("lte[" + field + "]", Cloud.param(value)), orderField, direction, limit, tail, after);
    }

    public Query orderBy(String field) {
        return orderBy(field, Direction.ASCENDING);
    }

    public Query orderBy(String field, Direction sort) {
        return with(filters, field, sort == null ? Direction.ASCENDING : sort, limit, tail, after);
    }

    public Query limit(int count) {
        return with(filters, orderField, direction, Math.max(1, count), false, after);
    }

    /** The newest `count` rows in this order, delivered in this order. */
    public Query limitToLast(int count) {
        return with(filters, orderField, direction, Math.max(1, count), true, after);
    }

    /** Continues after a document already on screen - the paging a feed or a thread uses. */
    public Query startAfter(DocumentSnapshot snapshot) {
        String cursor = snapshot == null ? null : snapshot.getCursor();
        return with(filters, orderField, direction, limit, tail, cursor);
    }

    public Query startAfter(QuerySnapshot snapshot) {
        if (snapshot == null || snapshot.isEmpty()) return this;
        return startAfter(snapshot.getDocuments().get(snapshot.size() - 1));
    }

    /**
     * Continues after a value rather than a document. The Worker pages by cursor, so this is only
     * exact when the value is the ordering field; a screen that has the previous document should pass
     * that instead.
     */
    public Query startAfter(Object... values) {
        if (values == null || values.length == 0) return this;
        return with(filters, orderField, direction, limit, tail, Cursor.encode(values[0], ""));
    }

    // ------------------------------------------------------------------ execution

    /** Fetches one page (or as many slices as the requested limit needs) and answers with it. */
    public Task<QuerySnapshot> get() {
        return Cloud.submit(new Cloud.Job<QuerySnapshot>() {
            @Override
            public QuerySnapshot run() throws Exception {
                return fetch(0L);
            }
        });
    }

    /**
     * Keeps a list on screen up to date. The listener is called once with the first page and again
     * whenever something in it changes, until remove() is called.
     */
    public ListenerRegistration addSnapshotListener(EventListener<QuerySnapshot> listener) {
        return start(listener);
    }

    public ListenerRegistration addSnapshotListener(Executor ignored, EventListener<QuerySnapshot> listener) {
        return start(listener);
    }

    public ListenerRegistration addSnapshotListener(Object ignored, EventListener<QuerySnapshot> listener) {
        return start(listener);
    }

    private ListenerRegistration start(EventListener<QuerySnapshot> listener) {
        Watcher watcher = Watcher.forQuery(this, new ArrayList<DocumentSnapshot>(), listener);
        Cloud.MAIN.post(watcher);
        return new ListenerRegistration(watcher);
    }

    /**
     * The read a watcher performs: rows, the next cursor, and the change position this answer already
     * includes. `since` above zero turns it into a change feed - the Worker then answers only rows that
     * changed at or after that moment, deletions included, so a watcher never re-downloads a page and
     * never misses a removal.
     */
    QuerySnapshot fetch(long since) throws CloudException {
        int want = limit > 0 ? limit : Cloud.MAX_PAGES * Cloud.PAGE;
        List<DocumentSnapshot> rows = new ArrayList<>();
        String cursor = after;
        String next = null;
        long position = since;
        int pages = 0;

        while (pages < Cloud.MAX_PAGES && rows.size() < want) {
            int slice = Math.min(Cloud.PAGE, want - rows.size());
            JSONObject answer = Cloud.call(url(slice, cursor, since), "GET", null);
            List<DocumentSnapshot> page = new ArrayList<>();
            SnapshotDecoder.decodeRows(collectionPath, answer.optJSONArray("documents"), page);
            rows.addAll(page);
            next = optString(answer, "next");
            position = Math.max(position, answer.optLong("since", 0L));
            pages += 1;
            if (next == null || page.isEmpty()) {
                next = null;
                break;
            }
            cursor = next;
            // A change feed answers only what moved, so paging through it would repeat the same rows.
            if (since > 0L) break;
        }

        if (tail) {
            // Read backwards, then put it back in the order the query asked for.
            Collections.reverse(rows);
            next = null;
        }
        if (limit > 0 && rows.size() > limit) rows = new ArrayList<>(rows.subList(0, limit));

        List<QueryDocumentSnapshot> typed = new ArrayList<>();
        for (DocumentSnapshot row : rows) {
            typed.add(new QueryDocumentSnapshot(collectionPath, row.id, row.data, row.exists, row.cursor,
                    row.updatedAt));
        }
        return new QuerySnapshot(typed, next, position, typed.size());
    }

    /** The URL for one request: filters, ordering, page size, cursor and - for a watcher - the floor. */
    private String url(int slice, String cursor, long since) {
        Map<String, String> params = new LinkedHashMap<>(filters);
        if (orderField != null && !orderField.isEmpty()) params.put("order", orderField);
        params.put("dir", dirValue());
        params.put("limit", String.valueOf(slice));
        if (cursor != null && !cursor.isEmpty()) params.put("after", cursor);
        if (since > 0L) params.put("since", String.valueOf(since));
        return Cloud.listUrl(collectionPath, params);
    }

    /** limitToLast reads from the other end and the client reverses the page. */
    private String dirValue() {
        boolean ascending = direction == Direction.ASCENDING;
        if (tail) ascending = !ascending;
        return ascending ? "asc" : "desc";
    }

    String listUrl() {
        int slice = limit > 0 ? Math.min(Cloud.PAGE, limit) : Cloud.PAGE;
        return url(slice, after, 0L);
    }

    String collectionPath() {
        return collectionPath;
    }

    /** The leaf collection decides both the access rule and how often this query should be polled. */
    String leaf() {
        int slash = collectionPath.lastIndexOf('/');
        return slash < 0 ? collectionPath : collectionPath.substring(slash + 1);
    }

    long intervalMs() {
        String leaf = leaf();
        for (String live : LIVE_LEAVES) if (live.equals(leaf)) return FAST_POLL_MS;
        return SLOW_POLL_MS;
    }

    private static String optString(JSONObject answer, String key) {
        String value = answer.optString(key, null);
        return value == null || value.isEmpty() || "null".equals(value) ? null : value;
    }
}
