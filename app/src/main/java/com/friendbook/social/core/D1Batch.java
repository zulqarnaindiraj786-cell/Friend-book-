package com.friendbook.social.core;

import com.google.firebase.firestore.FieldValue;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A write batch that commits to D1, not Firestore.
 *
 * <p><b>Why this exists.</b> Every write in the app was built as
 * {@code batch.set(...); batch.update(...); batch.commit()} against Firestore, with a
 * {@code DataRouter.mirrorPut(...)} beside it that also wrote D1. That looks like a dual write
 * and is not one: the caller's success callback was attached to {@code commit()}, so when
 * Firestore refused - which it does all day on a spent quota - the app reported failure and the
 * user saw "couldn't post", even though D1 had accepted the write. Reads had been moved to D1;
 * writes had not, and a new account could not create anything.
 *
 * <p>This class is the missing half. It accepts the same calls - set, update, delete, with
 * FieldValue sentinels - and commits them to D1 in one pass, reporting D1's answer. Firestore is
 * no longer in the path at all, so a refused or unreachable Firestore cannot fail a write the
 * user made.
 *
 * <p>Sentinel handling: {@code FieldValue.increment(n)}, {@code arrayUnion} and
 * {@code arrayRemove} become the worker's atomic PATCH operations rather than being sent as a
 * literal value, because a round-tripped sentinel object stored as data is a silent corruption.
 */
public final class D1Batch {

    /** One queued operation. */
    private static final class Op {
        final String collection;
        final String docId;
        final Map<String, Object> fields;   // for set/update
        final JSONArray ops;                // for atomic field operations
        final boolean merge;                // true for update, false for set
        final boolean delete;

        Op(String collection, String docId, Map<String, Object> fields, JSONArray ops,
           boolean merge, boolean delete) {
            this.collection = collection;
            this.docId = docId;
            this.fields = fields;
            this.ops = ops;
            this.merge = merge;
            this.delete = delete;
        }
    }

    private final List<Op> queued = new ArrayList<>();

    /**
     * Runs when D1 refuses the batch.
     *
     * <p>D1 is the only store the app writes to now, so a refusal is a real failure and the
     * caller must hear about it. The hook exists so the caller can retry through its existing
     * Firestore code path if one is still worth trying, without this class knowing about
     * Firestore at all.
     */
    private Runnable onRefused;

    /** Installs the refusal hook; it runs once, on the main thread, after D1 says no. */
    public D1Batch orElse(Runnable refused) {
        this.onRefused = refused;
        return this;
    }

    /** Queues a full document write. */
    public D1Batch set(String collection, String docId, Map<String, Object> fields) {
        if (collection != null && docId != null && !docId.isEmpty() && fields != null) {
            Route split = Route.of(fields);
            if (split.atomic.length() > 0) {
                // A map that carries sentinels needs both halves: the plain fields and the
                // atomic ones, in that order, so an increment is not overwritten by a stale
                // absolute value in the same document.
                queued.add(new Op(collection, docId, split.plain, null, false, false));
                queued.add(new Op(collection, docId, null, split.atomic, false, false));
            } else {
                queued.add(new Op(collection, docId, split.plain, null, false, false));
            }
        }
        return this;
    }

    /** Queues a merge of the given fields. */
    public D1Batch update(String collection, String docId, Map<String, Object> fields) {
        if (collection != null && docId != null && !docId.isEmpty() && fields != null) {
            Route split = Route.of(fields);
            if (split.plain != null && !split.plain.isEmpty()) {
                queued.add(new Op(collection, docId, split.plain, null, true, false));
            }
            if (split.atomic.length() > 0) {
                queued.add(new Op(collection, docId, null, split.atomic, true, false));
            }
        }
        return this;
    }

    /** Queues an update given as alternating field/value pairs, like Firestore's update(). */
    public D1Batch update(String collection, String docId, Object... fieldValuePairs) {
        if (fieldValuePairs == null || fieldValuePairs.length < 2) return this;
        Map<String, Object> fields = new HashMap<>();
        for (int i = 0; i + 1 < fieldValuePairs.length; i += 2) {
            Object key = fieldValuePairs[i];
            if (key instanceof String) fields.put((String) key, fieldValuePairs[i + 1]);
        }
        return update(collection, docId, fields);
    }

