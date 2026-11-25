package com.friendbook.social.core;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/** Shares overlapping cache-first profile lookups, never completed results or fresh reads. */
final class ProfileReadFlights<K,V> {
    private final Map<K,Task<V>> active=new HashMap<>();

    /** Detach pre-mutation operations without cancelling their current consumers. */
    synchronized void invalidateAll() { active.clear(); }

    /** Unrelated operations remain shareable; matched consumers still finish normally. */
    synchronized void invalidateMatching(java.util.function.Predicate<K> predicate) {
        active.keySet().removeIf(predicate);
    }

    synchronized Task<V> read(K key,Callable<Task<V>> start) {
        Task<V> existing=active.get(key);
        if (existing!=null) return existing;
        TaskCompletionSource<V> result=new TaskCompletionSource<>();
        Task<V> shared=result.getTask();
        active.put(key,shared);
        try {
            Task<V> operation=start.call();
            operation.addOnCompleteListener(Runnable::run, completed->{
                // Remove before notifying consumers: a subsequent read must not
                // accidentally join an already completed/missing/error result.
                synchronized (ProfileReadFlights.this) {active.remove(key,shared);}
                if (completed.isSuccessful()) result.trySetResult(completed.getResult());
                else result.trySetException(completed.getException()!=null?completed.getException():
                        new java.io.IOException("Profile lookup cancelled"));
            });
        } catch(Exception failure) {
            active.remove(key,shared);
            result.trySetException(failure);
        }
        return shared;
    }
}
