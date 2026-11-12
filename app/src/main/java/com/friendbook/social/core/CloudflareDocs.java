package com.friendbook.social.core;

import android.os.Handler;
import android.os.Looper;

import com.friendbook.social.BuildConfig;
import com.google.firebase.auth.FirebaseUser;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The Cloudflare copy of app documents — `PUT/GET /v1/docs/<collection>/<id>` on the Worker, table
 * `friendbook_cf_documents` (migration 0011, already live in production).
 *
 * Firebase is authentication only here: the Firebase ID token is the bearer credential and the
 * Worker owns the row. Every document this app creates or edits is mirrored to Cloudflare D1 the
 * moment the Firestore write succeeds, so the social data has a home outside Firestore and read
 * paths can move over collection by collection without ever losing a document.
 *
 * Contract (all of it is enforced by tests):
 *  - additive only: the Worker merge-upserts, this client never sends a DELETE;
 *  - fire-and-forget: a failed mirror is silent, the Firestore write already succeeded and the next
 *    edit carries the whole document again, so a Worker outage can never block posting;
 *  - bounded: the Worker rejects a payload over 95 KiB and this transport over 32 KiB, so an
 *    oversized document (a post that carries an embedded document, for example) is skipped rather
 *    than retried forever — videos live in R2, never inside a mirrored row;
 *  - Firestore sentinels (serverTimestamp, arrayUnion, …) are not JSON: a server timestamp becomes
 *    the client clock (the Worker stamps its own updatedAt) and any other sentinel is dropped.
 */
public final class CloudflareDocs {

    private CloudflareDocs() {}

    private static final String BASE =
            "https://rough-firefly-dd7b.zulqarnaindiraj786.workers.dev/v1/docs";
    /** Mirrors are metadata only; anything bigger belongs in R2 or is a duplicate of an embed. */
    private static final int MAX_MIRROR_BYTES = 24 * 1024;
    private static final int MAX_LIST_IDS = 50;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cf-docs");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** Staged behind a build flag so a batch can be switched off without a release on the store. */
    public static boolean enabled() {
        return BuildConfig.CLOUDFLARE_DOCS_MIRROR;
    }

    /**
     * Mirrors one document to Cloudflare D1 (merge-upsert on the Worker). Fire-and-forget on a
     * background thread: the caller keeps using Firestore exactly as before.
     */
    public static void mirror(String collection, String id, Map<String, Object> fields) {
        if (!enabled() || collection == null || collection.isEmpty()) return;
        if (id == null || id.isEmpty() || fields == null) return;
        final JSONObject body = sanitize(fields);
        if (body.length() == 0) return;
        final String path = '/' + collection + '/' + id;
        withToken(token -> {
            try {
                if (body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_MIRROR_BYTES) {
                    return; // The Worker would refuse it; retrying would only burn quota.
                }
                URL url = new URL(BASE + path);
                D1HttpTransport.send(url, "PUT", token, body);
            } catch (Throwable ignored) {
                // Silent by design: Firestore already stored the document and the next edit re-mirrors.
            }
        }, null);
    }

    /**
     * Reads one page of mirrored documents straight from D1 (newest first, 1-50). Used by read paths
     * as they move over. The callback always runs on the main thread and receives an empty list when
     * the Worker is unreachable, so a caller can fall back to its previous source.
     */
    public static void list(String collection, int limit, Consumer<List<Map<String, Object>>> callback) {
        if (callback == null) return;
        if (!enabled() || collection == null || collection.isEmpty()) {
            MAIN.post(() -> callback.accept(new ArrayList<>()));
            return;
        }
        final int bounded = Math.max(1, Math.min(MAX_LIST_IDS, limit));
        final String query = "?collection=" + android.net.Uri.encode(collection) + "&limit=" + bounded;
        withToken(token -> {
            List<Map<String, Object>> docs = new ArrayList<>();
            try {
                JSONObject data = D1HttpTransport.send(new URL(BASE + query), "GET", token, null);
                JSONArray arr = data != null ? data.optJSONArray("documents") : null;
                if (arr != null) docs = D1Client.maps(arr);
            } catch (Throwable ignored) {
                docs = new ArrayList<>();
            }
            final List<Map<String, Object>> result = docs;
            MAIN.post(() -> callback.accept(result));
        }, () -> MAIN.post(() -> callback.accept(new ArrayList<>())));
    }

