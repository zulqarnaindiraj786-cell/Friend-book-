package com.friendbook.social.post;

import java.util.ArrayDeque;
import java.util.Objects;

/** Main-thread state for one resource. Queue absolute targets, never retry a toggle. */
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
    private Target active;

    void add(String requested,boolean onlyAdd,boolean toggleAny) {
        Intent intent=()->{
            if(onlyAdd && desired!=null)return;
            desired=nextReaction(desired,requested,toggleAny);
            pending.addLast(new Target(desired));
        };
        if(initialized)intent.apply();else unresolved.addLast(intent);
    }

    void initialize(String current) {
        initialized=true;desired=current;
        while(!unresolved.isEmpty())unresolved.removeFirst().apply();
    }

    String desired(){return desired;}
    Target begin(){
        if(active!=null)return null;
        active=pending.pollFirst();
        return active;
    }
    void complete(Target target){if(active==target)active=null;}
    boolean hasPending(){return !pending.isEmpty();}

    static String nextReaction(String old,String requested,boolean toggleAny) {
        return toggleAny && old!=null ? null : Objects.equals(old,requested)?null:requested;
    }
}
