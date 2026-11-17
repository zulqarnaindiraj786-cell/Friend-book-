package com.friendbook.social.d1.documents;

import com.google.android.gms.tasks.Task;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/** Reference to a document in Cloudflare D1. */
public class DocumentReference {
    private final D1DocumentStore firestore;
    private final String path;
    private final String id;

    DocumentReference(D1DocumentStore firestore, String path) {
        this.firestore = firestore;
        this.path = trimSlashes(path);
        int idx = this.path.lastIndexOf('/');
        this.id = idx >= 0 ? this.path.substring(idx + 1) : this.path;
    }

    private static String trimSlashes(String s) {
        if (s == null) return "";
        int a = 0, b = s.length();
        while (a < b && s.charAt(a) == '/') a++;
        while (b > a && s.charAt(b - 1) == '/') b--;
        return s.substring(a, b);
    }

    public String getId() { return id; }

    public String getPath() { return path; }

    public D1DocumentStore getStore() { return firestore; }

    public CollectionReference collection(String collectionPath) {
        return new CollectionReference(firestore, path + "/" + trimSlashes(collectionPath));
    }

    public CollectionReference getParent() {
        int idx = path.lastIndexOf('/');
        return idx > 0 ? new CollectionReference(firestore, path.substring(0, idx)) : null;
    }

    public Task<DocumentSnapshot> get() {
        return get(Source.DEFAULT);
    }

    public Task<DocumentSnapshot> get(Source source) {
        if (source == Source.CACHE) {
            DocumentSnapshot cached = firestore.getCachedDoc(path);
            if (cached != null) return com.google.android.gms.tasks.Tasks.forResult(cached);
        }
        return firestore.runAsync(() -> {
            JSONObject res = D1DocumentTransport.getDocument(path);
            DocumentSnapshot snap = parseSnapshot(res);
            firestore.cacheDoc(path, snap);
            return snap;
        });
    }

    DocumentSnapshot parseSnapshot(JSONObject res) {
        boolean exists = res != null && res.optBoolean("exists", false);
        Map<String, Object> data = exists ? D1DocumentJson.decodeMap(res.optJSONObject("data")) : null;
        long ct = res != null ? res.optLong("createTime", 0L) : 0L;
        long ut = res != null ? res.optLong("updateTime", 0L) : 0L;
        return new DocumentSnapshot(firestore, path, id, data, exists, new SnapshotMetadata(false, false), ct, ut);
    }

    public Task<Void> set(Object data) {
        return set(data, null);
    }

    @SuppressWarnings("unchecked")
    public Task<Void> set(Object data, SetOptions options) {
        Map<String, Object> map = data instanceof Map ? (Map<String, Object>) data : new HashMap<>();
        JSONObject body = new JSONObject();
        try {
            body.put("data", D1DocumentJson.encodeMap(map));
            if (options != null && options.isMerge()) {
                body.put("merge", true);
                if (options.getMergeFields() != null) {
                    body.put("mergeFields", new JSONArray(options.getMergeFields()));
                }
            }
        } catch (Exception ignored) { }
        return firestore.runAsync(() -> {
            D1DocumentTransport.putDocument(path, body);
            firestore.invalidateDoc(path);
            firestore.notifyDocListeners(path);
            return null;
        });
    }

    public Task<Void> update(Map<String, Object> data) {
        JSONObject body = new JSONObject();
        try { body.put("updates", D1DocumentJson.encodeMap(data)); } catch (Exception ignored) { }
        return firestore.runAsync(() -> {
            D1DocumentTransport.patchDocument(path, body);
            firestore.invalidateDoc(path);
            firestore.notifyDocListeners(path);
            return null;
        });
    }

    public Task<Void> update(String field, Object value, Object... moreFieldsAndValues) {
        Map<String, Object> map = new HashMap<>();
        map.put(field, value);
        if (moreFieldsAndValues != null) {
            for (int i = 0; i + 1 < moreFieldsAndValues.length; i += 2) {
                map.put(String.valueOf(moreFieldsAndValues[i]), moreFieldsAndValues[i + 1]);
            }
        }
        return update(map);
    }

    public Task<Void> update(FieldPath fieldPath, Object value, Object... moreFieldsAndValues) {
        Map<String, Object> map = new HashMap<>();
        if (fieldPath != null) map.put(fieldPath.getFieldPathString(), value);
        if (moreFieldsAndValues != null) {
            for (int i = 0; i + 1 < moreFieldsAndValues.length; i += 2) {
                Object k = moreFieldsAndValues[i];
                String ks = k instanceof FieldPath ? ((FieldPath) k).getFieldPathString() : String.valueOf(k);
                map.put(ks, moreFieldsAndValues[i + 1]);
            }
        }
        return update(map);
    }

    public Task<Void> delete() {
        return firestore.runAsync(() -> {
            D1DocumentTransport.deleteDocument(path);
            firestore.invalidateDoc(path);
            firestore.notifyDocListeners(path);
            return null;
        });
    }

    public ListenerRegistration addSnapshotListener(EventListener<DocumentSnapshot> listener) {
        return firestore.registerDocListener(this, listener);
    }

    public ListenerRegistration addSnapshotListener(MetadataChanges metadataChanges, EventListener<DocumentSnapshot> listener) {
        return addSnapshotListener(listener);
    }

    public ListenerRegistration addSnapshotListener(Executor executor, EventListener<DocumentSnapshot> listener) {
        return addSnapshotListener(listener);
    }

    public ListenerRegistration addSnapshotListener(android.app.Activity activity, EventListener<DocumentSnapshot> listener) {
        return addSnapshotListener(listener);
    }
}