    /**
     * Queues atomic field operations - increments, array unions, array removes.
     *
     * <p>These are the three things a plain document write cannot express. Sending the sentinel
     * itself would store it as data, and read-modify-write in the app loses concurrent updates,
     * so the worker applies them inside one statement.
     */
    public D1Batch ops(String collection, String docId, JSONObject... operations) {
        if (collection == null || docId == null || docId.isEmpty() || operations == null) return this;
        JSONArray array = new JSONArray();
        for (JSONObject op : operations) {
            if (op != null && op.length() > 0) array.put(op);
        }
        if (array.length() > 0) {
            queued.add(new Op(collection, docId, null, array, true, false));
        }
        return this;
    }

    public D1Batch delete(String collection, String docId) {
        if (collection != null && docId != null && !docId.isEmpty()) {
            queued.add(new Op(collection, docId, null, null, false, true));
        }
        return this;
    }

    /**
     * Sends everything to D1.
     *
     * <p>Callbacks fire on the main thread, once, after the last operation is acknowledged. A
     * single failure fails the batch - a partially applied post-and-counter is worse than a
     * retry - and the message says how many operations were sent so a silent failure is
     * distinguishable from an empty batch.
     */
    public void commit(Callback callback) {
        if (queued.isEmpty()) {
            if (callback != null) callback.done(true, null);
            return;
        }

        final Op[] pending = queued.toArray(new Op[0]);
        if (pending.length == 1) {
            commitIndividually(pending, callback);
            return;
        }

        // Pack multi-op batches into POST /v1/docs/batch chunks (<= 40 ops and <= 24 KB each so
        // D1HttpTransport's 32 KB ceiling is never exceeded). One HTTP request replaces N.
        List<JSONArray> chunks = new ArrayList<>();
        List<java.util.Set<String>> chunkCollections = new ArrayList<>();
        List<List<Op>> chunkOps = new ArrayList<>();
        try {
            JSONArray current = new JSONArray();
            java.util.Set<String> cols = new java.util.HashSet<>();
            List<Op> rawOps = new ArrayList<>();
            int approxBytes = 16;
            for (Op op : pending) {
                JSONObject item = new JSONObject();
                item.put("collection", op.collection);
                item.put("id", op.docId);
                if (op.delete) {
                    item.put("action", "delete");
                } else if (op.ops != null) {
                    item.put("action", "patch");
                    item.put("ops", op.ops);
                } else {
                    item.put("action", "put");
                    item.put("document", CloudflareStore.toJson(op.fields));
                }
                int itemBytes = item.toString().length() + 2;
                if (itemBytes > 24_000) {
                    // Single large document: fall back to individual requests.
                    commitIndividually(pending, callback);
                    return;
                }
                if (current.length() > 0 && (current.length() >= 40 || approxBytes + itemBytes > 24_000)) {
                    chunks.add(current);
                    chunkCollections.add(cols);
                    chunkOps.add(rawOps);
                    current = new JSONArray();
                    cols = new java.util.HashSet<>();
                    rawOps = new ArrayList<>();
                    approxBytes = 16;
                }
                current.put(item);
                cols.add(op.collection);
                rawOps.add(op);
                approxBytes += itemBytes;
            }
            if (current.length() > 0) {
                chunks.add(current);
                chunkCollections.add(cols);
                chunkOps.add(rawOps);
            }
        } catch (Throwable buildErr) {
            commitIndividually(pending, callback);
            return;
        }

        sendBatchChunk(chunks, chunkCollections, chunkOps, 0, callback);
    }

    private void sendBatchChunk(List<JSONArray> chunks, List<java.util.Set<String>> chunkCollections,
                                List<List<Op>> chunkOps, int index, Callback callback) {
        if (index >= chunks.size()) {
            if (callback != null) callback.done(true, null);
            return;
        }
        CloudflareStore.batch(chunks.get(index), chunkCollections.get(index),
                () -> sendBatchChunk(chunks, chunkCollections, chunkOps, index + 1, callback),
                unsupported -> {
                    if (Boolean.TRUE.equals(unsupported)) {
                        // Rollout safety: server doesn't have /v1/docs/batch yet; run remaining ops individually.
                        List<Op> rest = new ArrayList<>();
                        for (int i = index; i < chunkOps.size(); i++) rest.addAll(chunkOps.get(i));
                        commitIndividually(rest.toArray(new Op[0]), callback);
                        return;
                    }
                    if (callback != null) callback.done(false, "D1 refused batch (" + chunks.get(index).length() + " ops)");
                    if (onRefused != null) {
                        Runnable retry = onRefused;
                        new android.os.Handler(android.os.Looper.getMainLooper()).post(retry);
                    }
                });
    }

