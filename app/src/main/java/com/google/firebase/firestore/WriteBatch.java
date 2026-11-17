package com.google.firebase.firestore;

import com.google.android.gms.tasks.Task;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Atomic batch of writes executed on Cloudflare D1. */
public class WriteBatch {
    private final FirebaseFirestore firestore;
    private final List<JSONObject> operations = new ArrayList<>();

    WriteBatch(FirebaseFirestore firestore) {
        this.firestore = firestore;
    }

    public WriteBatch set(DocumentReference documentRef, Object data) {
        return set(documentRef, data, null);
    }

    @SuppressWarnings("unchecked")
    public WriteBatch set(DocumentReference documentRef, Object data, SetOptions options) {
        Map<String, Object> map = data instanceof Map ? (Map<String, Object>) data : new HashMap<>();
        JSONObject op = new JSONObject();
        try {
            op.put("op", "set");
            op.put("path", documentRef.getPath());
            op.put("data", FirestoreJson.encodeMap(map));
            if (options != null && options.isMerge()) {
                op.put("merge", true);
                if (options.getMergeFields() != null) {
                    op.put("mergeFields", new JSONArray(options.getMergeFields()));
                }
            }
        } catch (Exception ignored) { }
        operations.add(op);
        return this;
    }

    public WriteBatch update(DocumentReference documentRef, Map<String, Object> data) {
        JSONObject op = new JSONObject();
        try {
            op.put("op", "update");
            op.put("path", documentRef.getPath());
            op.put("updates", FirestoreJson.encodeMap(data));
        } catch (Exception ignored) { }
        operations.add(op);
        return this;
    }

    public WriteBatch update(DocumentReference documentRef, String field, Object value, Object... moreFieldsAndValues) {
        Map<String, Object> map = new HashMap<>();
        map.put(field, value);
        if (moreFieldsAndValues != null) {
            for (int i = 0; i + 1 < moreFieldsAndValues.length; i += 2) {
                map.put(String.valueOf(moreFieldsAndValues[i]), moreFieldsAndValues[i + 1]);
            }
        }
        return update(documentRef, map);
    }

    public WriteBatch update(DocumentReference documentRef, FieldPath fieldPath, Object value, Object... moreFieldsAndValues) {
        Map<String, Object> map = new HashMap<>();
        if (fieldPath != null) map.put(fieldPath.getFieldPathString(), value);
        if (moreFieldsAndValues != null) {
            for (int i = 0; i + 1 < moreFieldsAndValues.length; i += 2) {
                Object k = moreFieldsAndValues[i];
                String ks = k instanceof FieldPath ? ((FieldPath) k).getFieldPathString() : String.valueOf(k);
                map.put(ks, moreFieldsAndValues[i + 1]);
            }
        }
        return update(documentRef, map);
    }

    public WriteBatch delete(DocumentReference documentRef) {
        JSONObject op = new JSONObject();
        try {
            op.put("op", "delete");
            op.put("path", documentRef.getPath());
        } catch (Exception ignored) { }
        operations.add(op);
        return this;
    }

    public Task<Void> commit() {
        JSONArray ops = new JSONArray(operations);
        return firestore.runAsync(() -> {
            FirestoreTransport.batch(ops);
            for (int i = 0; i < ops.length(); i++) {
                JSONObject o = ops.optJSONObject(i);
                if (o != null) {
                    String p = o.optString("path", "");
                    if (!p.isEmpty()) {
                        firestore.invalidateDoc(p);
                        firestore.notifyDocListeners(p);
                    }
                }
            }
            return null;
        });
    }
}
