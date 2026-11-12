package com.example.frienbook.core.cloud;

import java.util.ArrayList;
import java.util.List;

/**
 * What keeps a screen live.
 *
 * Cloudflare D1 has no push channel to a phone, so a listener polls - but it never re-downloads the
 * page it already has. The first read asks for the page, and every read after that asks only for
 * changes since the position the previous answer reported (the Worker's `since` feed, which includes
 * deletions so a removed row really disappears from the list). The phone then keeps the newest state
 * of each id it has seen, in the order the query sorted them, and calls the listener once with that
 * merged view - which is what a live screen expects: one answer, complete, not a diff it has to apply.
 *
 * Nothing here writes to the database, and nothing here guesses: if a poll fails the listener hears
 * the error and the screen keeps showing what it had.
 */
final class Watcher implements Runnable {

    /** Chat threads and live rooms feel instant at this rate; it costs one small HTTP read per tick. */
    private static final long FAST_MS = 2500L;
    /** Feeds, friend lists and admin tables do not need to be faster than this. */
    private static final long SLOW_MS = 8000L;

    private final String collectionPath;
    private final String documentId;
    private final List<DocumentSnapshot> rows;
    private final String query;
    private final EventListener<?> listener;
    private final boolean document;
    private final long intervalMs;

    private long since = 0L;
    private String cursor = null;
    private boolean stopped;
    private boolean delivered;

    Watcher(String collectionPath, String documentId, List<DocumentSnapshot> rows, String query,
            boolean document, EventListener<?> listener, long intervalMs) {
        this.collectionPath = collectionPath;
        this.documentId = documentId;
        this.rows = rows;
        this.query = query;
        this.document = document;
        this.listener = listener;
        this.intervalMs = intervalMs;
    }

    static Watcher forQuery(Query query, List<DocumentSnapshot> seed, EventListener<QuerySnapshot> listener) {
        return new Watcher(query.collectionPath(), null, seed, query.listUrl(), false, listener,
                query.intervalMs());
    }

    static Watcher forDocument(String collectionPath, String id, EventListener<DocumentSnapshot> listener) {
        return new Watcher(collectionPath, id, new ArrayList<DocumentSnapshot>(), null, true, listener,
                FAST_MS);
    }

    void stop() {
        stopped = true;
    }

    @Override
    public void run() {
        if (stopped) return;
        Cloud.MAIN.post(() -> {
            if (stopped) return;
            try {
                if (document) deliverDocument();
                else deliverQuery();
            } catch (Exception failed) {
                onError(Cloud.wrap(failed));
            } catch (Throwable failed) {
                onError(new CloudException("listener failed", failed));
            }
            Cloud.MAIN.postDelayed(this, intervalMs);
        });
    }

    private void deliverDocument() throws CloudException {
        String url = "/v1/docs/" + collectionPath + "/" + Cloud.encode(documentId);
        org.json.JSONObject answer;
        try {
            answer = Cloud.call(url, "GET", null);
        } catch (CloudException failed) {
            // A document that is gone answers 404, and a watcher on a row somebody deleted has to say
            // so; any other failure is reported, so the screen keeps showing what it already had.
            if (failed.isMissing()) {
                List<DocumentSnapshot> gone = new ArrayList<>();
                gone.add(DocumentSnapshot.missing(collectionPath, documentId));
                deliver(gone, null, 0L);
                return;
            }
            throw failed;
        }
        List<DocumentSnapshot> merged = new ArrayList<>();
        merged.add(SnapshotDecoder.decodeDocument(collectionPath, documentId, answer));
        deliver(merged, null, 0L);
    }

    private void deliverQuery() throws CloudException {
        List<DocumentSnapshot> merged;
        String next = cursor;
        long position = since;

        if (!delivered) {
            // First tick: the whole page, exactly as get() would have answered it.
            org.json.JSONObject answer = Cloud.call(query, "GET", null);
            merged = new ArrayList<>();
            next = readPage(answer, merged);
            position = answer.optLong("since", 0L);
            rows.clear();
            rows.addAll(merged);
        } else {
            // Every later tick: only what changed since the position we already hold.
            org.json.JSONObject answer = Cloud.call(feedUrl(), "GET", null);
            merged = new ArrayList<>();
            next = readPage(answer, merged);
            position = answer.optLong("since", position);
            for (DocumentSnapshot row : merged) merge(row);
            merged = rows;
        }

        delivered = true;
        cursor = next;
        since = position;
        deliver(merged, next, position);
    }

    /** The change feed is the same list endpoint with a floor instead of a page. */
    private String feedUrl() {
        String separator = query.contains("?") ? "&" : "?";
        return query + separator + "since=" + since;
    }

    private String readPage(org.json.JSONObject answer, List<DocumentSnapshot> into) {
        SnapshotDecoder.decodeRows(collectionPath, answer.optJSONArray("documents"), into);
        String next = answer.optString("next", null);
        return next == null || next.isEmpty() ? null : next;
    }

    /**
     * Keeps the newest state of every id, in the order the query sorted them: a changed row updates
     * where it already sits, a deleted row leaves the list, and a row that has never been seen is
     * appended. Appending rather than re-sorting is deliberate - the visible list must not jump while
     * the user is reading it, and the next full read puts everything back in the queried order.
     */
    private void merge(DocumentSnapshot row) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).id.equals(row.id)) {
                if (row.exists) rows.set(i, row);
                else rows.remove(i);
                return;
            }
        }
        if (row.exists) rows.add(row);
    }

    @SuppressWarnings("unchecked")
    private void deliver(List<DocumentSnapshot> rows, String next, long position) {
        List<QueryDocumentSnapshot> typed = new ArrayList<>();
        for (DocumentSnapshot row : rows) {
            typed.add(new QueryDocumentSnapshot(collectionPath, row.id, row.data, row.exists, row.cursor,
                    row.updatedAt));
        }
        QuerySnapshot snapshot = new QuerySnapshot(typed, next, position, typed.size());
        if (document) {
            DocumentSnapshot only = typed.isEmpty() ? DocumentSnapshot.missing(collectionPath, "") : typed.get(0);
            ((EventListener<DocumentSnapshot>) listener).onEvent(only, null);
        } else {
            ((EventListener<QuerySnapshot>) listener).onEvent(snapshot, null);
        }
    }

    @SuppressWarnings("unchecked")
    private void onError(CloudException error) {
        if (document) {
            ((EventListener<DocumentSnapshot>) listener).onEvent(null, error);
        } else {
            ((EventListener<QuerySnapshot>) listener).onEvent(null, error);
        }
    }
}
