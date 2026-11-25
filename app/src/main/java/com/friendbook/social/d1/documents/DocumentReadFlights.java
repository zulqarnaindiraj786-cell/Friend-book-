package com.friendbook.social.d1.documents;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

/** Active DEFAULT reads only. Not a result cache; SERVER reads never join. */
final class DocumentReadFlights<V> {
    private static final class Flight<V> {
        final Task<V> task;
        final AtomicBoolean backgroundAllowed;
        final boolean initiallyAutomatic;
        Flight(Task<V> task, boolean backgroundAllowed) {
            this.task=task; this.backgroundAllowed=new AtomicBoolean(backgroundAllowed);
            this.initiallyAutomatic=!backgroundAllowed;
        }
    }
    static final class Access<V> {
        final Task<V> task;
        final boolean promotedAutomatic;
        Access(Task<V> task, boolean promotedAutomatic) {
            this.task=task; this.promotedAutomatic=promotedAutomatic;
        }
    }
    private final Map<java.util.List<String>, Flight<V>> active = new HashMap<>();

    synchronized Task<V> read(String actor, String path, boolean share, Callable<Task<V>> start) {
        return readPromotable(actor,path,share,true,ignored -> start.call()).task;
    }

    interface Starter<V> { Task<V> start(AtomicBoolean backgroundAllowed) throws Exception; }

    /** A user-requested read may promote an already queued automatic read. */
    synchronized Access<V> readPromotable(String actor, String path, boolean share,
            boolean allowBackground, Starter<V> start) {
        java.util.List<String> key = java.util.Arrays.asList(actor, path);
        if (share) {
            Flight<V> existing = active.get(key);
            if (existing != null) {
                if (allowBackground) existing.backgroundAllowed.set(true);
                return new Access<>(existing.task,allowBackground && existing.initiallyAutomatic);
            }
        }
        TaskCompletionSource<V> result = new TaskCompletionSource<>();
        Flight<V> flight = new Flight<>(result.getTask(),allowBackground);
        if (share) active.put(key, flight);
        try {
            start.start(flight.backgroundAllowed).addOnCompleteListener(Runnable::run, completed -> {
                synchronized (DocumentReadFlights.this) {
                    if (share) active.remove(key, flight);
                }
                if (completed.isSuccessful()) result.trySetResult(completed.getResult());
                else result.trySetException(completed.getException() != null ? completed.getException() :
                        new java.io.IOException("Document read cancelled"));
            });
        } catch (Exception failure) {
            if (share) active.remove(key, flight);
            result.trySetException(failure);
        }
        return new Access<>(flight.task,false);
    }

    /** A document mutation can change membership/order in any active query. */
    synchronized void invalidateAll() { active.clear(); }

    /** Detach pre-mutation flights; their existing consumers still complete normally. */
    synchronized void invalidate(String actor, String path) {
        active.remove(java.util.Arrays.asList(actor, path));
    }
}
