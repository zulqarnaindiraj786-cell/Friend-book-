package com.friendbook.social.core;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Blocking bridges over {@link CloudflareStore}'s callback API, for work that runs on a
 * background thread and has to finish a read or a write before taking the next step.
 *
 * <p>Both push workers were written against Firestore transactions and Firestore reads, which let
 * them {@code Tasks.await(...)} a step and inspect the result. D1 is reached over the same
 * callback API the screens use, so a worker needs the same two outcomes - the value, or a clear
 * refusal - without an interface of its own. A timeout is a refusal, never a silent success: a
 * worker that treats a lost request as done leaves a user with no notification and no retry.
 *
 * <p>Callbacks are delivered on the main thread by {@link CloudflareStore}, so blocking a worker
 * thread on these latches is safe - the main looper is free to run them.
 */
public final class D1Blocking {

    private static final long TIMEOUT_SECONDS = 25L;

    private D1Blocking() {
    }

    /** Reads one document; {@code null} when it does not exist or the read was refused. */
    public static Map<String, Object> get(String collection, String docId) {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Map<String, Object>> out = new AtomicReference<>();
        CloudflareStore.get(collection, docId, doc -> {
            out.set(doc);
            done.countDown();
        });
        try {
            if (!done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        }
        return out.get();
    }

    /**
     * Writes one document and reports whether D1 accepted it.
     *
     * @throws IllegalStateException on timeout or refusal, so a worker cannot mistake a failed
     *                               write for a completed step
     */
    public static void put(String collection, String docId, Map<String, Object> fields) {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicBoolean ok = new AtomicBoolean(false);
        CloudflareStore.put(collection, docId,
                fields == null ? Collections.<String, Object>emptyMap() : fields,
                () -> {
                    ok.set(true);
                    done.countDown();
                },
                done::countDown);
        await(done, collection + "/" + docId);
        if (!ok.get()) throw new IllegalStateException("D1 refused " + collection + "/" + docId);
    }

    /** One page of a query; an empty page on refusal so a loop can end rather than spin. */
    public static CloudflareStore.Page query(DocQuery query) {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<CloudflareStore.Page> out = new AtomicReference<>();
        CloudflareStore.query(query, page -> {
            out.set(page);
            done.countDown();
        }, done::countDown);
        try {
            if (!done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        }
        return out.get();
    }

    private static void await(CountDownLatch done, String what) {
        try {
            if (!done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("D1 write timed out for " + what);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("D1 write interrupted for " + what);
        }
    }
}