    private void commitIndividually(final Op[] pending, final Callback callback) {
        final boolean[] failed = {false};
        final String[] error = {null};
        final int[] remaining = {pending.length};

        for (Op op : pending) {
            Runnable finish = () -> {
                synchronized (remaining) {
                    remaining[0]--;
                    if (remaining[0] == 0) {
                        if (failed[0] && onRefused != null) {
                            if (callback != null) callback.done(false, error[0]);
                            Runnable retry = onRefused;
                            android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                            main.post(retry);
                            return;
                        }
                        if (callback != null) callback.done(!failed[0], error[0]);
                    }
                }
            };
            Runnable fail = () -> {
                synchronized (remaining) {
                    if (!failed[0]) {
                        failed[0] = true;
                        error[0] = "D1 refused " + op.collection + "/" + op.docId;
                    }
                }
                finish.run();
            };

            try {
                if (op.delete) {
                    CloudflareStore.remove(op.collection, op.docId, finish, fail);
                } else if (op.ops != null) {
                    CloudflareStore.patch(op.collection, op.docId, op.ops, finish, fail);
                } else {
                    CloudflareStore.put(op.collection, op.docId, op.fields, finish, fail);
                }
            } catch (Throwable thrown) {
                fail.run();
            }
        }
    }

    /** Convenience for callers that do not care about the outcome. */
    public void commit() {
        commit(null);
    }

    public interface Callback {
        void done(boolean success, String error);
    }

    /** A queued write split into plain fields and atomic operations. */
    private static final class Route {
        final Map<String, Object> plain;
        final JSONArray atomic;

        Route(Map<String, Object> plain, JSONArray atomic) {
            this.plain = plain;
            this.atomic = atomic;
        }

        /**
         * Keeps only values that can be stored as they are.
         *
         * <p>A {@code FieldValue} sentinel cannot be serialised. Sending one would store the
         * object's JSON shape and the field would read back as a map instead of a number or an
         * array. The app builds its atomic operations explicitly - {@link
         * CloudflareStore#increment}, {@link CloudflareStore#arrayUnion}, {@link
         * CloudflareStore#arrayRemove} - and those go through {@link #ops}, so an array sentinel
         * arriving here is a caller that was not converted. Dropping it leaves the field alone,
         * which is recoverable; storing a sentinel is not.
         *
         * <p>{@code serverTimestamp()} is the exception. There is no server to ask, the value is
         * always "now", and dropping it meant a post or a message was written with no creation
         * time at all - which sorts it to the bottom of every list and shows it as 1970. So a
         * timestamp sentinel becomes the current millisecond value, decided once per batch so
         * every field in one write agrees.
         */
        static Route of(Map<String, Object> fields) {
            Map<String, Object> plain = new HashMap<>();
            JSONArray stray = new JSONArray();
            long now = System.currentTimeMillis();
            for (Map.Entry<String, Object> entry : fields.entrySet()) {
                String name = entry.getKey();
                Object value = entry.getValue();
                if (name == null || value == null) continue;
                if (value instanceof FieldValue) {
                    // A time-shaped field keeps a real time; anything else is an unconverted
                    // atomic operation and must not be guessed at.
                    String lower = name.toLowerCase();
                    if (lower.contains("at") || lower.contains("time")) {
                        plain.put(name, now);
                    } else {
                        stray.put(name);
                    }
                    continue;
                }
                plain.put(name, value);
            }
            if (stray.length() > 0) {
                android.util.Log.w("D1Batch",
                        "ignored FieldValue sentinels (use ops instead): " + stray);
            }
            return new Route(plain, new JSONArray());
        }
    }
}
