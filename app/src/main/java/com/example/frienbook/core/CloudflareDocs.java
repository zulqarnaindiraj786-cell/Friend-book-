package com.example.frienbook.core;

import android.os.Handler;
import android.os.Looper;

import com.example.frienbook.BuildConfig;

import org.json.JSONObject;

import java.net.URL;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The Cloudflare copy of app documents (`/v1/docs` on the Worker, table `friendbook_cf_documents`).
 *
 * Firebase stays the authentication source only. Every document this app creates or edits is also
 * mirrored to Cloudflare D1 through the Worker, so the social data has a home outside Firestore and
 * read paths can move over collection by collection without ever losing a document: the mirror is
 * additive (merge on conflict, never deletes), bounded (95 KiB per document on the Worker) and
 * fire-and-forget (a failed mirror is silent - the next edit carries the full document again).
 *
 * The Worker accepts exactly the collections the app owns (users, posts, reels, stories, chats,
 * messages, friends, friend_requests, follows, groups, pages, notifications, official_broadcasts,
 * lives, pokes, ...) and stamps owner/createdAt/updatedAt itself.
 */
public final class CloudflareDocs {

    private CloudflareDocs() {}

    private static final String BASE = CloudTransport.BASE + "/v1/docs";

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cf-docs");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** Mirroring is staged behind a build flag so a batch can be turned off without a release. */
    public static boolean enabled() {
        return BuildConfig.CLOUDFLARE_DOCS_MIRROR;
    }

    /**
     * Mirrors one document to Cloudflare D1 (merge-upsert on the Worker). Fire-and-forget on a
     * background thread: callers keep writing Firestore exactly as before, so a Worker outage can
     * never block posting, and the mirror simply catches up on the next edit.
     */
    public static void mirror(String collection, String id, Map<String, Object> fields) {
        if (!enabled() || collection == null || collection.isEmpty() || id == null || id.isEmpty()) return;
        if (fields == null) return;
        final JSONObject body = new JSONObject(sanitize(fields));
        CloudTransport.withToken(IO, MAIN, token -> {
            try {
                URL url = new URL(BASE + "/" + collection + "/" + id);
                CloudTransport.send(url, "PUT", token, body);
            } catch (Throwable ignored) {
                // Silent by design: the Firestore write already succeeded and the next edit re-mirrors.
            }
        }, null);
    }

    /**
     * Firestore sentinels (serverTimestamp, arrayUnion, ...) are not JSON. The mirror substitutes the
     * client clock for a server timestamp - the Worker stamps its own updatedAt anyway - and drops
     * any other sentinel instead of sending Firestore internals to Cloudflare.
     */
    private static Map<String, Object> sanitize(Map<String, Object> fields) {
        Map<String, Object> out = new java.util.HashMap<>();
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            Object v = e.getValue();
            if (v instanceof com.google.firebase.firestore.FieldValue) {
                String s = String.valueOf(v);
                if (s.contains("serverTimestamp")) v = System.currentTimeMillis();
                else continue;
            }
            out.put(e.getKey(), v);
        }
        return out;
    }

    /**
     * Reads one page of mirrored documents straight from D1 (newest first, max 50). Used by read
     * paths as they move over; the callback always runs on the main thread and receives an empty
     * list when the Worker is unreachable - callers then fall back to their previous source.
     */
    public static void list(String collection, int limit, Consumer<org.json.JSONArray> callback) {
        if (callback == null) return;
        if (!enabled() || collection == null || collection.isEmpty()) {
            MAIN.post(() -> callback.accept(new org.json.JSONArray()));
            return;
        }
        final int bounded = Math.max(1, Math.min(50, limit));
        CloudTransport.withToken(IO, MAIN, token -> {
            org.json.JSONArray docs = new org.json.JSONArray();
            try {
                URL url = new URL(BASE + "?collection=" + collection + "&limit=" + bounded);
                JSONObject data = CloudTransport.send(url, "GET", token, null);
                docs = data != null ? data.optJSONArray("documents") : null;
                if (docs == null) docs = new org.json.JSONArray();
            } catch (Throwable ignored) {
                docs = new org.json.JSONArray();
            }
            final org.json.JSONArray result = docs;
            MAIN.post(() -> callback.accept(result));
        }, () -> MAIN.post(() -> callback.accept(new org.json.JSONArray())));
    }
}
