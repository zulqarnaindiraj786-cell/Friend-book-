package com.friendbook.social.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/** Badge lookup state, separate from Android and authenticated transport. */
final class BadgeLookupState<V> {
    static final class Flight<V> {
        final String actor, uid;
        final long generation;
        final WeakHashMap<V, Boolean> waiters = new WeakHashMap<>();
        Flight(String actor, String uid, long generation) {
            this.actor=actor;this.uid=uid;this.generation=generation;
        }
    }
    private String actor;
    private long generation;
    private final Map<String,String> levels=new HashMap<>();
    private final Map<String,Flight<V>> pending=new HashMap<>();
    private final Map<String,Long> failedUntil=new HashMap<>();
    private final WeakHashMap<V,String> targets=new WeakHashMap<>();

    private void scope(String currentActor) {
        if (!Objects.equals(actor,currentActor)) {
            clear();actor=currentActor;
        }
    }
    synchronized void clear() {generation++;levels.clear();pending.clear();failedUntil.clear();targets.clear();}
    synchronized String cached(String currentActor,String uid) {scope(currentActor);return levels.get(uid);}
    synchronized void put(String currentActor,String uid,String level) {scope(currentActor);levels.put(uid,level);}
    synchronized Flight<V> bind(String currentActor,String uid,V view,long now) {
        scope(currentActor);
        targets.put(view,uid); // Also replaces the target when this view is recycled.
        if (actor==null || uid==null || uid.isEmpty() || levels.containsKey(uid)
                || now<failedUntil.getOrDefault(uid,0L)) return null;
        Flight<V> flight=pending.get(uid);
        boolean start=flight==null;
        if (start) {flight=new Flight<>(actor,uid,generation);pending.put(uid,flight);}
        flight.waiters.put(view,Boolean.TRUE);
        return start?flight:null;
    }
    synchronized boolean current(String currentActor,Flight<V> flight) {
        scope(currentActor);
        return generation==flight.generation && Objects.equals(actor,flight.actor)
                && pending.get(flight.uid)==flight;
    }
    synchronized List<V> finish(String currentActor,Flight<V> flight,String level,long now) {
        if (!current(currentActor,flight)) return new ArrayList<>();
        pending.remove(flight.uid);
        if (level==null) {failedUntil.put(flight.uid,now+10_000L);return new ArrayList<>();}
        // An explicit badge update during the flight wins over the older read.
        levels.putIfAbsent(flight.uid,level);
        failedUntil.remove(flight.uid);
        List<V> views=new ArrayList<>();
        for (V view:flight.waiters.keySet())
            if (Objects.equals(targets.get(view),flight.uid)) views.add(view);
        return views;
    }
}
