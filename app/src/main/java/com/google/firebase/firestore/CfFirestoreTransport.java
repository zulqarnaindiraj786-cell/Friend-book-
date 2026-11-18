package com.google.firebase.firestore;

import android.app.Activity;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;
import com.google.android.gms.tasks.Tasks;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class CfFirestoreTransport {

    static final class WriteOp {
        final String op;
        final String path;
        final Map<String, Object> data;
        final boolean merge;

        WriteOp(String op, String path, Map<String, Object> data, boolean merge) {
            this.op = op;
            this.path = cleanPath(path);
            this.data = data;
            this.merge = merge;
        }
    }

    private static final class CachedDoc {
        final boolean exists;
        final Map<String, Object> data;
        final String rawJson;

        CachedDoc(boolean exists, Map<String, Object> data, String rawJson) {
            this.exists = exists;
            this.data = data;
            this.rawJson = rawJson == null ? "" : rawJson;
        }
    }

    private static final class DocSub {
        final FirebaseFirestore firestore;
        final String path;
        final Activity activity;
        final EventListener<DocumentSnapshot> listener;
        volatile boolean removed;
        volatile boolean deliveredServer;
        volatile String lastFingerprint;
        volatile long lastPolledAt;

        DocSub(FirebaseFirestore firestore, String path, Activity activity, EventListener<DocumentSnapshot> listener) {
            this.firestore = firestore;
            this.path = path;
            this.activity = activity;
            this.listener = listener;
        }

        boolean isDead() {
            if (removed) return true;
            if (activity != null && (activity.isFinishing() || activity.isDestroyed())) {
                removed = true;
                return true;
            }
            return false;
        }
    }

    private static final class QuerySub {
        final Query query;
        final String queryKey;
        final Activity activity;
        final EventListener<QuerySnapshot> listener;
        volatile boolean removed;
        volatile boolean deliveredServer;
        volatile List<DocItem> lastDocs;
        volatile long lastPolledAt;

        QuerySub(Query query, String queryKey, Activity activity, EventListener<QuerySnapshot> listener) {
            this.query = query;
            this.queryKey = queryKey;
            this.activity = activity;
            this.listener = listener;
        }

        boolean isDead() {
            if (removed) return true;
            if (activity != null && (activity.isFinishing() || activity.isDestroyed())) {
                removed = true;
                return true;
            }
            return false;
        }
    }

    private static final class DocItem {
        final String id;
        final String path;
        final Map<String, Object> data;
        final String rawJson;

        DocItem(String id, String path, Map<String, Object> data, String rawJson) {
            this.id = id;
            this.path = path;
            this.data = data;
            this.rawJson = rawJson;
        }
    }

    private static final ConcurrentHashMap<String, CachedDoc> DOC_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, List<DocItem>> QUERY_CACHE = new ConcurrentHashMap<>();
    private static final CopyOnWriteArrayList<DocSub> DOC_SUBS = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<QuerySub> QUERY_SUBS = new CopyOnWriteArrayList<>();

    private static final AtomicBoolean DISK_LOADED = new AtomicBoolean(false);
    private static final AtomicBoolean DISK_SAVE_PENDING = new AtomicBoolean(false);
    private static final AtomicBoolean POLLER_STARTED = new AtomicBoolean(false);

    private CfFirestoreTransport() {}

    static String autoId() {
        return CfWorkerBridge.autoId();
    }

    static String cleanPath(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        while (s.startsWith("/")) s = s.substring(1);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    static Map<String, Object> objectToMap(Object data) {
        return CfWorkerBridge.pojoToMap(data);
    }

    static Map<String, Object> varargsToMap(String firstField, Object firstVal, Object... more) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(firstField, firstVal);
        if (more != null) {
            for (int i = 0; i + 1 < more.length; i += 2) {
                Object k = more[i];
                if (k instanceof FieldPath) {
                    m.put(((FieldPath) k).getCanonicalString(), more[i + 1]);
                } else if (k != null) {
                    m.put(String.valueOf(k), more[i + 1]);
                }
            }
        }
        return m;
    }

    static void clearLocalCache() {
        DOC_CACHE.clear();
        QUERY_CACHE.clear();
    }

    private static File resolveDiskCacheFile() {
        try {
            android.app.Application app = com.friendbook.social.App.instance;
            if (app != null && app.getFilesDir() != null) {
                return new File(app.getFilesDir(), "cfdb_local_cache_v1.json");
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static void ensureDiskLoaded() {
        if (!DISK_LOADED.compareAndSet(false, true)) return;
        File f = resolveDiskCacheFile();
        if (f == null || !f.exists()) return;
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] bytes = new byte[(int) Math.min(f.length(), 4L * 1024L * 1024L)];
            int read = fis.read(bytes);
            if (read <= 0) return;
            JSONObject root = new JSONObject(new String(bytes, 0, read, StandardCharsets.UTF_8));
            JSONObject docs = root.optJSONObject("docs");
            if (docs != null) {
                java.util.Iterator<String> it = docs.keys();
                while (it.hasNext()) {
                    String path = it.next();
                    JSONObject item = docs.optJSONObject(path);
                    if (item != null && !DOC_CACHE.containsKey(path)) {
                        Map<String, Object> map = CfWorkerBridge.jsonToMap(item);
                        DOC_CACHE.put(path, new CachedDoc(true, map, item.toString()));
                    }
                }
            }
            JSONObject queries = root.optJSONObject("queries");
            if (queries != null) {
                java.util.Iterator<String> it = queries.keys();
                while (it.hasNext()) {
                    String qk = it.next();
                    JSONArray arr = queries.optJSONArray(qk);
                    if (arr != null && !QUERY_CACHE.containsKey(qk)) {
                        List<DocItem> items = parseQueryDocs(arr);
                        QUERY_CACHE.put(qk, items);
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void scheduleDiskSave() {
        if (!DISK_SAVE_PENDING.compareAndSet(false, true)) return;
        CfWorkerBridge.SCHEDULER.schedule(() -> {
            DISK_SAVE_PENDING.set(false);
            File f = resolveDiskCacheFile();
            if (f == null) return;
            try {
                JSONObject root = new JSONObject();
                JSONObject docs = new JSONObject();
                int dCount = 0;
                for (Map.Entry<String, CachedDoc> e : DOC_CACHE.entrySet()) {
                    CachedDoc cd = e.getValue();
                    if (cd != null && cd.exists && cd.rawJson != null && !cd.rawJson.isEmpty()) {
                        docs.put(e.getKey(), new JSONObject(cd.rawJson));
                        if (++dCount >= 400) break;
                    }
                }
                root.put("docs", docs);

                JSONObject queries = new JSONObject();
                int qCount = 0;
                for (Map.Entry<String, List<DocItem>> e : QUERY_CACHE.entrySet()) {
                    JSONArray arr = new JSONArray();
                    for (DocItem di : e.getValue()) {
                        JSONObject row = new JSONObject();
                        row.put("id", di.id);
                        row.put("path", di.path);
                        row.put("data", new JSONObject(di.rawJson));
                        arr.put(row);
                    }
                    queries.put(e.getKey(), arr);
                    if (++qCount >= 40) break;
                }
                root.put("queries", queries);

                byte[] payload = root.toString().getBytes(StandardCharsets.UTF_8);
                File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
                try (FileOutputStream fos = new FileOutputStream(tmp)) {
                    fos.write(payload);
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(f);
            } catch (Throwable ignored) {}
        }, 2, TimeUnit.SECONDS);
    }

    static DocumentSnapshot getSync(FirebaseFirestore db, String path) throws FirebaseFirestoreException {
        JSONObject req = new JSONObject();
        try {
            req.put("path", path);
        } catch (JSONException e) {
            throw new FirebaseFirestoreException("Bad path", FirebaseFirestoreException.Code.INVALID_ARGUMENT, e);
        }
        JSONObject res = CfWorkerBridge.requestJson("POST", "/v1/cfdb/get", req);
        boolean exists = res.optBoolean("exists", false);
        JSONObject dataObj = res.optJSONObject("data");
        if (!exists || dataObj == null) {
            DOC_CACHE.put(path, new CachedDoc(false, null, ""));
            return new DocumentSnapshot(db, path, null, false, false);
        }
        try {
            Map<String, Object> data = CfWorkerBridge.jsonToMap(dataObj);
            String raw = dataObj.toString();
            DOC_CACHE.put(path, new CachedDoc(true, data, raw));
            scheduleDiskSave();
            return new DocumentSnapshot(db, path, data, true, false);
        } catch (JSONException e) {
            throw new FirebaseFirestoreException("Decode error", FirebaseFirestoreException.Code.INTERNAL, e);
        }
    }

    static Task<DocumentSnapshot> executeGet(FirebaseFirestore db, String path, Source source) {
        ensureDiskLoaded();
        if (source == Source.CACHE) {
            CachedDoc cached = DOC_CACHE.get(path);
            if (cached != null && cached.exists && cached.data != null) {
                return Tasks.forResult(new DocumentSnapshot(db, path, cached.data, true, true));
            }
            return Tasks.forResult(new DocumentSnapshot(db, path, null, false, true));
        }

        TaskCompletionSource<DocumentSnapshot> tcs = new TaskCompletionSource<>();
        CfWorkerBridge.IO_POOL.execute(() -> {
            try {
                DocumentSnapshot snap = getSync(db, path);
                CfWorkerBridge.postMain(() -> tcs.trySetResult(snap));
            } catch (Exception e) {
                CachedDoc fallback = source == Source.DEFAULT ? DOC_CACHE.get(path) : null;
                if (fallback != null && fallback.exists && fallback.data != null) {
                    DocumentSnapshot snap = new DocumentSnapshot(db, path, fallback.data, true, true);
                    CfWorkerBridge.postMain(() -> tcs.trySetResult(snap));
                } else {
                    CfWorkerBridge.postMain(() -> tcs.trySetException(e));
                }
            }
        });
        return tcs.getTask();
    }

    private static JSONObject buildQueryJson(Query q, boolean countOnly) throws JSONException {
        JSONObject body = new JSONObject();
        body.put("collection", q.collectionPath);
        if (countOnly) body.put("countOnly", true);

        if (!q.filters.isEmpty()) {
            JSONArray fs = new JSONArray();
            for (Query.FilterSpec f : q.filters) {
                JSONObject fo = new JSONObject();
                fo.put("field", f.field);
                fo.put("op", f.op);
                fo.put("value", CfWorkerBridge.toJsonValue(f.value));
                fs.put(fo);
            }
            body.put("filters", fs);
        }

        if (!q.orders.isEmpty()) {
            JSONArray os = new JSONArray();
            for (Query.OrderSpec o : q.orders) {
                JSONObject oo = new JSONObject();
                oo.put("field", o.field);
                oo.put("dir", o.direction == Query.Direction.DESCENDING ? "DESC" : "ASC");
                os.put(oo);
            }
            body.put("orders", os);
        }

        if (q.limitCount > 0) body.put("limit", q.limitCount);
        if (q.limitToLastCount > 0) body.put("limitToLast", q.limitToLastCount);
        putBound(body, "startAt", q.startAtBound);
        putBound(body, "startAfter", q.startAfterBound);
        putBound(body, "endBefore", q.endBeforeBound);
        putBound(body, "endAt", q.endAtBound);
        return body;
    }

    private static void putBound(JSONObject target, String key, Query.BoundSpec bound) throws JSONException {
        if (bound == null) return;
        JSONObject b = new JSONObject();
        if (bound.docId != null && !bound.docId.isEmpty()) {
            b.put("docId", bound.docId);
        }
        if (bound.values != null && !bound.values.isEmpty()) {
            JSONArray vals = new JSONArray();
            for (Object v : bound.values) {
                vals.put(CfWorkerBridge.toJsonValue(v));
            }
            b.put("values", vals);
        }
        target.put(key, b);
    }

    private static List<DocItem> parseQueryDocs(JSONArray docsArr) throws JSONException {
        List<DocItem> items = new ArrayList<>(docsArr == null ? 0 : docsArr.length());
        if (docsArr == null) return items;
        for (int i = 0; i < docsArr.length(); i++) {
            JSONObject row = docsArr.optJSONObject(i);
            if (row == null) continue;
            String id = row.optString("id", "");
            String path = row.optString("path", "");
            JSONObject dataObj = row.optJSONObject("data");
            if (dataObj == null) continue;
            String raw = dataObj.toString();
            Map<String, Object> data = CfWorkerBridge.jsonToMap(dataObj);
            if (!path.isEmpty()) {
                DOC_CACHE.put(path, new CachedDoc(true, data, raw));
            }
            items.add(new DocItem(id, path, data, raw));
        }
        return items;
    }

    private static QuerySnapshot buildQuerySnapshot(Query query, List<DocItem> items, List<DocItem> prevItems, boolean isFromCache) {
        FirebaseFirestore db = query.getFirestore();
        List<QueryDocumentSnapshot> docs = new ArrayList<>(items.size());
        for (DocItem item : items) {
            String p = !item.path.isEmpty() ? item.path : (query.collectionPath + "/" + item.id);
            docs.add(new QueryDocumentSnapshot(db, p, item.data, isFromCache));
        }
        List<DocumentChange> changes = computeChanges(db, query.collectionPath, prevItems, items, isFromCache);
        return new QuerySnapshot(query, docs, changes, isFromCache);
    }

    private static List<DocumentChange> computeChanges(
            FirebaseFirestore db,
            String collectionPath,
            List<DocItem> prev,
            List<DocItem> curr,
            boolean isFromCache) {
        List<DocumentChange> changes = new ArrayList<>();
        if (prev == null) {
            for (int i = 0; i < curr.size(); i++) {
                DocItem c = curr.get(i);
                String p = !c.path.isEmpty() ? c.path : (collectionPath + "/" + c.id);
                changes.add(new DocumentChange(
                        DocumentChange.Type.ADDED,
                        new QueryDocumentSnapshot(db, p, c.data, isFromCache),
                        -1,
                        i));
            }
            return changes;
        }

        Map<String, Integer> prevIdx = new LinkedHashMap<>();
        for (int i = 0; i < prev.size(); i++) prevIdx.put(prev.get(i).id, i);
        Map<String, Integer> currIdx = new LinkedHashMap<>();
        for (int i = 0; i < curr.size(); i++) currIdx.put(curr.get(i).id, i);

        for (int oldI = 0; oldI < prev.size(); oldI++) {
            DocItem oldItem = prev.get(oldI);
            if (!currIdx.containsKey(oldItem.id)) {
                String p = !oldItem.path.isEmpty() ? oldItem.path : (collectionPath + "/" + oldItem.id);
                changes.add(new DocumentChange(
                        DocumentChange.Type.REMOVED,
                        new QueryDocumentSnapshot(db, p, oldItem.data, isFromCache),
                        oldI,
                        -1));
            }
        }

        for (int newI = 0; newI < curr.size(); newI++) {
            DocItem newItem = curr.get(newI);
            Integer oldI = prevIdx.get(newItem.id);
            String p = !newItem.path.isEmpty() ? newItem.path : (collectionPath + "/" + newItem.id);
            if (oldI == null) {
                changes.add(new DocumentChange(
                        DocumentChange.Type.ADDED,
                        new QueryDocumentSnapshot(db, p, newItem.data, isFromCache),
                        -1,
                        newI));
            } else {
                DocItem oldItem = prev.get(oldI);
                if (!Objects.equals(oldItem.rawJson, newItem.rawJson) || oldI != newI) {
                    changes.add(new DocumentChange(
                            DocumentChange.Type.MODIFIED,
                            new QueryDocumentSnapshot(db, p, newItem.data, isFromCache),
                            oldI,
                            newI));
                }
            }
        }
        return changes;
    }

    static Task<QuerySnapshot> executeQuery(Query query, Source source) {
        ensureDiskLoaded();
        final String queryKey;
        final JSONObject reqBody;
        try {
            reqBody = buildQueryJson(query, false);
            queryKey = reqBody.toString();
        } catch (JSONException e) {
            return Tasks.forException(new FirebaseFirestoreException("Invalid query", FirebaseFirestoreException.Code.INVALID_ARGUMENT, e));
        }

        if (source == Source.CACHE) {
            List<DocItem> cached = QUERY_CACHE.get(queryKey);
            if (cached != null) {
                return Tasks.forResult(buildQuerySnapshot(query, cached, null, true));
            }
            return Tasks.forResult(buildQuerySnapshot(query, Collections.emptyList(), null, true));
        }

        TaskCompletionSource<QuerySnapshot> tcs = new TaskCompletionSource<>();
        CfWorkerBridge.IO_POOL.execute(() -> {
            try {
                JSONObject res = CfWorkerBridge.requestJson("POST", "/v1/cfdb/query", reqBody);
                List<DocItem> items = parseQueryDocs(res.optJSONArray("documents"));
                QUERY_CACHE.put(queryKey, items);
                scheduleDiskSave();
                QuerySnapshot snap = buildQuerySnapshot(query, items, null, false);
                CfWorkerBridge.postMain(() -> tcs.trySetResult(snap));
            } catch (Exception e) {
                List<DocItem> fallback = source == Source.DEFAULT ? QUERY_CACHE.get(queryKey) : null;
                if (fallback != null) {
                    QuerySnapshot snap = buildQuerySnapshot(query, fallback, null, true);
                    CfWorkerBridge.postMain(() -> tcs.trySetResult(snap));
                } else {
                    CfWorkerBridge.postMain(() -> tcs.trySetException(e));
                }
            }
        });
        return tcs.getTask();
    }

    static Task<AggregateQuerySnapshot> executeCount(AggregateQuery agg, Query query) {
        final JSONObject reqBody;
        try {
            reqBody = buildQueryJson(query, true);
        } catch (JSONException e) {
            return Tasks.forException(new FirebaseFirestoreException("Invalid count query", FirebaseFirestoreException.Code.INVALID_ARGUMENT, e));
        }

        TaskCompletionSource<AggregateQuerySnapshot> tcs = new TaskCompletionSource<>();
        CfWorkerBridge.IO_POOL.execute(() -> {
            try {
                JSONObject res = CfWorkerBridge.requestJson("POST", "/v1/cfdb/query", reqBody);
                long count = res.optLong("count", 0L);
                AggregateQuerySnapshot snap = new AggregateQuerySnapshot(agg, count);
                CfWorkerBridge.postMain(() -> tcs.trySetResult(snap));
            } catch (Exception e) {
                CfWorkerBridge.postMain(() -> tcs.trySetException(e));
            }
        });
        return tcs.getTask();
    }

    private static void writeSync(List<WriteOp> ops) throws FirebaseFirestoreException {
        if (ops == null || ops.isEmpty()) return;
        JSONObject req = new JSONObject();
        JSONArray writes = new JSONArray();
        Set<String> touchedPaths = ConcurrentHashMap.newKeySet();
        Set<String> touchedCollections = ConcurrentHashMap.newKeySet();

        try {
            for (WriteOp op : ops) {
                JSONObject w = new JSONObject();
                w.put("op", op.op);
                w.put("path", op.path);
                if (op.merge) w.put("merge", true);
                if (op.data != null) {
                    w.put("data", CfWorkerBridge.toJsonValue(op.data));
                }
                writes.put(w);
                touchedPaths.add(op.path);
                int slash = op.path.lastIndexOf('/');
                if (slash > 0) {
                    String col = op.path.substring(0, slash);
                    touchedCollections.add(col);
                    if ("friends".equals(col) || "follows".equals(col) || "posts".equals(col)) {
                        touchedCollections.add("users");
                    }
                    if ("users".equals(col)) {
                        touchedCollections.add("posts");
                        touchedCollections.add("reels");
                    }
                }
            }
            req.put("writes", writes);
        } catch (JSONException e) {
            throw new FirebaseFirestoreException("Failed to serialize write", FirebaseFirestoreException.Code.INVALID_ARGUMENT, e);
        }

        JSONObject res = CfWorkerBridge.requestJson("POST", "/v1/cfdb/write", req);
        JSONArray results = res.optJSONArray("results");
        if (results != null) {
            for (int i = 0; i < results.length(); i++) {
                JSONObject r = results.optJSONObject(i);
                if (r == null) continue;
                String rPath = cleanPath(r.optString("path", ""));
                if (rPath.isEmpty()) continue;
                touchedPaths.add(rPath);
                if (r.optBoolean("deleted", false)) {
                    DOC_CACHE.put(rPath, new CachedDoc(false, null, ""));
                } else {
                    JSONObject dataObj = r.optJSONObject("data");
                    if (dataObj != null) {
                        try {
                            Map<String, Object> map = CfWorkerBridge.jsonToMap(dataObj);
                            DOC_CACHE.put(rPath, new CachedDoc(true, map, dataObj.toString()));
                        } catch (JSONException ignored) {}
                    }
                }
            }
        }
        scheduleDiskSave();
        notifyLocalMutation(touchedPaths, touchedCollections);
    }

    static Task<Void> executeWrites(List<WriteOp> ops) {
        if (ops == null || ops.isEmpty()) return Tasks.forResult(null);
        TaskCompletionSource<Void> tcs = new TaskCompletionSource<>();
        CfWorkerBridge.IO_POOL.execute(() -> {
            try {
                writeSync(ops);
                CfWorkerBridge.postMain(() -> tcs.trySetResult(null));
            } catch (Exception e) {
                CfWorkerBridge.postMain(() -> tcs.trySetException(e));
            }
        });
        return tcs.getTask();
    }

    static <TResult> Task<TResult> executeTransaction(FirebaseFirestore db, Transaction.Function<TResult> fn) {
        TaskCompletionSource<TResult> tcs = new TaskCompletionSource<>();
        CfWorkerBridge.TX_POOL.execute(() -> {
            try {
                Transaction tx = new Transaction(db);
                TResult out = fn.apply(tx);
                List<WriteOp> ops = tx.ops();
                if (!ops.isEmpty()) {
                    writeSync(ops);
                }
                CfWorkerBridge.postMain(() -> tcs.trySetResult(out));
            } catch (Exception e) {
                CfWorkerBridge.postMain(() -> tcs.trySetException(e));
            }
        });
        return tcs.getTask();
    }

    static ListenerRegistration listenDocument(
            FirebaseFirestore db,
            String path,
            Activity activity,
            EventListener<DocumentSnapshot> listener) {
        ensureDiskLoaded();
        ensurePollerStarted();
        final DocSub sub = new DocSub(db, path, activity, listener);
        DOC_SUBS.add(sub);

        CachedDoc cached = DOC_CACHE.get(path);
        if (cached != null && cached.exists && cached.data != null) {
            final DocumentSnapshot cachedSnap = new DocumentSnapshot(db, path, cached.data, true, true);
            CfWorkerBridge.postMain(() -> {
                if (!sub.isDead()) sub.listener.onEvent(cachedSnap, null);
            });
        }

        CfWorkerBridge.IO_POOL.execute(() -> pollDocSub(sub, true));
        return () -> {
            sub.removed = true;
            DOC_SUBS.remove(sub);
        };
    }

    static ListenerRegistration listenQuery(
            Query query,
            Activity activity,
            EventListener<QuerySnapshot> listener) {
        ensureDiskLoaded();
        ensurePollerStarted();
        String qk;
        try {
            qk = buildQueryJson(query, false).toString();
        } catch (JSONException e) {
            qk = query.collectionPath;
        }
        final QuerySub sub = new QuerySub(query, qk, activity, listener);
        QUERY_SUBS.add(sub);

        List<DocItem> cached = QUERY_CACHE.get(qk);
        if (cached != null && !cached.isEmpty()) {
            sub.lastDocs = cached;
            final QuerySnapshot cachedSnap = buildQuerySnapshot(query, cached, null, true);
            CfWorkerBridge.postMain(() -> {
                if (!sub.isDead()) sub.listener.onEvent(cachedSnap, null);
            });
        }

        CfWorkerBridge.IO_POOL.execute(() -> pollQuerySub(sub, true));
        return () -> {
            sub.removed = true;
            QUERY_SUBS.remove(sub);
        };
    }

    private static void pollDocSub(DocSub sub, boolean forceInitial) {
        if (sub.isDead()) {
            DOC_SUBS.remove(sub);
            return;
        }
        sub.lastPolledAt = System.currentTimeMillis();
        try {
            DocumentSnapshot snap = getSync(sub.firestore, sub.path);
            CachedDoc cd = DOC_CACHE.get(sub.path);
            String fp = snap.exists() ? ("1:" + (cd != null ? cd.rawJson : "")) : "0:";
            if (!forceInitial && sub.deliveredServer && Objects.equals(fp, sub.lastFingerprint)) {
                return;
            }
            sub.lastFingerprint = fp;
            sub.deliveredServer = true;
            CfWorkerBridge.postMain(() -> {
                if (!sub.isDead()) sub.listener.onEvent(snap, null);
            });
        } catch (FirebaseFirestoreException e) {
            if (forceInitial && !sub.deliveredServer) {
                CfWorkerBridge.postMain(() -> {
                    if (!sub.isDead()) sub.listener.onEvent(null, e);
                });
            }
        } catch (Throwable ignored) {}
    }

    private static void pollQuerySub(QuerySub sub, boolean forceInitial) {
        if (sub.isDead()) {
            QUERY_SUBS.remove(sub);
            return;
        }
        sub.lastPolledAt = System.currentTimeMillis();
        try {
            JSONObject reqBody = buildQueryJson(sub.query, false);
            JSONObject res = CfWorkerBridge.requestJson("POST", "/v1/cfdb/query", reqBody);
            List<DocItem> items = parseQueryDocs(res.optJSONArray("documents"));
            QUERY_CACHE.put(sub.queryKey, items);

            List<DocItem> prev = sub.lastDocs;
            QuerySnapshot snap = buildQuerySnapshot(sub.query, items, prev, false);
            if (!forceInitial && sub.deliveredServer && snap.getDocumentChanges().isEmpty()) {
                return;
            }
            sub.lastDocs = items;
            sub.deliveredServer = true;
            CfWorkerBridge.postMain(() -> {
                if (!sub.isDead()) sub.listener.onEvent(snap, null);
            });
        } catch (FirebaseFirestoreException e) {
            if (forceInitial && !sub.deliveredServer) {
                CfWorkerBridge.postMain(() -> {
                    if (!sub.isDead()) sub.listener.onEvent(null, e);
                });
            }
        } catch (Throwable ignored) {}
    }

    private static void notifyLocalMutation(Set<String> paths, Set<String> collections) {
        for (DocSub sub : DOC_SUBS) {
            if (sub.isDead()) continue;
            int slash = sub.path.lastIndexOf('/');
            String col = slash > 0 ? sub.path.substring(0, slash) : "";
            if (paths.contains(sub.path) || ("users".equals(col) && collections.contains("users"))) {
                CfWorkerBridge.IO_POOL.execute(() -> pollDocSub(sub, false));
            }
        }
        for (QuerySub sub : QUERY_SUBS) {
            if (sub.isDead()) continue;
            if (collections.contains(sub.query.collectionPath)) {
                CfWorkerBridge.IO_POOL.execute(() -> pollQuerySub(sub, false));
            }
        }
    }

    private static long pollIntervalMsForPath(String pathOrCol) {
        if (pathOrCol == null) return 7_000L;
        if (pathOrCol.startsWith("calls")
                || pathOrCol.startsWith("lives")
                || pathOrCol.contains("/messages")
                || pathOrCol.startsWith("chats")) {
            return 2_500L;
        }
        if (pathOrCol.startsWith("friend_requests")
                || pathOrCol.startsWith("notifications")
                || pathOrCol.startsWith("users")) {
            return 5_000L;
        }
        return 8_000L;
    }

    private static void ensurePollerStarted() {
        if (!POLLER_STARTED.compareAndSet(false, true)) return;
        CfWorkerBridge.SCHEDULER.scheduleWithFixedDelay(() -> {
            long now = System.currentTimeMillis();
            for (DocSub sub : DOC_SUBS) {
                if (sub.isDead()) {
                    DOC_SUBS.remove(sub);
                    continue;
                }
                if (now - sub.lastPolledAt >= pollIntervalMsForPath(sub.path)) {
                    CfWorkerBridge.IO_POOL.execute(() -> pollDocSub(sub, false));
                }
            }
            for (QuerySub sub : QUERY_SUBS) {
                if (sub.isDead()) {
                    QUERY_SUBS.remove(sub);
                    continue;
                }
                if (now - sub.lastPolledAt >= pollIntervalMsForPath(sub.query.collectionPath)) {
                    CfWorkerBridge.IO_POOL.execute(() -> pollQuerySub(sub, false));
                }
            }
        }, 2_000L, 1_500L, TimeUnit.MILLISECONDS);
    }
}
