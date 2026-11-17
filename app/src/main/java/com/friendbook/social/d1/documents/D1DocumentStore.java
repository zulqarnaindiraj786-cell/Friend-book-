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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Cloudflare D1-backed implementation of D1DocumentStore.
 * Keeps the exact com.friendbook.social.d1.documents API so all ~80k lines of UI/feature
 * code work unchanged while 100% of app data lives in Cloudflare D1.
 */
public class D1DocumentStore {
    private static final D1DocumentStore INSTANCE = new D1DocumentStore();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, DocumentSnapshot> docCache = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<DocListenerEntry>> docListeners = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<QueryListenerEntry> queryListeners = new CopyOnWriteArrayList<>();

    private static final class DocListenerEntry {
        final DocumentReference ref;
        final EventListener<DocumentSnapshot> listener;
        volatile boolean active = true;
        volatile long lastUpdate = -1L;
        volatile boolean lastExists = false;

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

        QueryListenerEntry(Query query, EventListener<QuerySnapshot> listener) {
            this.query = query;
            this.listener = listener;
        }
    }

    private D1DocumentStore() {
        poller.scheduleWithFixedDelay(this::pollActiveListeners, 5, 5, TimeUnit.SECONDS);
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
        executor.execute(() -> {
            try {
                tcs.setResult(task.call());
            } catch (Exception e) {
                tcs.setException(e);
            }
        });
        return tcs.getTask();
    }

    void cacheDoc(String path, DocumentSnapshot snap) {
        if (path != null && snap != null) docCache.put(path, snap);
    }

    DocumentSnapshot getCachedDoc(String path) {
        return path == null ? null : docCache.get(path);
    }

    void invalidateDoc(String path) {
        if (path != null) docCache.remove(path);
    }

    ListenerRegistration registerDocListener(DocumentReference ref, EventListener<DocumentSnapshot> listener) {
        DocListenerEntry entry = new DocListenerEntry(ref, listener);
        docListeners.computeIfAbsent(ref.getPath(), k -> new CopyOnWriteArrayList<>()).add(entry);
        executor.execute(() -> deliverDoc(entry));
        return () -> {
            entry.active = false;
            CopyOnWriteArrayList<DocListenerEntry> list = docListeners.get(ref.getPath());
            if (list != null) list.remove(entry);
        };
    }

    ListenerRegistration registerQueryListener(Query query, EventListener<QuerySnapshot> listener) {
        QueryListenerEntry entry = new QueryListenerEntry(query, listener);
        queryListeners.add(entry);
        executor.execute(() -> deliverQuery(entry));
        return () -> {
            entry.active = false;
            queryListeners.remove(entry);
        };
    }

    void notifyDocListeners(String path) {
        CopyOnWriteArrayList<DocListenerEntry> list = docListeners.get(path);
        if (list != null) {
            for (DocListenerEntry e : list) {
                if (e.active) executor.execute(() -> deliverDoc(e));
            }
        }
        int idx = path.lastIndexOf('/');
        if (idx > 0) notifyCollectionListeners(path.substring(0, idx));
    }

    void notifyCollectionListeners(String parentPath) {
        for (QueryListenerEntry e : queryListeners) {
            if (e.active && parentPath.equals(e.query.parentPath)) {
                executor.execute(() -> deliverQuery(e));
            }
        }
    }

    private void pollActiveListeners() {
        for (CopyOnWriteArrayList<DocListenerEntry> list : docListeners.values()) {
            for (DocListenerEntry e : list) {
                if (e.active) deliverDoc(e);
            }
        }
        for (QueryListenerEntry e : queryListeners) {
            if (e.active) deliverQuery(e);
        }
    }

    private void deliverDoc(DocListenerEntry entry) {
        if (!entry.active) return;
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
        }
    }

    private void deliverQuery(QueryListenerEntry entry) {
        if (!entry.active) return;
        try {
            org.json.JSONArray arr = D1DocumentTransport.query(toQueryJson(entry.query));
            String fp = arr.toString();
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
        }
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
