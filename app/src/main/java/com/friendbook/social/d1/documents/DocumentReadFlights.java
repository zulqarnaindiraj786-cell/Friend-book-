package com.friendbook.social.d1.documents;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/** Active DEFAULT reads only. Not a result cache; SERVER reads never join. */
final class DocumentReadFlights<V> {
    private final Map<java.util.List<String>, Task<V>> active = new HashMap<>();

    synchronized Task<V> read(String actor, String path, boolean share, Callable<Task<V>> start) {
        java.util.List<String> key = java.util.Arrays.asList(actor, path);
        if (share) {
            Task<V> existing = active.get(key);
            if (existing != null) return existing;
        }
        TaskCompletionSource<V> result = new TaskCompletionSource<>();
        Task<V> shared = result.getTask();
        if (share) active.put(key, shared);
        try {
            start.call().addOnCompleteListener(Runnable::run, completed -> {
                synchronized (DocumentReadFlights.this) {
                    if (share) active.remove(key, shared);
                }
                if (completed.isSuccessful()) result.trySetResult(completed.getResult());
                else result.trySetException(completed.getException() != null ? completed.getException() :
                        new java.io.IOException("Document read cancelled"));
            });
        } catch (Exception failure) {
            if (share) active.remove(key, shared);
            result.trySetException(failure);
        }
        return shared;
    }

    /** A document mutation can change membership/order in any active query. */
    synchronized void invalidateAll() {
        active.clear();
    }

    /** Detach pre-mutation flights; their existing consumers still complete normally. */
    synchronized void invalidate(String actor, String path) {
        active.remove(java.util.Arrays.asList(actor, path));
    }
}
