package com.friendbook.social.admin;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** In-process single flight; the Worker remains responsible for authorization. */
final class ModerationGate {
    static final ModerationGate SHARED=new ModerationGate();
    private final Set<List<String>> pending=new HashSet<>();
    synchronized boolean begin(String actor,String collection,String id) {
        if(actor==null || actor.isEmpty() || collection==null || id==null || id.isEmpty())return false;
        return pending.add(Arrays.asList(actor,collection,id));
    }
    synchronized void end(String actor,String collection,String id) {
        pending.remove(Arrays.asList(actor,collection,id));
    }
    synchronized boolean pending(String actor,String collection,String id) {
        return pending.contains(Arrays.asList(actor,collection,id));
    }
}
