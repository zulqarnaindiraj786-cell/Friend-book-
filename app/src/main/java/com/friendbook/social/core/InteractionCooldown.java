package com.friendbook.social.core;

import java.util.*;

/** Main UI action budget, not a data/authorization cache. */
public final class InteractionCooldown {
    public static final long WINDOW_MS=1_000L;
    private static final InteractionCooldown SHARED=new InteractionCooldown();
    private final Map<List<String>,Long> accepted=new HashMap<>();
    public static boolean acquire(String actor,String resource){
        return SHARED.acquireAt(actor,resource,android.os.SystemClock.elapsedRealtime());
    }
    synchronized boolean acquireAt(String actor,String resource,long now){
        if(actor==null || resource==null)return false;
        accepted.entrySet().removeIf(e->now<e.getValue() || now-e.getValue()>=WINDOW_MS);
        List<String> key=Arrays.asList(actor,resource);
        if(accepted.containsKey(key))return false;
        accepted.put(key,now);return true;
    }
    /** Keep the actual button disabled, in addition to the shared action guard. */
    public static boolean disableBriefly(android.view.View view){
        if(view==null || !view.isEnabled())return false;
        view.setEnabled(false);
        view.postDelayed(()->view.setEnabled(true),WINDOW_MS);
        return true;
    }
}
