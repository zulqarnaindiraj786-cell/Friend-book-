package com.friendbook.social.core;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Polling replacement for Firestore {@code addSnapshotListener} against the D1 document store.
 *
 * <p>Firestore pushed changes over a socket. D1 has no change feed, so this asks the same
 * question again on an interval and works out what actually changed. Callers that used a
 * listener keep the same shape: an initial delivery, then incremental deltas, then a way to
 * stop.
 *
 * <pre>
 *   DocWatch watch = CloudflareStore.watch(DocQuery.of("posts").orderByDescending("createdAt"), 10_000, delta -&gt; {
 *       for (Map&lt;String, Object&gt; doc : delta.added)   { ... }
 *       for (String id : delta.removed)                { ... }
 *       if (delta.changed) render();
 *   });
 *   // later
 *   watch.stop();
 * </pre>
 *
 * <p>Two properties matter for a feed that people actually use:
 * <ul>
 *   <li>It never fires for a poll that returned identical data. A 10-second tick over an idle
 *       feed must not re-render, or the list flickers and recycles the scroll position.</li>
 *   <li>It stops itself when the owner goes away. The owner passes a stopped-check so the watch
 *       can cancel itself rather than leak a Handler that polls a dead Activity forever.</li>
 * </ul>
 *
 * <p>Interval is floored at {@link #MIN_INTERVAL_MS}: a 1-second poll over lakhon clients would
 * be a self-inflicted load test on D1, which has a daily read cap.
 */
public final class DocWatch {

    private static final String TAG = "DocWatch";

    /** Never poll faster than this, no matter what a caller asks for. */
    public static final long MIN_INTERVAL_MS = 5_000L;
    public static final long DEFAULT_INTERVAL_MS = 15_000L;

    /** What changed between two polls. */
    public static final class Delta {
        public final List<Map<String, Object>> added;
        public final List<Map<String, Object>> updated;
        public final List<String> removed;
        /** False when the poll matched the previous one exactly. */
        public final boolean changed;
        /** Every document in the current window, in query order. */
        public final List<Map<String, Object>> documents;

        Delta(List<Map<String, Object>> added, List<Map<String, Object>> updated,
              List<String> removed, List<Map<String, Object>> documents) {
            this.added = added;
            this.updated = updated;
            this.removed = removed;
            this.documents = documents;
            this.changed = !added.isEmpty() || !updated.isEmpty() || !removed.isEmpty();
        }
    }

    /** Callback plus a reason it might want to stop. */
    public interface Stopped {
        boolean shouldStop();
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final DocQuery query;
    private final long intervalMs;
    private final Consumer<Delta> listener;
    private final Stopped stopped;
    private final Runnable tick = this::poll;

    /** Last seen documents by id, plus their serialised form for change detection. */
    private final Map<String, Map<String, Object>> previous = new LinkedHashMap<>();
    private final Map<String, String> previousSignature = new LinkedHashMap<>();

    private boolean stoppedFlag;
    private boolean inFlight;
    private int failures;

    DocWatch(DocQuery query, long intervalMs, Stopped stopped, Consumer<Delta> listener) {
        this.query = query;
        this.intervalMs = Math.max(MIN_INTERVAL_MS, intervalMs);
        this.stopped = stopped;
        this.listener = listener;
    }

    void start() {
        handler.post(tick);
    }

    public void stop() {
        stoppedFlag = true;
        handler.removeCallbacks(tick);
    }

    public boolean isStopped() {
        return stoppedFlag;
    }

    private void poll() {
        if (stoppedFlag) return;
        if (stopped != null && stopped.shouldStop()) {
            stop();
            return;
        }
        if (inFlight) {
            // A slow response must not stack requests; just try again next tick.
            scheduleNext();
            return;
        }
        inFlight = true;
        CloudflareStore.query(query, page -> {
            inFlight = false;
            if (stoppedFlag) return;
            if (page.documents.isEmpty() && failures > 0) {
                // Transient failure: keep the last good view instead of blanking the screen.
                scheduleNext();
                return;
            }
            failures = 0;
            deliver(page.documents);
            scheduleNext();
        });
    }

    private void scheduleNext() {
        if (stoppedFlag) return;
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, intervalMs);
    }

    private void deliver(List<Map<String, Object>> documents) {
        Map<String, Map<String, Object>> current = new LinkedHashMap<>();
        Map<String, String> currentSignature = new LinkedHashMap<>();
        for (Map<String, Object> doc : documents) {
            String id = idOf(doc);
            if (id == null) continue;
            current.put(id, doc);
            currentSignature.put(id, signature(doc));
        }

        List<Map<String, Object>> added = new ArrayList<>();
        List<Map<String, Object>> updated = new ArrayList<>();
        for (Map<String, Object> doc : current.values()) {
            String id = idOf(doc);
            if (!previous.containsKey(id)) {
                added.add(doc);
            } else if (!currentSignature.get(id).equals(previousSignature.get(id))) {
                updated.add(doc);
            }
        }
        List<String> removed = new ArrayList<>();
        for (String id : previous.keySet()) {
            if (!current.containsKey(id)) removed.add(id);
        }

        previous.clear();
        previous.putAll(current);
        previousSignature.clear();
        previousSignature.putAll(currentSignature);

        Delta delta = new Delta(added, updated, removed, new ArrayList<>(current.values()));
        if (!delta.changed) return;
        try {
            listener.accept(delta);
        } catch (Throwable t) {
            Log.w(TAG, "watch listener threw", t);
        }
    }

    /**
     * Stable identity for a document. The store always sets {@code id}; falling back to the
     * path-derived key keeps a document from looking new on every poll if it is missing.
     */
    private static String idOf(Map<String, Object> doc) {
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

    private static String signature(Map<String, Object> doc) {
        return CloudflareStore.toJson(doc).toString();
    }
}
