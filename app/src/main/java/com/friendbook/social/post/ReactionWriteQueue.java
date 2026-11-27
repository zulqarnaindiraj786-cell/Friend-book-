package com.friendbook.social.post;

import java.util.ArrayDeque;
import java.util.Objects;

/** Main-thread state for one resource. Preserve active writes, coalesce unsent desired state. */
final class ReactionWriteQueue {
    static final class Target {
        final String type;
        Target(String type) { this.type=type; }
    }
    private interface Intent { void apply(); }
    private final ArrayDeque<Intent> unresolved=new ArrayDeque<>();
    private final ArrayDeque<Target> pending=new ArrayDeque<>();
    private boolean initialized;
    private String desired;
    private String settled;
    private Target active;

    void add(String requested,boolean onlyAdd,boolean toggleAny) {
        Intent intent=()->{
            if(onlyAdd && desired!=null)return;
            desired=nextReaction(desired,requested,toggleAny);
            // Intermediate taps not yet sent are UI intent, not separate writes.
            // Always fold the toggle first; only the latest absolute state matters.
            pending.clear();
            if(!Objects.equals(desired,active!=null?active.type:settled))
                pending.addLast(new Target(desired));
        };
        if(initialized)intent.apply();else unresolved.addLast(intent);
    }

    void initialize(String current) {
        initialized=true;desired=current;settled=current;
        while(!unresolved.isEmpty())unresolved.removeFirst().apply();
    }

    String desired(){return desired;}
    Target begin(){
        if(active!=null)return null;
        active=pending.pollFirst();
        return active;
    }
    void complete(Target target){if(active==target){settled=target.type;active=null;}}
    boolean hasPending(){return !pending.isEmpty();}

    static String nextReaction(String old,String requested,boolean toggleAny) {
        return toggleAny && old!=null ? null : Objects.equals(old,requested)?null:requested;
    }
}
