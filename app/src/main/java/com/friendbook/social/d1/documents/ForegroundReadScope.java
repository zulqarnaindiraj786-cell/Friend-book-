package com.friendbook.social.d1.documents;

import com.friendbook.social.App;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

/** Lifecycle-owned automatic reads. Explicit user reads do not use this scope. */
public final class ForegroundReadScope implements AutoCloseable {
    private static final Object LOCK = new Object();
    private static final Set<Pending<?>> WAITING = new HashSet<>();
    private final Set<Pending<?>> owned = new HashSet<>();
    private boolean closed;

    <T> Task<T> read(Supplier<Task<T>> operation) {
        Pending<T> pending = new Pending<>(this, operation);
        synchronized (LOCK) {
            if (!closed) owned.add(pending);
            else pending.done=true;
        }
        if (pending.done) pending.result.trySetException(cancelled());
        else pending.attempt();
        return pending.result.getTask();
    }

    /** A one-shot foreground signal, cancelled with this scope. */
    public Task<Void> whenForeground() {
        return read(() -> com.google.android.gms.tasks.Tasks.forResult(null));
    }

    /** Called after the process actually enters the foreground. */
    public static void resume() {
        ArrayList<Pending<?>> pending;
        synchronized (LOCK) { pending = new ArrayList<>(WAITING); }
        for (Pending<?> item : pending) item.attempt();
    }

    @Override public void close() {
        ArrayList<Pending<?>> pending;
        synchronized (LOCK) {
            if (closed) return;
            closed=true;
            pending=new ArrayList<>(owned);
            owned.clear();
            for (Pending<?> item : pending) { item.done=true; WAITING.remove(item); }
        }
        for (Pending<?> item : pending) item.result.trySetException(cancelled());
    }

    private static D1DocumentStoreException cancelled() {
        return new D1DocumentStoreException(D1DocumentStoreException.Code.CANCELLED,"Screen stopped");
    }

    private static final class Pending<T> {
        final ForegroundReadScope scope;
        final Supplier<Task<T>> operation;
        final TaskCompletionSource<T> result = new TaskCompletionSource<>();
        boolean done, executing;
        Pending(ForegroundReadScope scope,Supplier<Task<T>> operation) {
            this.scope=scope; this.operation=operation;
        }
        void attempt() {
            synchronized (LOCK) {
                if (done || executing) return;
                if (!App.isForeground()) { WAITING.add(this); return; }
                WAITING.remove(this);
                executing=true;
            }
            try {
                operation.get().addOnCompleteListener(Runnable::run, completed -> {
                    boolean retry;
                    synchronized (LOCK) {
                        executing=false;
                        if (done) return;
                        retry=completed.getException() instanceof D1DocumentStore.BackgroundQueryDeferred;
                        if (!retry) { done=true; scope.owned.remove(this); WAITING.remove(this); }
                    }
                    if (retry) attempt();
                    else if (completed.isSuccessful()) result.trySetResult(completed.getResult());
                    else result.trySetException(completed.getException()!=null ? completed.getException()
                            : new java.io.IOException("Automatic read cancelled"));
                });
            } catch (Exception failure) {
                synchronized (LOCK) {
                    if (done) return;
                    done=true;executing=false;scope.owned.remove(this);WAITING.remove(this);
                }
                result.trySetException(failure);
            }
        }
    }
}