    private interface TokenWork {
        void run(String token);
    }

    /**
     * Hands a fresh Firebase ID token to `work` on the background thread. Mirrors are best-effort, so
     * "no token" (signed out, account switch, queue rejected) simply means "do nothing".
     */
    private static void withToken(final TokenWork work, final Runnable onUnavailable) {
        if (work == null) return;
        try {
            FirebaseUser user = Fb.auth().getCurrentUser();
            final String actor = Fb.uid();
            if (user == null || actor == null || actor.isEmpty()) {
                if (onUnavailable != null) onUnavailable.run();
                return;
            }
            user.getIdToken(false).addOnSuccessListener(res -> {
                String token = res != null ? res.getToken() : null;
                if (token == null || token.isEmpty()) {
                    if (onUnavailable != null) onUnavailable.run();
                    return;
                }
                try {
                    IO.execute(() -> {
                        // Never mirror into another account's session.
                        if (!actor.equals(Fb.uid())) {
                            if (onUnavailable != null) MAIN.post(onUnavailable);
                            return;
                        }
                        work.run(token);
                    });
                } catch (Throwable rejected) {
                    if (onUnavailable != null) onUnavailable.run();
                }
            }).addOnFailureListener(e -> {
                if (onUnavailable != null) onUnavailable.run();
            });
        } catch (Throwable ignored) {
            if (onUnavailable != null) onUnavailable.run();
        }
    }

    /**
     * Firestore sentinels are not JSON. A server timestamp becomes the client clock — the Worker
     * stamps its own authoritative updatedAt anyway — and every other sentinel is dropped instead of
     * leaking Firestore internals to Cloudflare.
     */
    static JSONObject sanitize(Map<String, Object> fields) {
        JSONObject out = new JSONObject();
        if (fields == null) return out;
        long now = System.currentTimeMillis();
        boolean timestamp = false;
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            String key = e.getKey();
            if (key == null || key.isEmpty()) continue;
            Object v = e.getValue();
            if (v instanceof com.google.firebase.firestore.FieldValue) {
                String s = String.valueOf(v);
                if (s.contains("serverTimestamp")) {
                    try { out.put(key, now); timestamp = true; } catch (Throwable ignored) { }
                }
                continue;
            }
            Object clean = clean(v);
            if (clean == null) continue;
            try { out.put(key, clean); } catch (Throwable ignored) { }
        }
        // The Worker keeps its own updatedAt; createdAt only needs a value when the document has one.
        if (timestamp && !out.has("createdAt")) {
            try { out.put("createdAt", now); } catch (Throwable ignored) { }
        }
        return out;
    }

    /** JSON-safe view of one value: nested maps/lists are cleaned, sentinels dropped. */
    private static Object clean(Object v) {
        if (v == null) return null;
        if (v instanceof com.google.firebase.firestore.FieldValue) return null;
        if (v instanceof String || v instanceof Number || v instanceof Boolean) return v;
        if (v instanceof com.google.firebase.Timestamp) {
            return ((com.google.firebase.Timestamp) v).toDate().getTime();
        }
        if (v instanceof java.util.Date) return ((java.util.Date) v).getTime();
        if (v instanceof Map) {
            JSONObject nested = sanitize(castMap(v));
            return nested.length() == 0 ? null : nested;
        }
        if (v instanceof List) {
            JSONArray arr = new JSONArray();
            for (Object item : (List<?>) v) {
                Object c = clean(item);
                if (c != null) arr.put(c);
            }
            return arr;
        }
        return String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object v) {
        return (Map<String, Object>) v;
    }
}
