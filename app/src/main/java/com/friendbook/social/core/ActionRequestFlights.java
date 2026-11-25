package com.friendbook.social.core;

import com.google.android.gms.tasks.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.LongSupplier;

/** Share one action ACK while pending and for its one-second click cooldown. Not a read cache. */
final class ActionRequestFlights<K,V> {
    private final Map<K,Entry<V>> active=new HashMap<>();
    private final LongSupplier clock;
    ActionRequestFlights(){this(android.os.SystemClock::elapsedRealtime);}
    ActionRequestFlights(LongSupplier clock){this.clock=clock;}
    private static final class Entry<V>{final long at;final Task<V> task;Entry(long at,Task<V> task){this.at=at;this.task=task;}}
    synchronized Task<V> run(K key,Callable<Task<V>> start){
        long now=clock.getAsLong();
        active.entrySet().removeIf(e->e.getValue().task.isComplete() && (now<e.getValue().at || now-e.getValue().at>=InteractionCooldown.WINDOW_MS));
        Entry<V> prior=active.get(key);if(prior!=null)return prior.task;
        TaskCompletionSource<V> result=new TaskCompletionSource<>();active.put(key,new Entry<>(now,result.getTask()));
        try{start.call().addOnCompleteListener(Runnable::run,task->{
            if(task.isSuccessful())result.trySetResult(task.getResult());
            else result.trySetException(task.getException()!=null?task.getException():new java.io.IOException("Action canceled"));
        });}catch(Exception error){result.trySetException(error);}
        return result.getTask();
    }
}
