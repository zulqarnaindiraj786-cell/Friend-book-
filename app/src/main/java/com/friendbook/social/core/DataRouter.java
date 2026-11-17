package com.friendbook.social.core;

import java.util.Map;

/**
 * Routes a document write to D1, with Firestore as the durability backstop.
 *
 * <p><b>Why this exists.</b> The app's reads for the feed and the Reels tab already come from
 * D1, but every write still went to Firestore. That is the wrong way round: with Firestore's
 * Spark quota spent, the app could read (from D1) but not write, so a post, message or reaction
 * silently vanished at the exact moment users were told to keep using the app. Writes have to
 * land somewhere that is not rate-limited out of existence.
 *
 * <p><b>Why Firestore is still called.</b> Only as a failure path. If the worker refuses or is
 * unreachable, the write is forwarded to Firestore so nothing is lost. This is not a read
 * fallback and it never runs when D1 answers - a healthy D1 means Firestore is not touched at
 * all, so a spent quota cannot affect normal operation.
 *
 * <p>Every call site keeps its existing Firestore {@code Task} contract: the returned task
 * completes when D1 has accepted the write, so {@code addOnSuccessListener} handlers fire at
 * the right moment and no screen needs to change.
 */
public final class DataRouter {

    /**
     * The release the D1 data plane shipped in.
     *
     * <p>Named so a crash report or a log line can be tied to a build that has the switch.
     */
    public static final String D1_DATA_PLANE_SINCE = "2.9.0";

    private DataRouter() {
    }

    /** True when writes for this collection should go to D1 first. */
    public static boolean d1First(String collection) {
        return com.friendbook.social.BuildConfig.CLOUDFLARE_DATA_ENABLED
                && collection != null && !collection.isEmpty();
    }

    /**
     * Writes {@code fields} to {@code collection/docId}.
     *
     * @param merge     true for a Firestore-style merge (update), false to replace
     * @param onSuccess run after D1 accepted the write
     * @param onFailure run when both D1 and the Firestore backstop failed
     * @param firestore the Firestore write to use as the backstop; may be null when the caller
     *                  has no Firestore write to offer (then a D1 failure is simply reported)
     */
    public static void write(String collection, String docId, Map<String, Object> fields,
                             boolean merge, Runnable onSuccess, Runnable onFailure,
                             Runnable firestore) {
        if (!d1First(collection) || docId == null || docId.isEmpty()) {
            if (firestore != null) firestore.run();
            return;
        }
        Map<String, Object> payload = fields == null ? new java.util.HashMap<>() : fields;
        if (merge) {
            // The worker merges server-side for PUT, so a merge and a replace are the same
            // request; the flag is kept so a future replace-only endpoint can honour it.
            payload = new java.util.HashMap<>(payload);
        }
        CloudflareStore.put(collection, docId, payload,
                onSuccess != null ? onSuccess : () -> { },
                () -> {
                    // D1 refused. Do not pretend the write succeeded; forward it so the user's
                    // post or message is not lost, and only report failure if that also fails.
                    if (firestore != null) {
                        firestore.run();
                    } else if (onFailure != null) {
                        onFailure.run();
                    }
                });
    }

    /** Deletes {@code collection/docId} from D1, falling back to Firestore on refusal. */
    public static void delete(String collection, String docId, Runnable onSuccess,
                              Runnable firestore) {
        if (!d1First(collection) || docId == null || docId.isEmpty()) {
            if (firestore != null) firestore.run();
            return;
        }
        CloudflareStore.remove(collection, docId,
                onSuccess != null ? onSuccess : () -> { },
                () -> {
                    if (firestore != null) firestore.run();
                });
    }

    /**
     * A batch that commits to D1 and reports D1's answer.
     *
     * <p>This is what a write call site should use instead of {@code Fb.db().batch()} plus a
     * {@code mirrorPut}. The Firestore batch reported the outcome of a write to a store that is
     * switched off, so a successful D1 write was surfaced to the user as a failure.
     */
    public static D1Batch batch() {
        return new D1Batch();
    }

    /**
     * Writes one new document straight to D1 and reports D1's answer.
     *
     * <p>This replaces {@code Fb.db().collection(c).add(map)} followed by a {@code mirrorPut}.
     * The add() was the actual write, so when Firestore was switched off a new post, report or
     * message was never created - and the success listener that opened the next screen never
     * ran. The id is generated here because D1 needs a key up front, and the caller is given the
     * same id so a later reference to the document matches what was stored.
     */
    public static void add(String collection, java.util.Map<String, Object> fields,
                           AddCallback callback) {
        if (collection == null || fields == null) {
            if (callback != null) callback.done(null, "Nothing to write");
            return;
        }
        String id;
        try {
            id = Fb.db().collection(collection).document().getId();
        } catch (Throwable unavailable) {
            // Firestore's client cannot mint an id when it was never initialised; any unique
            // string does, so the write still lands.
            id = collection + "_" + System.currentTimeMillis() + "_"
                    + Integer.toHexString(new java.util.Random().nextInt(0x10000));
        }
        final String newId = id;
        CloudflareStore.put(collection, newId, fields,
                () -> { if (callback != null) callback.done(newId, null); },
                () -> { if (callback != null) callback.done(null, "D1 refused " + collection); });
    }

    /** Outcome of an {@link #add}. */
    public interface AddCallback {
        void done(String id, String error);
    }

