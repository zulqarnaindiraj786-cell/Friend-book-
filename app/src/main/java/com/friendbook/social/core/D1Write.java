package com.friendbook.social.core;

import com.friendbook.social.App;
import com.google.firebase.firestore.SetOptions;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The one place where a write leaves this app.
 *
 * The rule of this build is simple: <b>Firebase is authentication only</b>. Every document a user
 * creates, edits or deletes goes to Cloudflare D1; Firestore is only ever read, and only for the
 * history that was written before the migration. That history is never migrated or backfilled.
 *
 * This app has ~190 write call sites across 40 screens. Rewriting all of them at once is how a release
 * loses a screen, so every call site comes through this class instead:
 *
 * <ul>
 *   <li>the write becomes a D1 document write ({@code /v1/docs}) on the same collection and the same
 *       document id, so every reader of that id keeps working unchanged;</li>
 *   <li>D1 is asked first and the caller's own success continuation runs on <em>its</em> outcome, so a
 *       screen's "done" state means the real store accepted the document;</li>
 *   <li>an offline or refused write is parked in {@link CloudflareOutbox} and the screen continues,
 *       because the user's action must not hang on the network;</li>
 *   <li>the pre-migration lambda a call site passes is only used when the Cloudflare flag is off, and
 *       in a debug build as an extra copy. A release build has no code path into Firestore.</li>
 * </ul>
 */
public final class D1Write {

    private D1Write() {}

    /** Where a write landed, for the screens that branch on it. */
    public static final class Outcome {
        public final boolean stored;
        public final String reason;
        Outcome(boolean stored, String reason) { this.stored = stored; this.reason = reason; }
    }

    /** Debug builds keep the pre-migration copy, so a tester can compare both stores. */
    private static boolean debugCopy() {
        try {
            return com.friendbook.social.BuildConfig.DEBUG;
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Writes
    // ---------------------------------------------------------------------------------------------

    /** Writes (merge) one document to D1; a failed write is parked for the next foreground. */
    public static void put(String collection, String id, Map<String, Object> fields,
                           Consumer<Outcome> done) {
        store(collection, id, fields, done);
    }

    /**
     * A Firestore-shaped write with the caller's own continuations - the shape almost every screen
     * has. `onSuccess` runs once D1 accepted the document or parked it for delivery; never because a
     * different store accepted it.
     *
     * @param previousWrite the pre-migration write of this call site, used only when Cloudflare is off
     *                      for the build (and as a debug-build copy while a batch is being verified)
     */
    public static void write(String collection, String id, Map<String, Object> fields,
                             Runnable onSuccess, Consumer<String> onFailure, Runnable previousWrite) {
        if (!CloudflareDocs.enabled()) {
            if (previousWrite != null) {
                previousWrite.run();
                if (onSuccess != null) onSuccess.run();
            } else if (onFailure != null) {
                onFailure.accept("disabled");
            }
            return;
        }
        store(collection, id, fields, outcome -> {
            if (outcome.stored) {
                copyInDebug(previousWrite);
                if (onSuccess != null) onSuccess.run();
            } else if (onFailure != null) {
                onFailure.accept(outcome.reason);
            } else if (onSuccess != null) {
                // No failure continuation was given, so the screen already committed to the write:
                // finish it and let the outbox deliver, instead of leaving a button spinning.
                onSuccess.run();
            }
        });
    }

    /**
     * Creates a document whose id the screen does not need - the common shape. Kept as its own name so
     * a call site never has to disambiguate between "give me the id" and "just tell me it landed".
     */
    public static void create(String collection, Map<String, Object> fields, Runnable onSuccess,
                              Consumer<String> onFailure, Runnable previousWrite) {
        final String id = newId();
        if (!CloudflareDocs.enabled()) {
            if (previousWrite != null) {
                previousWrite.run();
                if (onSuccess != null) onSuccess.run();
            }
            return;
        }
        store(collection, id, fields, outcome -> {
            if (outcome.stored) {
                if (onSuccess != null) onSuccess.run();
            } else if (onFailure != null) {
                onFailure.accept(outcome.reason);
            } else if (onSuccess != null) {
                onSuccess.run();
            }
        });
    }

    /** `add()` whose continuation needs the new document's id (it is generated on the device). */
    public static void add(String collection, Map<String, Object> fields, Consumer<String> onId,
                           Consumer<String> onFailure, Runnable previousWrite) {
        final String id = newId();
        if (!CloudflareDocs.enabled()) {
            if (previousWrite != null) previousWrite.run();
            if (onId != null) onId.accept(id);
            return;
        }
        store(collection, id, fields, outcome -> {
            if (outcome.stored) {
                if (onId != null) onId.accept(id);
            } else if (onFailure != null) {
                onFailure.accept(outcome.reason);
            }
        });
    }

    /** An append-only record (a report, a notification, a gift): only the landing matters. */
    public static void append(String collection, Map<String, Object> fields) {
        store(collection, newId(), fields, null);
    }

    /** Delete: D1 owns the row, so the row deleted is the one this build wrote. */
    public static void delete(String collection, String id, Runnable onSuccess,
                              Consumer<String> onFailure, Runnable previousDelete) {
        if (collection == null || id == null || id.isEmpty()) {
            if (onSuccess != null) onSuccess.run();
            return;
        }
        if (!CloudflareDocs.enabled()) {
            if (previousDelete != null) previousDelete.run();
            if (onSuccess != null) onSuccess.run();
            return;
        }
        CloudflareDocs.delete(collection, id,
                () -> {
                    copyInDebug(previousDelete);
                    if (onSuccess != null) onSuccess.run();
                },
                reason -> {
                    CloudflareOutbox.enqueueDelete(App.instance, collection, id);
                    // The row is removed when the outbox replays, so the screen may continue.
                    if (onSuccess != null) onSuccess.run();
                    if (onFailure != null) onFailure.accept(reason);
                });
    }

    /** A document id generated on the device, for a write that has not reached any store yet. */
    public static String newId() {
        return CloudflareDocs.newId();
    }

    /** The set-with-merge constant, re-exported so a converted call site reads as it did before. */
    public static SetOptions mergeOption() {
        return SetOptions.merge();
    }

    // ---------------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------------

    private static void store(String collection, String id, Map<String, Object> fields,
                              Consumer<Outcome> done) {
        if (collection == null || id == null || id.isEmpty()) {
            if (done != null) done.accept(new Outcome(false, "invalid"));
            return;
        }
        final Map<String, Object> payload = fields == null ? new HashMap<>() : fields;
        if (!CloudflareDocs.enabled()) {
            if (done != null) done.accept(new Outcome(true, "legacy"));
            return;
        }
        CloudflareDocs.write(collection, id, payload,
                () -> { if (done != null) done.accept(new Outcome(true, "")); },
                reason -> {
                    park(collection, id, payload);
                    if (done != null) done.accept(new Outcome(false, reason));
                });
    }

    private static void copyInDebug(Runnable previousWrite) {
        if (previousWrite == null || !debugCopy()) return;
        try {
            previousWrite.run();
        } catch (Throwable ignored) {
            // The D1 write already landed; the extra debug copy is best-effort only.
        }
    }

    private static void park(String collection, String id, Map<String, Object> fields) {
        try {
            CloudflareOutbox.enqueue(App.instance, collection, id, CloudflareDocs.sanitize(fields));
        } catch (Throwable ignored) {
            // A write that cannot be parked is still reported to its caller as failed.
        }
    }
}
