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
}