    /**
     * Mirrors a write into D1, leaving the caller's Firestore write exactly as it was.
     *
     * <p>With Firestore's network disabled that call queues locally and spends no quota, so
     * keeping it costs nothing, and keeping it means no write path had to be rewritten around
     * a new callback contract. If the network is ever re-enabled the queued writes flush and
     * the two stores converge instead of diverging.
     */
    public static void mirrorPut(String collection, String docId, java.util.Map<String, Object> fields) {
        if (!d1First(collection) || docId == null || docId.isEmpty() || fields == null) return;
        CloudflareStore.put(collection, docId, fields);
    }

    /**
     * Mirrors {@code update(field, value, field, value, ...)}.
     *
     * <p>The pairs are kept paired. A version of this that took a truncated map silently wrote
     * the wrong field, which is worse than not mirroring at all.
     */
    public static void mirrorPut(String collection, String docId, Object... fieldValuePairs) {
        if (!d1First(collection) || docId == null || docId.isEmpty()) return;
        if (fieldValuePairs == null || fieldValuePairs.length < 2) return;
        java.util.Map<String, Object> fields = new java.util.HashMap<>();
        for (int i = 0; i + 1 < fieldValuePairs.length; i += 2) {
            Object key = fieldValuePairs[i];
            if (key instanceof String) fields.put((String) key, fieldValuePairs[i + 1]);
        }
        if (!fields.isEmpty()) CloudflareStore.put(collection, docId, fields);
    }

    /**
     * Mirrors a write addressed by a Firestore path like {@code users/abc123}.
     *
     * <p>Batch writes hand the reference around as an argument, so there is no statement
     * boundary to hang a mirror on. Taking the path keeps the mirror next to the reference it
     * belongs to instead of guessing from the surrounding text.
     */
    public static void mirrorPutPath(String path, java.util.Map<String, Object> fields) {
        String[] parts = splitPath(path);
        if (parts != null) mirrorPut(parts[0], parts[1], fields);
    }

    /** Mirrors an {@code update(field, value, ...)} addressed by a Firestore path. */
    public static void mirrorPutPath(String path, Object... fieldValuePairs) {
        String[] parts = splitPath(path);
        if (parts != null) mirrorPut(parts[0], parts[1], fieldValuePairs);
    }

    /** Mirrors a delete addressed by a Firestore path. */
    public static void mirrorDeletePath(String path) {
        String[] parts = splitPath(path);
        if (parts != null) mirrorDelete(parts[0], parts[1]);
    }

    /**
     * Returns {collection, docId}, or null when the path is not a single document.
     *
     * <p>Subcollection paths like {@code chats/a/messages/b} are rejected on purpose: the D1
     * table is keyed by collection and id, so flattening them would put a message in the chat's
     * table under a made-up id.
     */
    private static String[] splitPath(String path) {
        if (path == null) return null;
        int slash = path.indexOf('/');
        if (slash <= 0 || slash == path.length() - 1) return null;
        if (path.indexOf('/', slash + 1) >= 0) return null;
        return new String[]{path.substring(0, slash), path.substring(slash + 1)};
    }

    /**
     * Mirrors an {@code update(field, value, ...)} into D1.
     *
     * <p>Sentinel values are refused rather than written. {@code FieldValue.increment(1)} is an
     * object, so a plain put stores the object and the counter reads as garbage forever. The
     * sites that use sentinels call {@link #mirrorOps} with the operation spelled out instead.
     */
    public static void mirrorUpdate(String collection, String docId, Object... fieldValuePairs) {
        if (!d1First(collection) || docId == null || docId.isEmpty()) return;
        if (fieldValuePairs == null || fieldValuePairs.length < 2) return;
        java.util.Map<String, Object> plain = new java.util.HashMap<>();
        for (int i = 0; i + 1 < fieldValuePairs.length; i += 2) {
            Object key = fieldValuePairs[i];
            Object value = fieldValuePairs[i + 1];
            if (!(key instanceof String)) continue;
            if (value instanceof com.google.firebase.firestore.FieldValue) continue;
            plain.put((String) key, value);
        }
        if (!plain.isEmpty()) CloudflareStore.put(collection, docId, plain);
    }

    /**
     * Mirrors explicit atomic operations into D1.
     *
     * <p>These exist because the values a counter needs are not always expressible as a
     * literal. The caller knows whether it is adding one, subtracting one, or adding this exact
     * uid to a list, so it says so, and D1 applies it without reading the old value back.
     */
    public static void mirrorOps(String collection, String docId, org.json.JSONObject... ops) {
        if (!d1First(collection) || docId == null || docId.isEmpty() || ops == null) return;
        org.json.JSONArray arr = new org.json.JSONArray();
        for (org.json.JSONObject op : ops) if (op != null && op.length() > 0) arr.put(op);
        if (arr.length() > 0) CloudflareStore.patch(collection, docId, arr);
    }

    /** Mirrors explicit atomic operations on a document named by a Firestore path. */
    public static void mirrorOpsPath(String path, org.json.JSONObject... ops) {
        String[] parts = splitPath(path);
        if (parts != null) mirrorOps(parts[0], parts[1], ops);
    }

    /** Mirrors a plain (sentinel-free) update on a document named by a Firestore path. */
    public static void mirrorUpdatePath(String path, Object... fieldValuePairs) {
        String[] parts = splitPath(path);
        if (parts != null) mirrorUpdate(parts[0], parts[1], fieldValuePairs);
    }

    /** Mirrors a delete into D1. */
    public static void mirrorDelete(String collection, String docId) {
        if (!d1First(collection) || docId == null || docId.isEmpty()) return;
        CloudflareStore.delete(collection, docId, null);
    }
}
