package com.friendbook.social.core;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.friendbook.social.BuildConfig;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FieldValue;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Cloudflare D1 App Document Store client (/v1/docs) for updated APK clients.
 * Preserves all historical data in D1 while writing new/updated user records to Cloudflare.
 */
public final class CloudflareStore {
    private CloudflareStore() {}

    private static final String ORIGIN = "https://rough-firefly-dd7b.zulqarnaindiraj786.workers.dev";
    private static final String BASE = ORIGIN + "/v1/docs";
    private static final long LIST_CACHE_TTL_MS = 5 * 60_000L;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final android.util.LruCache<String, List<Map<String, Object>>> LIST_CACHE = new android.util.LruCache<>(16);
    private static final android.util.LruCache<String, Long> LIST_CACHE_AT = new android.util.LruCache<>(16);

    public static boolean enabled() {
        return BuildConfig.D1_INTERACTIONS_ENABLED;
    }

    public static JSONObject toJson(Map<String, Object> map) {
        JSONObject out = new JSONObject();
        if (map == null) return out;
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Object> e : map.entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            if (k == null || v == null) continue;
            try {
                if (v instanceof FieldValue) {
                    if (k.toLowerCase().contains("at") || k.toLowerCase().contains("time") || k.toLowerCase().contains("seen")) {
                        out.put(k, now);
                    }
                } else if (v instanceof List) {
                    JSONArray arr = new JSONArray();
                    for (Object item : (List<?>) v) {
                        if (item instanceof Map) {
                            //noinspection unchecked
                            arr.put(toJson((Map<String, Object>) item));
                        } else {
                            arr.put(item);
                        }
                    }
                    out.put(k, arr);
                } else if (v instanceof Map) {
                    //noinspection unchecked
                    out.put(k, toJson((Map<String, Object>) v));
                } else if (v instanceof Boolean || v instanceof Number || v instanceof String) {
                    out.put(k, v);
                }
            } catch (JSONException ignored) { }
        }
        return out;
    }

    public static void put(String collection, String docId, Map<String, Object> fields) {
        put(collection, docId, fields, null);
    }

    public static void put(String collection, String docId, Map<String, Object> fields, Runnable onSuccess) {
        put(collection, docId, fields, onSuccess, null);
    }

    /**
     * Writes a document, reporting both outcomes.
     *
     * <p>The one-callback form silently did nothing whenever the worker refused, the token was
     * missing or the request threw - which is indistinguishable from success for the caller and
     * is how a write could disappear with no error anywhere. A caller that passes
     * {@code onFailure} is told, so it can retry or route the write elsewhere.
     */
    public static void put(String collection, String docId, Map<String, Object> fields,
                           Runnable onSuccess, Runnable onFailure) {
        if (!enabled() || collection == null || docId == null || docId.isEmpty() || fields == null) {
            if (onFailure != null) MAIN.post(onFailure);
            return;
        }
        final JSONObject body = toJson(fields);
        try {
            FirebaseUser user = Fb.auth().getCurrentUser();
            if (user == null) { if (onFailure != null) MAIN.post(onFailure); return; }
            user.getIdToken(false).addOnSuccessListener(res -> {
                String token = res != null ? res.getToken() : null;
                if (token == null || token.isEmpty()) { if (onFailure != null) MAIN.post(onFailure); return; }
                IO.execute(() -> {
                    try {
                        URL url = new URL(BASE + "/" + Uri.encode(collection) + "/" + Uri.encode(docId));
                        D1HttpTransport.send(url, "PUT", token, body);
                        LIST_CACHE.evictAll();
                        LIST_CACHE_AT.evictAll();
                        if (onSuccess != null) MAIN.post(onSuccess);
                    } catch (Throwable refused) {
                        if (onFailure != null) MAIN.post(onFailure);
                    }
                });
            }).addOnFailureListener(err -> { if (onFailure != null) MAIN.post(onFailure); });
        } catch (Throwable refused) {
            if (onFailure != null) MAIN.post(onFailure);
        }
    }

    public static void list(String collection, int limit, Consumer<List<Map<String, Object>>> callback) {
        list(collection, limit, callback, null);
    }

    /**
     * Lists a collection, reporting a refusal as well as a result.
     *
     * <p>Without {@code onFailure} a caller cannot tell "the worker said no" from "the
     * collection is empty", and every such caller in this app ended up showing a blank list.
     * A caller that passes it can stop its spinner and say something true.
     */
    public static void list(String collection, int limit, Consumer<List<Map<String, Object>>> callback,
                            Runnable onFailure) {
        if (!enabled() || collection == null || callback == null) {
            if (onFailure != null) MAIN.post(onFailure);
            return;
        }
        final int safeLimit = Math.max(1, Math.min(DocQuery.MAX_LIMIT, limit));
        final String cacheKey = collection + ":" + safeLimit;
        long now = android.os.SystemClock.elapsedRealtime();
        List<Map<String, Object>> cached = LIST_CACHE.get(cacheKey);
        Long cachedAt = LIST_CACHE_AT.get(cacheKey);
        if (cached != null && cachedAt != null && (now - cachedAt) < LIST_CACHE_TTL_MS) {
            MAIN.post(() -> callback.accept(cached));
            return;
        }
        try {
            FirebaseUser user = Fb.auth().getCurrentUser();
            if (user == null) { if (onFailure != null) MAIN.post(onFailure); return; }
            user.getIdToken(false).addOnSuccessListener(res -> {
                String token = res != null ? res.getToken() : null;
                if (token == null || token.isEmpty()) { if (onFailure != null) MAIN.post(onFailure); return; }
                IO.execute(() -> {
                    try {
                        URL url = new URL(BASE + "?collection=" + Uri.encode(collection) + "&limit=" + safeLimit);
                        JSONObject data = D1HttpTransport.send(url, "GET", token, null);
                        JSONArray arr = data.optJSONArray("documents");
                        List<Map<String, Object>> docs = arr != null ? D1Client.maps(arr) : new ArrayList<>();
                        LIST_CACHE.put(cacheKey, docs);
                        LIST_CACHE_AT.put(cacheKey, android.os.SystemClock.elapsedRealtime());
                        MAIN.post(() -> callback.accept(docs));
                    } catch (Throwable refused) {
                        if (onFailure != null) MAIN.post(onFailure);
                    }
                });
            }).addOnFailureListener(err -> { if (onFailure != null) MAIN.post(onFailure); });
        } catch (Throwable refused) {
            if (onFailure != null) MAIN.post(onFailure);
        }
    }

    /** A page of results from the document store. */
    public static final class Page {
        public final List<Map<String, Object>> documents;
        public final boolean hasMore;
        public final String nextCursor;

        Page(List<Map<String, Object>> documents, boolean hasMore, String nextCursor) {
            this.documents = documents;
            this.hasMore = hasMore;
            this.nextCursor = nextCursor;
        }

        public boolean isEmpty() { return documents.isEmpty(); }
    }

    /**
     * Runs a filtered, ordered, paginated query. Unlike {@link #list}, results are never
     * cached: they depend on caller parameters and on a cursor, and a stale page would
     * silently repeat or skip rows.
     *
     * <p>{@code callback} runs on the main thread. On any failure it receives an empty page,
     * never null, so callers can render "no results" without a separate error path.
     */
    public static void query(DocQuery query, Consumer<Page> callback) {
        if (!enabled() || query == null || callback == null) return;
        FirebaseUser user = currentUser();
        if (user == null) {
            MAIN.post(() -> callback.accept(emptyPage()));
            return;
        }
        user.getIdToken(false).addOnSuccessListener(res -> {
            String token = res != null ? res.getToken() : null;
            if (token == null || token.isEmpty()) {
                MAIN.post(() -> callback.accept(emptyPage()));
                return;
            }
            IO.execute(() -> {
                Page page;
                try {
                    URL url = new URL(ORIGIN + query.toPath());
                    JSONObject data = D1HttpTransport.send(url, "GET", token, null);
                    JSONArray arr = data != null ? data.optJSONArray("documents") : null;
                    List<Map<String, Object>> docs = arr != null ? D1Client.maps(arr) : new ArrayList<>();
                    String next = data != null ? data.optString("nextCursor", null) : null;
                    if (next != null && next.isEmpty()) next = null;
                    page = new Page(docs, data != null && data.optBoolean("hasMore", false), next);
                } catch (Throwable ignored) {
                    page = emptyPage();
                }
                final Page result = page;
                MAIN.post(() -> callback.accept(result));
            });
        });
    }

    /**
     * Deletes one document. Owner-only on the server; deleting something already absent is a
     * success, which makes retries safe.
     */
    public static void delete(String collection, String docId, Runnable onSuccess) {
        remove(collection, docId, onSuccess, null);
    }

    /** Deletes a document, reporting both outcomes. See {@link #put} for why. */
    public static void remove(String collection, String docId, Runnable onSuccess, Runnable onFailure) {
        if (!enabled() || collection == null || docId == null || docId.isEmpty()) {
            if (onFailure != null) MAIN.post(onFailure);
            return;
        }
        FirebaseUser user = currentUser();
        if (user == null) { if (onFailure != null) MAIN.post(onFailure); return; }
        user.getIdToken(false).addOnSuccessListener(res -> {
            String token = res != null ? res.getToken() : null;
            if (token == null || token.isEmpty()) { if (onFailure != null) MAIN.post(onFailure); return; }
            IO.execute(() -> {
                try {
                    URL url = new URL(BASE + "/" + Uri.encode(collection) + "/" + Uri.encode(docId));
                    D1HttpTransport.send(url, "DELETE", token, null);
                    LIST_CACHE.evictAll();
                    LIST_CACHE_AT.evictAll();
                    if (onSuccess != null) MAIN.post(onSuccess);
                } catch (Throwable refused) {
                    if (onFailure != null) MAIN.post(onFailure);
                }
            });
        }).addOnFailureListener(err -> { if (onFailure != null) MAIN.post(onFailure); });
    }

    /**
     * Polls {@code query} and reports deltas, as a replacement for a Firestore snapshot
     * listener. See {@link DocWatch}. The watch stops itself when {@code stopped} reports true,
     * and callers must still {@link DocWatch#stop()} when they finish early.
     */
    public static DocWatch watch(DocQuery query, long intervalMs, DocWatch.Stopped stopped,
                                 Consumer<DocWatch.Delta> listener) {
        DocWatch w = new DocWatch(query, intervalMs, stopped, listener);
        if (enabled()) w.start();
        return w;
    }

    private static FirebaseUser currentUser() {
        try {
            return Fb.auth().getCurrentUser();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Page emptyPage() {
        return new Page(Collections.emptyList(), false, null);
    }
}
