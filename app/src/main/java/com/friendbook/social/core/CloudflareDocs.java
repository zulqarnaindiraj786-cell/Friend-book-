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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The Cloudflare D1 read path for app documents (`/v1/docs` on the Worker).
 *
 * Firebase stays authentication only: the Firebase ID token is the bearer credential and the Worker
 * owns both the rows and the visibility rules. This client is the *first* source for every read on
 * a migrated path (feed, reels, profile, chat, notifications), with the pre-migration source kept
 * as a union fallback so not a single document disappears while the history is still in Firestore.
 *
 * The query surface mirrors what the app already used - a newest-first page of N documents, an
 * optional field filter/equality set, and a cursor for the next page - so callers keep their paging
 * model instead of adopting a new one:
 *
 *   D1.read("posts", 15, Query.orderDesc("createdAt").cursor(lastId), docs -> ...)
 *
 * Cursors are document ids, exactly like the Firestore snapshot the app used to hold, and the
 * Worker answers `hasMore` instead of the client guessing from a short page.
 */
public final class CloudflareDocs {

    private CloudflareDocs() {}

    private static final String BASE =
            "https://rough-firefly-dd7b.zulqarnaindiraj786.workers.dev/v1/docs";
    /** Mirrors are metadata only; anything bigger belongs in R2 or is a duplicate of an embed. */
    private static final int MAX_MIRROR_BYTES = 24 * 1024;
    private static final int MAX_LIST_IDS = 50;
    private static final long READ_CACHE_TTL_MS = 30_000L;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cf-docs");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Post-id -> (reel/…) lookups repeated many times per screen: a tiny short-lived cache. */
    private static final android.util.LruCache<String, CachedPage> READ_CACHE = new android.util.LruCache<>(48);

    /** Staged behind a build flag so a batch can be switched off without a release on the store. */
    public static boolean enabled() {
        return BuildConfig.CLOUDFLARE_DOCS_MIRROR;
    }

    /** One page of documents plus the cursor that addresses the next one. */
    public static final class Page {
        public final List<Map<String, Object>> documents;
        /** Id of the last document in this page, or null when the page is empty. */
        public final String cursor;
        public final boolean hasMore;
        public Page(List<Map<String, Object>> documents, String cursor, boolean hasMore) {
            this.documents = documents == null ? new ArrayList<>() : documents;
            this.cursor = cursor;
            this.hasMore = hasMore;
        }
    }

    /** What to read: order field, direction, cursor and the visibility filters. */
    public static final class Query {
        String order = "updatedAt";
        boolean ascending = false;
        String cursor = "";
        boolean mine = false;
        boolean includePrivate = false;
        boolean includeArchived = false;
        final List<String> filters = new ArrayList<>();

