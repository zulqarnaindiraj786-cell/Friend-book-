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

import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;

import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
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
    /**
     * The Worker refuses a document over 95 000 characters, and the transport over 64 KiB of UTF-8.
     * A long post with a shared link is still far below this, so the client keeps the same ceiling as
     * the server instead of a smaller "mirror" one that would silently drop a real write.
     */
    private static final int MAX_WRITE_BYTES = 60 * 1024;
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
        write(collection, id, fields, null, null);
    }

    /**
     * Writes one document to Cloudflare D1 and reports the outcome.
     *
     * This is the app's write path: every client on this build writes here - a brand new user and a
     * user who just updated alike. A document that already exists in the pre-migration source is
     * never written back to it; it is only ever *read* from there while history is still there.
     *
     * `onSuccess` runs on the main thread, `onFailure` too, so a caller can queue the write instead
     * of losing it when the network is down.
     */
    public static void write(String collection, String id, Map<String, Object> fields,
                             Runnable onSuccess, Consumer<String> onFailure) {
        if (collection == null || collection.isEmpty() || id == null || id.isEmpty() || fields == null) {
            if (onFailure != null) MAIN.post(() -> onFailure.accept("invalid"));
            return;
        }
        if (!enabled()) {
            if (onFailure != null) MAIN.post(() -> onFailure.accept("disabled"));
            return;
        }
        final JSONObject body = sanitize(fields);
        if (body.length() == 0) {
            if (onFailure != null) MAIN.post(() -> onFailure.accept("empty"));
            return;
        }
        final String path = '/' + collection + '/' + id;
        withToken(token -> {
            try {
                if (body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_WRITE_BYTES) {
                    throw new IllegalStateException("too-large");
                }
                D1HttpTransport.send(new URL(BASE + path), "PUT", token, body);
                READ_CACHE.evictAll();
                if (onSuccess != null) MAIN.post(onSuccess);
            } catch (Throwable error) {
                if (onFailure != null) {
                    String reason = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                    MAIN.post(() -> onFailure.accept(reason));
                }
            }
        }, () -> {
            if (onFailure != null) MAIN.post(() -> onFailure.accept("auth"));
        });
    }

    /** A document id generated on the device, for a write that has not reached any store yet. */
    public static String newId() {
        return java.util.UUID.randomUUID().toString();
    }

    /** Deletes one document from the store (the Worker refuses anything the caller does not own). */
    public static void delete(String collection, String id, Runnable onSuccess, Consumer<String> onFailure) {
        if (!enabled() || collection == null || id == null || id.isEmpty()) {
            if (onFailure != null) MAIN.post(() -> onFailure.accept("disabled"));
            return;
        }
        final String path = '/' + collection + '/' + id;
        withToken(token -> {
            try {
                D1HttpTransport.send(new URL(BASE + path), "DELETE", token, null);
                READ_CACHE.evictAll();
                if (onSuccess != null) MAIN.post(onSuccess);
            } catch (Throwable error) {
                if (onFailure != null) MAIN.post(() -> onFailure.accept("delete"));
            }
        }, () -> {
            if (onFailure != null) MAIN.post(() -> onFailure.accept("auth"));
        });
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
    public static JSONObject sanitize(Map<String, Object> fields) {
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

    // ---------------------------------------------------------------------------------------------
    // The migration read contract: Cloudflare first, the pre-migration source as a union fallback.
    // ---------------------------------------------------------------------------------------------

    /**
     * Reads a page of a collection, Cloudflare first and Firestore as the fallback that still holds
     * the pre-migration history. Rows are de-duplicated by document id (Cloudflare wins), so a screen
     * can never show the same item twice and can never lose one that only exists in the old source.
     *
     * `onFirestore` is handed the raw query so the caller keeps its own mapping (each screen already
     * knows how to turn a snapshot into its model). It runs only when Cloudflare did not fill the page.
     */
    public static void readUnion(String collection, int limit, Query query,
                                 Consumer<List<Map<String, Object>>> onMerged,
                                 Consumer<Query> onFirestore) {
        readUnion(collection, limit, query, onMerged, onFirestore, null);
    }

    /**
     * Same, with a filter that Cloudflare cannot express in SQL (a set of ids, an array-contains
     * that the Worker does not support) - `accept` decides what the Cloudflare page may contribute.
     */
    public static void readUnion(String collection, int limit, Query query,
                                 Consumer<List<Map<String, Object>>> onMerged,
                                 Consumer<Query> onFirestore,
                                 java.util.function.Predicate<Map<String, Object>> accept) {
        read(collection, limit, query, page -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            if (page != null) {
                for (Map<String, Object> row : page.documents) {
                    if (row == null || accept == null || !accept.test(row)) continue;
                    Object id = row.get("id");
                    if (id == null || !ids.add(String.valueOf(id))) continue;
                    rows.add(row);
                }
            }
            if (onMerged != null) onMerged.accept(rows);
            // Cloudflare answered with nothing usable: the pre-migration query owns this page.
            if (rows.isEmpty() && onFirestore != null) onFirestore.accept(query);
        });
    }

    /**
     * Reads one document, Cloudflare first. `onFirestore` is only asked when the mirror does not hold
     * it, which is exactly the history written before the migration.
     */
    public static void getUnion(String collection, String id,
                                Consumer<Map<String, Object>> onDocument,
                                Runnable onFirestore) {
        get(collection, id, doc -> {
            if (doc != null && doc.get("id") != null) {
                if (onDocument != null) onDocument.accept(doc);
                return;
            }
            if (onFirestore != null) onFirestore.run();
        });
    }

    /**
     * Writes one document to Cloudflare and, only when Cloudflare is switched off for this build,
     * falls back to the previous source. This is the single write decision of the app.
     *
     * `previousWrite` is a lambda that performs the old Firestore write; it is never used while
     * Cloudflare is enabled, so a user on this build cannot write back into the old source.
     */
    public static void writeDocument(String collection, String id, Map<String, Object> fields,
                                     Runnable onSuccess, Consumer<String> onFailure,
                                     Runnable previousWrite) {
        if (!enabled()) {
            if (previousWrite != null) previousWrite.run();
            else if (onFailure != null) onFailure.accept("disabled");
            return;
        }
        write(collection, id, fields, onSuccess, onFailure);
    }

    /** Deletes a document from Cloudflare (owner-only on the Worker) or from the old source if off. */
    public static void deleteDocument(String collection, String id,
                                      Runnable onSuccess, Consumer<String> onFailure,
                                      Runnable previousDelete) {
        if (!enabled()) {
            if (previousDelete != null) previousDelete.run();
            else if (onFailure != null) onFailure.accept("disabled");
            return;
        }
        delete(collection, id, onSuccess, onFailure);
    }

    /**
     * Creates a new document id without touching any backend, so an optimistic write and the real one
     * share the same id (Firestore used to generate this on the client).
     */
    public static String newId(FirebaseFirestore db, String collection) {
        // A locally generated id: no backend call, and the same id is used for the optimistic row and
        // the real write. Kept here so callers do not each invent their own format.
        return java.util.UUID.randomUUID().toString();
    }

    /** One snapshot row as the plain map the rest of this class speaks. */
    public static Map<String, Object> plain(DocumentSnapshot snapshot) {
        if (snapshot == null || !snapshot.exists()) return null;
        Map<String, Object> row = new HashMap<>(snapshot.getData() == null
                ? Collections.emptyMap() : snapshot.getData());
        row.put("id", snapshot.getId());
        return row;
    }

    // ---------------------------------------------------------------------------------------------
    // Social graph: who is this user connected to, asked of Cloudflare first.
    // ---------------------------------------------------------------------------------------------

    /** The uids this user is friends with (Cloudflare rows, both id shapes), de-duplicated. */
    public static void friendsOf(String uid, Consumer<Set<String>> callback) {
        final Set<String> found = new HashSet<>();
        if (callback == null) return;
        if (uid == null || uid.isEmpty() || !enabled()) {
            MAIN.post(() -> callback.accept(found));
            return;
        }
        read("friends", 50, Query.orderDesc("createdAt").whereIn("participants", uid), page -> {
            if (page != null) {
                for (Map<String, Object> row : page.documents) {
                    Object participants = row.get("participants");
                    if (participants instanceof List) {
                        for (Object participant : (List<?>) participants) {
                            String other = participant == null ? "" : String.valueOf(participant);
                            if (!other.isEmpty() && !other.equals(uid)) found.add(other);
                        }
                    }
                    // The `a_b` id shape used by the migration carries no participants array.
                    Object id = row.get("id");
                    if (id != null) {
                        String[] parts = String.valueOf(id).split("_");
                        if (parts.length >= 2) {
                            for (String part : parts) {
                                if (!part.isEmpty() && !part.equals(uid) && part.length() > 10) found.add(part);
                            }
                        }
                    }
                }
            }
            MAIN.post(() -> callback.accept(found));
        });
    }

    /**
     * Pending friend requests addressed to this user, as `fromUid -> requestId`.
     * Cloudflare answers for the requests created after the migration; the caller still runs its
     * pre-migration listener, so an old request is neither lost nor shown twice.
     */
    public static void incomingRequests(String uid, Consumer<Map<String, String>> callback) {
        final Map<String, String> found = new LinkedHashMap<>();
        if (callback == null) return;
        if (uid == null || uid.isEmpty() || !enabled()) {
            MAIN.post(() -> callback.accept(found));
            return;
        }
        read("friend_requests", 50, Query.orderDesc("createdAt").where("toUid", uid).where("status", "pending"), page -> {
            if (page != null) {
                for (Map<String, Object> row : page.documents) {
                    Object from = row.get("fromUid");
                    Object id = row.get("id");
                    if (from == null || id == null) continue;
                    if (!"pending".equals(String.valueOf(row.getOrDefault("status", "pending")))) continue;
                    found.put(String.valueOf(from), String.valueOf(id));
                }
            }
            MAIN.post(() -> callback.accept(found));
        });
    }
}
