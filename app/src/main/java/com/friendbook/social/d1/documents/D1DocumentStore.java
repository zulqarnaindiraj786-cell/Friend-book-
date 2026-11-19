package com.friendbook.social.d1.documents;

import android.os.Handler;
import android.os.Looper;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Cloudflare D1-backed implementation of D1DocumentStore.
 * Keeps the exact com.friendbook.social.d1.documents API so all ~80k lines of UI/feature
 * code work unchanged while 100% of app data lives in Cloudflare D1.
 */
public class D1DocumentStore {
    private static final D1DocumentStore INSTANCE = new D1DocumentStore();
    // Keep network work bounded: a cached pool can create one thread per slow request on flaky
    // mobile networks, which starves the UI and battery. Polls use the same bounded pool and each
    // listener has an in-flight guard so a slow response cannot pile up every five seconds.
    // A non-full queue prevents ThreadPoolExecutor from growing past its core size.
    // Use all eight workers immediately instead of silently running only four while
    // foreground screen requests wait behind slow network calls.
    private final ExecutorService executor = new java.util.concurrent.ThreadPoolExecutor(
            8, 8, 30, TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(256),
            new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, DocumentSnapshot> docCache = new ConcurrentHashMap<>();
    // Warm in-process cache for revisited screens. Query keys include the authenticated UID to
    // prevent one account's cached results being shown after an account switch.
    private final Map<String, String> queryCache = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<DocListenerEntry>> docListeners = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<QueryListenerEntry> queryListeners = new CopyOnWriteArrayList<>();

    private static final class DocListenerEntry {
        final DocumentReference ref;
        final String actor = authenticatedUid();
        final EventListener<DocumentSnapshot> listener;
        volatile boolean active = true;
        volatile long lastUpdate = -1L;
        volatile boolean lastExists = false;
        final AtomicBoolean inFlight = new AtomicBoolean(false);

        DocListenerEntry(DocumentReference ref, EventListener<DocumentSnapshot> listener) {
            this.ref = ref;
            this.listener = listener;
        }
    }

    private static final class QueryListenerEntry {
        final Query query;
        final String actor = authenticatedUid();
        final EventListener<QuerySnapshot> listener;
        volatile boolean active = true;
        final QueryDeliveryState deliveryState = new QueryDeliveryState();
        final AtomicBoolean inFlight = new AtomicBoolean(false);

        QueryListenerEntry(Query query, EventListener<QuerySnapshot> listener) {
            this.query = query;
            this.listener = listener;
        }
    }

    private D1DocumentStore() {
        poller.scheduleWithFixedDelay(this::pollActiveListeners, 5, 5, TimeUnit.SECONDS);
        // Only active chat message queries need the shorter interval. Keep other listeners at
        // five seconds so a faster chat does not multiply traffic for the whole app.
        poller.scheduleWithFixedDelay(this::pollChatMessages, 2, 2, TimeUnit.SECONDS);
    }

    private void pollChatMessages() {
        for (QueryListenerEntry e : queryListeners) {
            String path = e.query.parentPath;
            if (e.active && ListenerPollingPolicy.isChatMessages(path)) {
                scheduleQueryRead(e);
            }
        }
    }

    public static D1DocumentStore getInstance() {
        return INSTANCE;
    }

    public void setFirestoreSettings(D1DocumentStoreSettings settings) {
        // No-op: D1 is the source of truth.
    }

    public CollectionReference collection(String collectionPath) {
        return new CollectionReference(this, collectionPath);
    }

    public DocumentReference document(String documentPath) {
        return new DocumentReference(this, documentPath);
    }

    public WriteBatch batch() {
        return new WriteBatch(this);
    }

    /** One authenticated profile header + first post page; individual listeners remain authoritative. */
    public Task<org.json.JSONObject> fetchProfileBootstrap(String uid) {
        final String actor=authenticatedUid();
        return runAsync(() -> {
            org.json.JSONObject result=D1DocumentTransport.request("GET", "/v1/firestore/profile-bootstrap?uid="
                    + D1DocumentTransport.enc(uid), null);
            if (!actor.equals(authenticatedUid())) throw new D1DocumentStoreException(
                    D1DocumentStoreException.Code.CANCELLED, "Account changed during profile read");
            return result;
        });
    }

    /** Seed account-scoped caches only while the same profile request is still active. */
    public void cacheProfileBootstrap(String uid, String actor, org.json.JSONObject result) {
        if (result==null || !actor.equals(authenticatedUid())) return;
        org.json.JSONObject profile=result.optJSONObject("profile"), posts=result.optJSONObject("posts");
        if (profile!=null && profile.optInt("status")==200 && profile.optJSONObject("body")!=null) {
            DocumentReference ref=collection("users").document(uid);
            cacheDoc(ref.getPath(),ref.parseSnapshot(profile.optJSONObject("body")),actor);
        }
        if (posts!=null && posts.optInt("status")==200 && posts.optJSONObject("body")!=null) {
            org.json.JSONArray rows=posts.optJSONObject("body").optJSONArray("documents");
            if (rows!=null) {
                Query query=collection("posts").whereEqualTo("authorId",uid)
                        .orderBy("createdAt", Query.Direction.DESCENDING).limit(7);
                cacheQuery(queryCacheKey(query,actor),rows.toString());
            }
        }
    }

    /** Moderation is authorized by the Worker using the verified admin UID, not UI state. */
    public Task<Void> moderate(String action, String collection, String id) {
        final String actor=authenticatedUid();
        return runAsync(() -> {
            if (!actor.equals(authenticatedUid())) throw new D1DocumentStoreException(
                    D1DocumentStoreException.Code.CANCELLED, "Account changed before moderation");
            D1DocumentTransport.request("POST", "/v1/admin/moderation",
                    new org.json.JSONObject().put("action",action).put("collection",collection).put("id",id));
            if (!actor.equals(authenticatedUid())) throw new D1DocumentStoreException(
                    D1DocumentStoreException.Code.CANCELLED, "Account changed during moderation");
            invalidateDoc(collection + "/" + id);
            notifyDocListeners(collection + "/" + id);
            notifyCollectionListeners(collection);
            return null;
        });
    }

    /** Server verifies the signed-in administrator; the UI is not an authorization boundary. */
    public Task<org.json.JSONObject> previewSocialRecovery(String kind, String id) {
        return runAsync(() -> D1DocumentTransport.request("GET", "/v1/admin/recover-social?kind="
                + D1DocumentTransport.enc(kind) + "&id=" + D1DocumentTransport.enc(id), null));
    }

    public Task<org.json.JSONObject> confirmSocialRecovery(String kind, String id, String revision, String owner) {
        return runAsync(() -> D1DocumentTransport.request("POST", "/v1/admin/recover-social",
                new org.json.JSONObject().put("kind", kind).put("id", id).put("revision", revision)
                        .put("owner", owner).put("confirm", true)));
    }

    public <TResult> Task<TResult> runTransaction(Transaction.Function<TResult> updateFunction) {
        return runAsync(() -> {
            Transaction tx = new Transaction(this);
            TResult result = updateFunction.apply(tx);
            tx.commit();
            return result;
        });
    }

    <T> Task<T> runAsync(Callable<T> task) {
        TaskCompletionSource<T> tcs = new TaskCompletionSource<>();
        try {
            executor.execute(() -> {
                try {
                    tcs.setResult(task.call());
                } catch (Exception e) {
                    tcs.setException(e);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            tcs.setException(new D1DocumentStoreException(D1DocumentStoreException.Code.UNAVAILABLE,
                    "Too many requests are already in progress", rejected));
        }
        return tcs.getTask();
    }

    static String authenticatedUid() {
        com.google.firebase.auth.FirebaseUser user = com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
        return user == null ? "signed-out" : user.getUid();
    }

    private static String scopedCacheKey(String uid, String path) { return uid + "|" + path; }

    void cacheDoc(String path, DocumentSnapshot snap) {
        cacheDoc(path, snap, authenticatedUid());
    }
    void cacheDoc(String path, DocumentSnapshot snap, String actor) {
        if (path != null && snap != null) docCache.put(scopedCacheKey(actor, path), snap);
    }

    DocumentSnapshot getCachedDoc(String path) {
        return path == null ? null : docCache.get(scopedCacheKey(authenticatedUid(), path));
    }

    void invalidateDoc(String path) {
        if (path != null) docCache.remove(scopedCacheKey(authenticatedUid(), path));
    }

    ListenerRegistration registerDocListener(DocumentReference ref, EventListener<DocumentSnapshot> listener) {
        DocListenerEntry entry = new DocListenerEntry(ref, listener);
        docListeners.computeIfAbsent(ref.getPath(), k -> new CopyOnWriteArrayList<>()).add(entry);
        DocumentSnapshot cached = getCachedDoc(ref.getPath());
        if (cached != null) mainHandler.post(() -> { if (entry.active && entry.actor.equals(authenticatedUid())) listener.onEvent(cached.asCached(), null); });
        scheduleDocRead(entry);
        return () -> {
            entry.active = false;
            CopyOnWriteArrayList<DocListenerEntry> list = docListeners.get(ref.getPath());
            if (list != null) list.remove(entry);
        };
    }

    ListenerRegistration registerQueryListener(Query query, EventListener<QuerySnapshot> listener) {
        QueryListenerEntry entry = new QueryListenerEntry(query, listener);
        queryListeners.add(entry);
        String key = queryCacheKey(query, entry.actor);
        String cachedJson = getCachedQuery(key);
        if (cachedJson != null) {
            try {
                org.json.JSONArray cached = new org.json.JSONArray(cachedJson);
                entry.deliveryState.cached(cached.toString());
                QuerySnapshot snapshot = query.parseQuerySnapshot(cached, true);
                mainHandler.post(() -> { if (entry.active && entry.actor.equals(authenticatedUid())) listener.onEvent(snapshot, null); });
            } catch (Exception ignored) { }
        }
        scheduleQueryRead(entry);
        return () -> {
            entry.active = false;
            queryListeners.remove(entry);
        };
    }

    void notifyDocListeners(String path) {
        CopyOnWriteArrayList<DocListenerEntry> list = docListeners.get(path);
        if (list != null) {
            for (DocListenerEntry e : list) {
                scheduleDocRead(e);
            }
        }
        int idx = path.lastIndexOf('/');
        if (idx > 0) notifyCollectionListeners(path.substring(0, idx));
    }

    /** FCM is only an invalidation hint; the authenticated D1 query stays authoritative. */
    public void refreshChatFromPush(String chatId) {
        if (chatId == null || !chatId.matches("[A-Za-z0-9_-]{1,128}")) return;
        notifyCollectionListeners("chats/" + chatId + "/messages");
    }

    void notifyCollectionListeners(String parentPath) {
        for (QueryListenerEntry e : queryListeners) {
            if (e.active && parentPath.equals(e.query.parentPath)) {
                scheduleQueryRead(e);
            }
        }
    }

    private void pollActiveListeners() {
        // Never perform HTTP on the single scheduled executor. A slow request there used to block
        // every remaining listener (and the next poll cycle), making unrelated screens appear hung.
        for (CopyOnWriteArrayList<DocListenerEntry> list : docListeners.values()) {
            for (DocListenerEntry e : list) scheduleDocRead(e);
        }
        for (QueryListenerEntry e : queryListeners) {
            if (!ListenerPollingPolicy.isChatMessages(e.query.parentPath)) scheduleQueryRead(e);
        }
    }

    private void scheduleDocRead(DocListenerEntry entry) {
        if (!entry.active || !entry.actor.equals(authenticatedUid()) || !entry.inFlight.compareAndSet(false, true)) return;
        try { executor.execute(() -> deliverDoc(entry)); }
        catch (java.util.concurrent.RejectedExecutionException rejected) { entry.inFlight.set(false); }
    }

    private void scheduleQueryRead(QueryListenerEntry entry) {
        if (!entry.active || !entry.actor.equals(authenticatedUid()) || !entry.inFlight.compareAndSet(false, true)) return;
        try { executor.execute(() -> deliverQuery(entry)); }
        catch (java.util.concurrent.RejectedExecutionException rejected) { entry.inFlight.set(false); }
    }

    private void deliverDoc(DocListenerEntry entry) {
        if (!entry.active) { entry.inFlight.set(false); return; }
        try {
            DocumentSnapshot snap = entry.ref.parseSnapshot(D1DocumentTransport.getDocument(entry.ref.getPath()));
            if (!entry.actor.equals(authenticatedUid())) return;
            cacheDoc(entry.ref.getPath(), snap, entry.actor);
            long ut = snap.getUpdateTime() != null ? snap.getUpdateTime().toDate().getTime() : 0L;
            if (entry.lastUpdate == ut && entry.lastExists == snap.exists() && entry.lastUpdate != -1L) return;
            entry.lastUpdate = ut;
            entry.lastExists = snap.exists();
            mainHandler.post(() -> {
                if (entry.active && entry.actor.equals(authenticatedUid())) entry.listener.onEvent(snap, null);
            });
        } catch (Exception ex) {
            D1DocumentStoreException ffe = ex instanceof D1DocumentStoreException
                    ? (D1DocumentStoreException) ex
                    : new D1DocumentStoreException(D1DocumentStoreException.Code.UNAVAILABLE, ex.getMessage(), ex);
            mainHandler.post(() -> {
                if (entry.active && entry.actor.equals(authenticatedUid())) entry.listener.onEvent(null, ffe);
            });
        } finally {
            entry.inFlight.set(false);
        }
    }

    private void deliverQuery(QueryListenerEntry entry) {
        if (!entry.active) { entry.inFlight.set(false); return; }
        try {
            org.json.JSONArray arr = D1DocumentTransport.query(toQueryJson(entry.query));
            if (!entry.actor.equals(authenticatedUid())) return;
            String fp = arr.toString();
            cacheQuery(queryCacheKey(entry.query, entry.actor), fp);
            // Cached rows are not server confirmation: the first server result
            // must be delivered even when its contents match the cached snapshot.
            if (!entry.deliveryState.server(fp)) return;
            QuerySnapshot snap = entry.query.parseQuerySnapshot(arr);
            mainHandler.post(() -> {
                if (entry.active && entry.actor.equals(authenticatedUid())) entry.listener.onEvent(snap, null);
            });
        } catch (Exception ex) {
            entry.deliveryState.error();
            D1DocumentStoreException ffe = ex instanceof D1DocumentStoreException
                    ? (D1DocumentStoreException) ex
                    : new D1DocumentStoreException(D1DocumentStoreException.Code.UNAVAILABLE, ex.getMessage(), ex);
            mainHandler.post(() -> {
                if (entry.active && entry.actor.equals(authenticatedUid())) entry.listener.onEvent(null, ffe);
            });
        } finally {
            entry.inFlight.set(false);
        }
    }

    String getCachedQuery(String key) {
        String cached = queryCache.get(key);
        if (cached != null) return cached;
        com.friendbook.social.App app = com.friendbook.social.App.instance;
        if (app == null) return null;
        String prefKey = "q_" + cacheHash(key);
        cached = app.getSharedPreferences("d1_query_cache_v1", android.content.Context.MODE_PRIVATE).getString(prefKey, null);
        if (cached != null) queryCache.put(key, cached);
        return cached;
    }

    void cacheQuery(String key, String json) {
        queryCache.put(key, json);
        while (queryCache.size() > 32) {
            String evict = queryCache.keySet().iterator().next();
            queryCache.remove(evict);
            com.friendbook.social.App app = com.friendbook.social.App.instance;
            if (app != null) app.getSharedPreferences("d1_query_cache_v1", android.content.Context.MODE_PRIVATE)
                    .edit().remove("q_" + cacheHash(evict)).apply();
        }
        // Avoid putting exceptionally large result pages into preferences.
        if (json.length() <= 256 * 1024) {
            com.friendbook.social.App app = com.friendbook.social.App.instance;
            if (app != null) {
                android.content.SharedPreferences prefs = app.getSharedPreferences("d1_query_cache_v1", android.content.Context.MODE_PRIVATE);
                String prefKey = "q_" + cacheHash(key);
                java.util.Set<String> keys = new java.util.HashSet<>(prefs.getStringSet("keys", java.util.Collections.emptySet()));
                keys.add(prefKey);
                android.content.SharedPreferences.Editor editor = prefs.edit().putString(prefKey, json);
                while (keys.size() > 32) {
                    String evict = keys.iterator().next(); keys.remove(evict); editor.remove(evict);
                }
                editor.putStringSet("keys", keys).apply();
            }
        }
    }

    private static String cacheHash(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
            return hex.toString();
        } catch (Exception impossible) { return Integer.toHexString(value.hashCode()); }
    }

    static String queryCacheKey(Query q, String actor) {
        return actor + "|" + toQueryJson(q).toString();
    }

    // Use the same serialization as one-shot reads: cursors must affect both the request
    // and cache key, otherwise a listener for a later page replays the first page.
    static org.json.JSONObject toQueryJson(Query q) {
        return q.toQueryBody();
    }
}
