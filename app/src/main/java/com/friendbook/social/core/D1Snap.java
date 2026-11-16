package com.friendbook.social.core;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * One D1 result page, readable with the same shape the screens used on a Firestore
 * {@code QuerySnapshot}.
 *
 * <p>Every screen that used {@code addSnapshotListener} rendered from a snapshot: it read
 * {@code getDocuments()}, {@code size()} or {@code isEmpty()} and built rows. Those bodies are
 * unchanged; what changed is that the data now arrives once from D1 instead of being pushed by
 * Firestore, which cannot push while its network is off.
 *
 * <p>There is no change feed. A screen that needs fresh data after a write calls this again -
 * which is one request on an explicit action, not an interval timer that keeps billing reads
 * while nobody is looking.
 */
public final class D1Snap implements Iterable<Doc> {

    private final List<Doc> documents;

    private D1Snap(List<Doc> documents) {
        this.documents = documents;
    }

    public static D1Snap of(CloudflareStore.Page page) {
        return new D1Snap(Doc.ofAll(page == null ? null : page.documents));
    }

    public static D1Snap ofRows(List<Map<String, Object>> rows) {
        return new D1Snap(Doc.ofAll(rows));
    }

    /**
     * Same page, but every document is rewrapped with the id the caller cares about.
     *
     * <p>The message table names a row {@code <chatId>_<messageId>} so that one flat table can
     * hold every conversation. Screens key messages by the bare message id, so they need that
     * prefix removed before the row is rendered; handing the caller the raw id made replies and
     * receipts attach to the wrong message.
     */
    public static D1Snap ofDocs(List<Map<String, Object>> rows, String idPrefix) {
        List<Doc> docs = new java.util.ArrayList<>();
        for (Map<String, Object> row : rows) {
            Doc raw = Doc.ofAll(java.util.Collections.singletonList(row)).get(0);
            String id = raw.getId();
            if (idPrefix != null && !idPrefix.isEmpty() && id != null && id.startsWith(idPrefix)) {
                id = id.substring(idPrefix.length());
            }
            docs.add(Doc.of(id, row));
        }
        return new D1Snap(docs);
    }

    public List<Doc> getDocuments() { return documents; }

    /** Lets rendering code keep its {@code for (DocumentSnapshot d : snap)} shape. */
    @Override public java.util.Iterator<Doc> iterator() { return documents.iterator(); }

    public int size() { return documents.size(); }

    public boolean isEmpty() { return documents.isEmpty(); }

    /**
     * Reads a whole collection once. The list variant exists because many screens asked for
     * "everything in this collection, newest first" and a query builder per call adds nothing.
     */
    public static void read(String collection, int limit, Consumer<D1Snap> callback) {
        CloudflareStore.list(collection, limit, rows -> callback.accept(ofRows(rows)), () -> { });
    }

    /** Reads a filtered query once. */
    public static void read(DocQuery query, Consumer<D1Snap> callback) {
        CloudflareStore.query(query, page -> callback.accept(of(page)), () -> { });
    }
}
