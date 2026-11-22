package com.friendbook.social.d1.documents;

import com.google.android.gms.tasks.Task;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Atomic batch of writes executed on Cloudflare D1. */
public class WriteBatch {
    private final D1DocumentStore firestore;
    private final List<JSONObject> operations = new ArrayList<>();

    WriteBatch(D1DocumentStore firestore) {
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
            op.put("data", D1DocumentJson.encodeMap(map));
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
            op.put("updates", D1DocumentJson.encodeMap(data));
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
        JSONArray ops=new JSONArray(operations);
        return firestore.runAsync(() -> { send(ops,false); return null; });
    }

    public Task<Boolean> commitChatSend() {
        JSONArray ops = new JSONArray(operations);
        java.util.concurrent.Callable<Boolean> task = () -> send(ops,true);
        for (int i = 0; i < ops.length(); i++) {
            JSONObject op = ops.optJSONObject(i);
            String path = op == null ? "" : op.optString("path", "");
            if (path.matches("chats/[^/]+")) return firestore.runChatSend(path, task);
        }
        return firestore.runChatSend("", task);
    }

    private boolean send(JSONArray ops,boolean chatSend) throws Exception {
        boolean pushScheduled=D1DocumentTransport.batch(ops,chatSend);
        // The server committed all operations atomically. Refresh each touched
        // document and collection once, not once per operation in the batch.
        firestore.notifyCommittedPaths(ops);
        return pushScheduled;
    }
}
