package com.friendbook.social.core;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

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

    /** What changed between two polls. Delegates to the pure logic in {@link DocDelta}. */
    public static final class Delta {
        public final List<Map<String, Object>> added;
        public final List<Map<String, Object>> updated;
        public final List<String> removed;
        public final boolean changed;
        public final List<Map<String, Object>> documents;

        Delta(DocDelta.Result r) {
            this.added = r.added;
            this.updated = r.updated;
            this.removed = r.removed;
            this.documents = r.documents;
            this.changed = r.changed();
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

    /** Signatures of the last delivered window, keyed by document id. */
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
        DocDelta.Result result = DocDelta.compute(previousSignature, documents);
        previousSignature.clear();
        for (Map<String, Object> doc : result.documents) {
            String id = DocDelta.idOf(doc);
            if (id != null) previousSignature.put(id, DocDelta.signature(doc));
        }
        Delta delta = new Delta(result);
        // A poll that matched the previous one must not re-render: an idle feed would
        // otherwise recycle its scroll position every tick.
        if (!delta.changed) return;
        try {
            listener.accept(delta);
        } catch (Throwable t) {
            Log.w(TAG, "watch listener threw", t);
        }
    }
}
