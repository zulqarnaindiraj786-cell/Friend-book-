package com.friendbook.social.push;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Coalesce only immediate duplicate transport deliveries, never logical event IDs. */
final class PushRefreshBurstGate {
    private static final long WINDOW_MS=1_000L;
    private static final int CAPACITY=256;
    private String actor;
    private final Map<String,Long> accepted=new LinkedHashMap<>();

    synchronized boolean allow(String currentActor,String transportId,long now) {
        if (!Objects.equals(actor,currentActor)) {actor=currentActor;accepted.clear();}
        if (actor==null) return false;
        // Unknown transport identity must never suppress an authenticated hint.
        if (transportId==null || transportId.isEmpty() || transportId.length()>512) return true;
        Long previous=accepted.get(transportId);
        if (previous!=null && now>=previous && now-previous<WINDOW_MS) return false;
        // Fixed window: duplicates cannot extend the suppression indefinitely.
        accepted.remove(transportId);accepted.put(transportId,now);
        while(accepted.size()>CAPACITY)accepted.remove(accepted.keySet().iterator().next());
        return true;
    }
}
