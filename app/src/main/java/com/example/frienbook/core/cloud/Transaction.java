package com.example.frienbook.core.cloud;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONObject;

/**
 * Read, decide, then write - as one unit.
 *
 * A reaction is the case this exists for: whether to add, switch or remove a reaction depends on what
 * is already stored, and the post's counter has to move with it. So the reads happen first (blocking,
 * off the main thread) and the writes are buffered, then sent to the Worker as one batch - and a batch
 * is all-or-nothing there, so the reaction and the counter can never disagree.
 *
 * What this is not, stated plainly: D1 gives no cross-row isolation through this API, so two people
 * reacting at the same instant are not serialised against each other. The counter is therefore moved
 * with FieldValue.increment, which the Worker applies to the stored row itself, so concurrent
 * reactions still add up correctly even though the reads were not locked.
 */
public final class Transaction {

    private final List<JSONObject> ops = new ArrayList<>();

    Transaction() { }

    /** Reads the document as it is right now. Blocking: a transaction already runs off the main thread. */
    public DocumentSnapshot get(DocumentReference reference) throws CloudException {
        return reference.read();
    }

    public Transaction set(DocumentReference reference, Map<String, Object> data) {
        return buffer("set", reference, data);
    }

    public Transaction set(DocumentReference reference, Map<String, Object> data, SetOptions options) {
        boolean merge = options != null && options.isMerge();
        return buffer(merge ? "update" : "set", reference, data);
    }

    public Transaction update(DocumentReference reference, Map<String, Object> data) {
        return buffer("update", reference, data);
    }

    public Transaction update(DocumentReference reference, String field, Object value, Object... more) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(field, value);
        if (more != null) {
            for (int i = 0; i + 1 < more.length; i += 2) {
                if (more[i] != null) data.put(String.valueOf(more[i]), more[i + 1]);
            }
        }
        return buffer("update", reference, data);
    }

    public Transaction delete(DocumentReference reference) {
        return buffer("delete", reference, null);
    }

    private Transaction buffer(String op, DocumentReference reference, Map<String, Object> data) {
        if (reference == null) return this;
        JSONObject entry = new JSONObject();
        try {
            entry.put("op", op);
            entry.put("collection", reference.collectionPath());
            entry.put("id", reference.getId());
            if (!"delete".equals(op)) {
                JSONObject body = Cloud.writeBody(data, "set".equals(op));
                JSONObject fields = body.optJSONObject("fields");
                JSONObject markers = body.optJSONObject("ops");
                entry.put("fields", fields == null ? new JSONObject() : fields);
                if (markers != null && markers.length() > 0) entry.put("ops", markers);
            }
        } catch (Exception failed) {
            return this;
        }
        ops.add(entry);
        return this;
    }

    List<JSONObject> ops() {
        return ops;
    }

    /** The body your function runs on. Firestore's own name for it, kept so the call site reads the same. */
    public interface Function<T> {
        T apply(Transaction transaction) throws CloudException;
    }
}
