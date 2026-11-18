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
    private final ExecutorService executor = new java.util.concurrent.ThreadPoolExecutor(
            4, 8, 30, TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(256),
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
        final EventListener<QuerySnapshot> listener;
        volatile boolean active = true;
        volatile String lastFingerprint = "";
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
            if (e.active && path.startsWith("chats/") && path.endsWith("/messages")) {
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

    private static String authenticatedUid() {
        com.google.firebase.auth.FirebaseUser user = com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
        return user == null ? "signed-out" : user.getUid();
    }

    private static String scopedCacheKey(String uid, String path) { return uid + "|" + path; }

    void cacheDoc(String path, DocumentSnapshot snap) {
        if (path != null && snap != null) docCache.put(scopedCacheKey(authenticatedUid(), path), snap);
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
        if (cached != null) mainHandler.post(() -> { if (entry.active) listener.onEvent(cached, null); });
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
        String key = queryCacheKey(query);
        String cachedJson = getCachedQuery(key);
        if (cachedJson != null) {
            try {
                org.json.JSONArray cached = new org.json.JSONArray(cachedJson);
                entry.lastFingerprint = cached.toString();
                QuerySnapshot snapshot = query.parseQuerySnapshot(cached, true);
                mainHandler.post(() -> { if (entry.active) listener.onEvent(snapshot, null); });
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
        for (QueryListenerEntry e : queryListeners) scheduleQueryRead(e);
    }

    private void scheduleDocRead(DocListenerEntry entry) {
        if (!entry.active || !entry.inFlight.compareAndSet(false, true)) return;
        try { executor.execute(() -> deliverDoc(entry)); }
        catch (java.util.concurrent.RejectedExecutionException rejected) { entry.inFlight.set(false); }
    }

    private void scheduleQueryRead(QueryListenerEntry entry) {
        if (!entry.active || !entry.inFlight.compareAndSet(false, true)) return;
        try { executor.execute(() -> deliverQuery(entry)); }
        catch (java.util.concurrent.RejectedExecutionException rejected) { entry.inFlight.set(false); }
    }

    private void deliverDoc(DocListenerEntry entry) {
        if (!entry.active) { entry.inFlight.set(false); return; }
        try {
            DocumentSnapshot snap = entry.ref.parseSnapshot(D1DocumentTransport.getDocument(entry.ref.getPath()));
            cacheDoc(entry.ref.getPath(), snap);
            long ut = snap.getUpdateTime() != null ? snap.getUpdateTime().toDate().getTime() : 0L;
            if (entry.lastUpdate == ut && entry.lastExists == snap.exists() && entry.lastUpdate != -1L) return;
            entry.lastUpdate = ut;
            entry.lastExists = snap.exists();
            mainHandler.post(() -> {
                if (entry.active) entry.listener.onEvent(snap, null);
            });
        } catch (Exception ex) {
            D1DocumentStoreException ffe = ex instanceof D1DocumentStoreException
                    ? (D1DocumentStoreException) ex
                    : new D1DocumentStoreException(D1DocumentStoreException.Code.UNAVAILABLE, ex.getMessage(), ex);
            mainHandler.post(() -> {
                if (entry.active) entry.listener.onEvent(null, ffe);
            });
        } finally {
            entry.inFlight.set(false);
        }
    }

    private void deliverQuery(QueryListenerEntry entry) {
        if (!entry.active) { entry.inFlight.set(false); return; }
        try {
            org.json.JSONArray arr = D1DocumentTransport.query(toQueryJson(entry.query));
            String fp = arr.toString();
            cacheQuery(queryCacheKey(entry.query), fp);
            if (fp.equals(entry.lastFingerprint)) return;
            entry.lastFingerprint = fp;
            QuerySnapshot snap = entry.query.parseQuerySnapshot(arr);
            mainHandler.post(() -> {
                if (entry.active) entry.listener.onEvent(snap, null);
            });
        } catch (Exception ex) {
            D1DocumentStoreException ffe = ex instanceof D1DocumentStoreException
                    ? (D1DocumentStoreException) ex
                    : new D1DocumentStoreException(D1DocumentStoreException.Code.UNAVAILABLE, ex.getMessage(), ex);
            mainHandler.post(() -> {
                if (entry.active) entry.listener.onEvent(null, ffe);
            });
        } finally {
            entry.inFlight.set(false);
        }
    }

    private String getCachedQuery(String key) {
        String cached = queryCache.get(key);
        if (cached != null) return cached;
        com.friendbook.social.App app = com.friendbook.social.App.instance;
        if (app == null) return null;
        String prefKey = "q_" + cacheHash(key);
        cached = app.getSharedPreferences("d1_query_cache_v1", android.content.Context.MODE_PRIVATE).getString(prefKey, null);
        if (cached != null) queryCache.put(key, cached);
        return cached;
    }

    private void cacheQuery(String key, String json) {
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

    private static String queryCacheKey(Query q) {
        return authenticatedUid() + "|" + toQueryJson(q).toString();
    }

    private static org.json.JSONObject toQueryJson(Query q) {
        org.json.JSONObject body = new org.json.JSONObject();
        try {
            body.put("parent", q.parentPath);
            body.put("filters", q.filtersJson());
            org.json.JSONArray ord = new org.json.JSONArray();
            for (Query.OrderSpec os : q.orders) {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("field", os.field);
                o.put("direction", os.dir == Query.Direction.DESCENDING ? "DESCENDING" : "ASCENDING");
                ord.put(o);
            }
            body.put("orderBy", ord);
            if (q.limitValue != null) body.put("limit", q.limitValue);
            if (q.limitToLastFlag) body.put("limitToLast", true);
        } catch (Exception ignored) { }
        return body;
    }
}
