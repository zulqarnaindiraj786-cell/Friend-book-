package com.example.frienbook.core.cloud;

import com.google.android.gms.tasks.Task;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Several writes sent as one request.
 *
 * The Worker accepts at most 20 operations per call and treats each call as all-or-nothing: one
 * refused write cancels the whole call, so a half-applied batch cannot leave a chat with a message but
 * no "last message" line. A batch bigger than that - the admin broadcast writes to every user - is
 * sent as successive calls of 20, and the first failure stops the run and is reported. That is the one
 * place where this class is weaker than a single transaction would be, and it is stated rather than
 * hidden: earlier calls stay applied, the failing one does not, and the caller hears which one failed.
 *
 * FieldValue markers inside a batched map are split the same way as a single write, so an increment in
 * a batch is still performed by the Worker on the stored row and still cannot be lost to a second
 * writer.
 */
public final class WriteBatch {

    /** The Worker's own ceiling; going over it is a 400, so the client slices instead. */
    private static final int MAX_OPS = 20;

    private final List<JSONObject> ops = new ArrayList<>();

    WriteBatch() { }

    /** Replaces the document (keeping its createdAt). */
    public WriteBatch set(DocumentReference reference, Map<String, Object> data) {
        return add("set", reference, data);
    }

    /** With merge(), the sent fields are laid over what is stored and the rest survives. */
    public WriteBatch set(DocumentReference reference, Map<String, Object> data, SetOptions options) {
        boolean merge = options != null && options.isMerge();
        return add(merge ? "update" : "set", reference, data);
    }

    public WriteBatch update(DocumentReference reference, Map<String, Object> data) {
        return add("update", reference, data);
    }

    public WriteBatch update(DocumentReference reference, String field, Object value, Object... more) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(field, value);
        if (more != null) {
            for (int i = 0; i + 1 < more.length; i += 2) {
                if (more[i] != null) data.put(String.valueOf(more[i]), more[i + 1]);
            }
        }
        return add("update", reference, data);
    }

    /** Tombstones the document: it stops being readable and stays on the server. */
    public WriteBatch delete(DocumentReference reference) {
        return add("delete", reference, null);
    }

    private WriteBatch add(String op, DocumentReference reference, Map<String, Object> data) {
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
            // An operation that cannot be encoded must not be sent as something else.
            return this;
        }
        ops.add(entry);
        return this;
    }

    /** How many writes are queued, so a caller can show progress on a long broadcast. */
    public int size() {
        return ops.size();
    }

    public Task<Void> commit() {
        return Cloud.submit(new Cloud.Job<Void>() {
            @Override
            public Void run() throws Exception {
                for (int start = 0; start < ops.size(); start += MAX_OPS) {
                    JSONArray slice = new JSONArray();
                    for (int i = start; i < Math.min(start + MAX_OPS, ops.size()); i++) slice.put(ops.get(i));
                    JSONObject body = new JSONObject();
                    body.put("ops", slice);
                    Cloud.call("/v1/docs/batch", "POST", body);
                }
                return null;
            }
        });
    }
}
