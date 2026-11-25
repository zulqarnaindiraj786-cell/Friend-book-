package com.friendbook.social.core;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicLong;

/** Shares only overlapping authenticated GETs. No TTL cache and no write replay. */
final class EngagementReadCoalescer {
    private final ConcurrentHashMap<Key, FutureTask<String>> active = new ConcurrentHashMap<>();
    private final AtomicLong epoch = new AtomicLong();

    String execute(boolean shareRead, String actor, String endpoint, Callable<String> request) throws Exception {
        if (!shareRead) {
            // A read after a completed mutation must not join a pre-mutation flight.
            epoch.incrementAndGet();
            try { return request.call(); }
            finally { epoch.incrementAndGet(); }
        }
        Key key = new Key(actor, endpoint, epoch.get());
        FutureTask<String> candidate = new FutureTask<>(request);
        FutureTask<String> existing = active.putIfAbsent(key, candidate);
        if (existing != null) return result(existing);
        try {
            candidate.run();
            return result(candidate);
        } finally {
            active.remove(key, candidate);
        }
    }

    private static String result(FutureTask<String> task) throws Exception {
        try { return task.get(); }
        catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw failure;
        }
    }

    private static final class Key {
        final String actor, endpoint;
        final long epoch;
        Key(String actor, String endpoint, long epoch) {
            this.actor = Objects.requireNonNull(actor);
            this.endpoint = Objects.requireNonNull(endpoint);
            this.epoch = epoch;
        }
        @Override public boolean equals(Object value) {
            if (!(value instanceof Key)) return false;
            Key other = (Key) value;
            return epoch == other.epoch && actor.equals(other.actor) && endpoint.equals(other.endpoint);
        }
        @Override public int hashCode() { return Objects.hash(actor, endpoint, epoch); }
    }
}