        public static Query newest() { return new Query(); }
        public static Query orderDesc(String field) {
            Query q = new Query();
            q.order = field == null || field.isEmpty() ? "updatedAt" : field;
            return q;
        }
        public static Query orderAsc(String field) {
            Query q = orderDesc(field);
            q.ascending = true;
            return q;
        }
        public Query cursor(String id) { this.cursor = id == null ? "" : id; return this; }
        /** Only the signed-in user's own documents (enforced by the Worker from the stored row). */
        public Query mineOnly() { this.mine = true; return this; }
        public Query withPrivate() { this.includePrivate = true; return this; }
        public Query withArchived() { this.includeArchived = true; return this; }
        /** Field equality, e.g. `where("authorId", uid)` - the Worker applies it in SQL. */
        public Query where(String field, String value) {
            if (field != null && value != null && !field.isEmpty()) filters.add(field + ":eq:" + clean(value));
            return this;
        }
        /** Field membership, e.g. `whereIn("privacy", "Public", "Friends")`. */
        public Query whereIn(String field, String... values) {
            if (field == null || field.isEmpty() || values == null || values.length == 0) return this;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < values.length; i++) {
                if (values[i] == null) continue;
                if (sb.length() > 0) sb.append('|');
                sb.append(clean(values[i]));
            }
            if (sb.length() > 0) filters.add(field + ":in:" + sb);
            return this;
        }
        /** Numeric comparison, e.g. `whereGreater("createdAt", cutoffMillis)`. */
        public Query whereGreater(String field, long value) {
            if (field != null && !field.isEmpty()) filters.add(field + ":gt:" + value);
            return this;
        }
        String encode() {
            StringBuilder sb = new StringBuilder();
            sb.append("&order=").append(Uri.encode(order));
            sb.append("&dir=").append(ascending ? "asc" : "desc");
            if (!cursor.isEmpty()) sb.append("&cursor=").append(Uri.encode(cursor));
            if (mine) sb.append("&owner=1");
            if (includePrivate) sb.append("&private=1");
            if (includeArchived) sb.append("&archived=1");
            if (!filters.isEmpty()) sb.append("&where=").append(Uri.encode(android.text.TextUtils.join(",", filters)));
            return sb.toString();
        }
        /** `:` separates the filter parts and `,` separates filters: neither may appear in a value. */
        private static String clean(String value) {
            return value.replace(":", "\u003a").replace(",", "\u002c").replace("|", "\u007c");
        }
    }

    private static final class CachedPage {
        final Page page;
        final long at;
        CachedPage(Page page, long at) { this.page = page; this.at = at; }
    }

    /**
     * Mirrors one document to Cloudflare D1 (merge-upsert on the Worker). Fire-and-forget on a
     * background thread: the caller keeps using its current write path exactly as before.
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
                D1HttpTransport.send(new URL(BASE + path), "PUT", token, body);
                READ_CACHE.evictAll();
            } catch (Throwable ignored) {
                // Silent by design: the caller's own write already succeeded.
            }
        }, null);
    }

    /** One page of mirrored documents, newest first (the pre-query-engine behaviour, kept). */
    public static void list(String collection, int limit, Consumer<List<Map<String, Object>>> callback) {
        read(collection, limit, Query.newest(), page -> {
            if (callback != null) callback.accept(page.documents);
        });
    }

    /**
     * Reads one page of D1 documents. The callback always runs on the main thread, receives an empty
     * page when the Worker is unreachable or the flag is off, and never throws.
     */
    public static void read(String collection, int limit, Query query, Consumer<Page> callback) {
        if (callback == null) return;
        final int bounded = Math.max(1, Math.min(MAX_LIST_IDS, limit));
        final Query q = query == null ? Query.newest() : query;
        if (!enabled() || collection == null || collection.isEmpty()) {
            MAIN.post(() -> callback.accept(new Page(new ArrayList<>(), null, false)));
            return;
        }
        final String cacheKey = collection + ':' + bounded + q.encode();
        long now = android.os.SystemClock.elapsedRealtime();
        // Only read-only, non-user-scoped pages are memoised, and never past the short TTL.
        boolean cacheable = !q.mine && !q.includePrivate && q.filters.isEmpty() && q.cursor.isEmpty();
        if (cacheable) {
            CachedPage hit = READ_CACHE.get(cacheKey);
            if (hit != null && (now - hit.at) < READ_CACHE_TTL_MS) {
                MAIN.post(() -> callback.accept(hit.page));
                return;
            }
        }
        final String url = BASE + "?collection=" + Uri.encode(collection) + "&limit=" + bounded + q.encode();
        withToken(token -> {
            Page page = new Page(new ArrayList<>(), null, false);
            try {
                JSONObject data = D1HttpTransport.send(new URL(url), "GET", token, null);
                JSONArray arr = data != null ? data.optJSONArray("documents") : null;
                List<Map<String, Object>> docs = arr != null ? D1Client.maps(arr) : new ArrayList<>();
                String cursor = data != null && data.optString("cursor", "").length() > 0
                        ? data.optString("cursor") : null;
                boolean hasMore = data != null && data.optBoolean("hasMore", false);
                page = new Page(docs, cursor, hasMore);
            } catch (Throwable ignored) {
                page = new Page(new ArrayList<>(), null, false);
            }
            final Page result = page;
            if (cacheable && !result.documents.isEmpty()) {
                READ_CACHE.put(cacheKey, new CachedPage(result, android.os.SystemClock.elapsedRealtime()));
            }
            MAIN.post(() -> callback.accept(result));
        }, () -> MAIN.post(() -> callback.accept(new Page(new ArrayList<>(), null, false))));
    }

    /** One document by id, or null when the mirror does not hold it (caller falls back). */
    public static void get(String collection, String id, Consumer<Map<String, Object>> callback) {
        if (callback == null) return;
        if (!enabled() || collection == null || collection.isEmpty() || id == null || id.isEmpty()) {
            MAIN.post(() -> callback.accept(null));
            return;
        }
        final String url = BASE + '/' + Uri.encode(collection) + '/' + Uri.encode(id);
        withToken(token -> {
            Map<String, Object> doc = null;
            try {
                JSONObject data = D1HttpTransport.send(new URL(url), "GET", token, null);
                JSONObject document = data != null ? data.optJSONObject("document") : null;
                if (document != null) {
                    JSONArray one = new JSONArray().put(document);
                    List<Map<String, Object>> docs = D1Client.maps(one);
                    if (!docs.isEmpty()) doc = docs.get(0);
                }
            } catch (Throwable ignored) {
                doc = null;
            }
            final Map<String, Object> result = doc;
            MAIN.post(() -> callback.accept(result));
        }, () -> MAIN.post(() -> callback.accept(null)));
    }

    private interface TokenWork {
        void run(String token);
    }

    /**
     * Hands a fresh Firebase ID token to `work` on the background thread. Mirrors and reads are
     * best-effort, so "no token" (signed out, account switch, queue rejected) simply means "nothing".
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
                        // Never read or mirror across an account switch.
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
            if (v instanceof FieldValue) {
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
        if (timestamp && !out.has("createdAt")) {
            try { out.put("createdAt", now); } catch (Throwable ignored) { }
        }
        return out;
    }

    /** JSON-safe view of one value: nested maps/lists are cleaned, sentinels dropped. */
    private static Object clean(Object v) {
        if (v == null) return null;
        if (v instanceof FieldValue) return null;
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
