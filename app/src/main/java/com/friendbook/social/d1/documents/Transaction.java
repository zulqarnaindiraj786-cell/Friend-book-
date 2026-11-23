package com.friendbook.social.d1.documents;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Synchronous transaction context used inside D1DocumentStore.runTransaction(). */
public class Transaction {
    public interface Function<TResult> {
        TResult apply(Transaction transaction) throws D1DocumentStoreException;
    }

    private final D1DocumentStore firestore;
    private final List<JSONObject> operations = new ArrayList<>();

    Transaction(D1DocumentStore firestore) {
        this.firestore = firestore;
    }

    public DocumentSnapshot get(DocumentReference documentRef) throws D1DocumentStoreException {
        try {
            JSONObject res = D1DocumentTransport.getDocument(documentRef.getPath());
            return documentRef.parseSnapshot(res);
        } catch (D1DocumentStoreException e) {
            throw e;
        } catch (Exception e) {
            throw new D1DocumentStoreException(D1DocumentStoreException.Code.UNAVAILABLE, e.getMessage(), e);
        }
    }

    public Transaction set(DocumentReference documentRef, Object data) {
        return set(documentRef, data, null);
    }

    @SuppressWarnings("unchecked")
    public Transaction set(DocumentReference documentRef, Object data, SetOptions options) {
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

    public Transaction update(DocumentReference documentRef, Map<String, Object> data) {
        JSONObject op = new JSONObject();
        try {
            op.put("op", "update");
            op.put("path", documentRef.getPath());
            op.put("updates", D1DocumentJson.encodeMap(data));
        } catch (Exception ignored) { }
        operations.add(op);
        return this;
    }

    public Transaction update(DocumentReference documentRef, String field, Object value, Object... moreFieldsAndValues) {
        Map<String, Object> map = new HashMap<>();
        map.put(field, value);
        if (moreFieldsAndValues != null) {
            for (int i = 0; i + 1 < moreFieldsAndValues.length; i += 2) {
                map.put(String.valueOf(moreFieldsAndValues[i]), moreFieldsAndValues[i + 1]);
            }
        }
        return update(documentRef, map);
    }

    public Transaction delete(DocumentReference documentRef) {
        JSONObject op = new JSONObject();
        try {
            op.put("op", "delete");
            op.put("path", documentRef.getPath());
        } catch (Exception ignored) { }
        operations.add(op);
        return this;
    }

    void commit() throws Exception {
        if (operations.isEmpty()) return;
        JSONArray ops = new JSONArray(operations);
        D1DocumentTransport.transaction(ops);
        firestore.notifyCommittedPaths(ops);
    }
}
