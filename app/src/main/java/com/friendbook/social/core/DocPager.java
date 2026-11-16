package com.friendbook.social.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Bounded, account-scoped pagination over D1.
 *
 * <p>The D1 counterpart of {@link CursorPager}, which pages a Firestore Query. It exists
 * because {@code CursorPager} cannot be reused: Firestore's {@code startAfter(DocumentSnapshot)}
 * has no D1 equivalent, and handing a Firestore snapshot back to a D1 caller is how the two
 * stores end up disagreeing about which page comes next.
 *
 * <p>Documents are delivered as plain maps with the id folded in under {@code "id"}, so the
 * model {@code fromMap} overloads can read them exactly the way they read Firestore data.
 * The cursor never advances on a failed page, so a retry asks for the same rows again instead
 * of silently skipping them.
 */
public final class DocPager {

    /** Identifies the caller's current view, so a stale page cannot land on a new one. */
    public interface Listener {
        void changed(List<Map<String, Object>> docs, boolean more, boolean loading, String error);
    }

    private final DocQuery base;
    private final int size;
    private final String actor;
    private final Listener listener;
    private final LinkedHashMap<String, Map<String, Object>> records = new LinkedHashMap<>();

    private String cursor;
    private boolean more = true;
    private boolean loading;
    private boolean closed;

    public DocPager(DocQuery base, int size, Listener listener) {
        this.base = base;
        this.size = Math.max(1, Math.min(DocQuery.MAX_LIMIT, size));
        this.actor = Fb.uid();
        this.listener = listener;
    }

    public void start() {
        more = true;
        next();
    }

    /** Loads the next page. Safe to call repeatedly; a page already in flight is ignored. */
    public void next() {
        if (closed || loading || !more || !Objects.equals(actor, Fb.uid())) return;
        loading = true;
        emit("");
        DocQuery q = cursor == null ? base : base.nextPage(cursor);
        DocQuery pageQuery = q.limit(size);
        CloudflareStore.query(pageQuery, page -> {
            if (!active()) return;
            loading = false;
            // The worker reports hasMore; using it rather than "the page was full" means a
            // short last page does not trigger one more empty request per list.
            more = page.hasMore;
            if (cursor == null) records.clear();
            for (Map<String, Object> doc : page.documents) {
                Object id = doc.get("id");
                if (id != null) records.put(String.valueOf(id), doc);
            }
            if (page.nextCursor != null) cursor = page.nextCursor;
            emit("");
        }, () -> {
            if (!active()) return;
            loading = false;
            // The cursor is deliberately not advanced: the caller can retry this same page.
            emit("Couldn't load this page. Tap to retry.");
        });
    }

    private boolean active() { return !closed && Objects.equals(actor, Fb.uid()); }

    private void emit(String error) {
        if (!active()) return;
        listener.changed(new ArrayList<>(records.values()), more, loading, error);
    }

    public void forget(String id) {
        records.remove(id);
    }

    public boolean hasMore() { return more; }

    public void close() { closed = true; }
}
