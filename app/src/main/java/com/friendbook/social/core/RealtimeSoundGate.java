package com.friendbook.social.core;
import java.util.*;
/** Silence first server history, cache replays, duplicates and old events. One gate per screen session. */
public final class RealtimeSoundGate {
    private final long openedAt;
    private boolean ready;
    private final Set<String> seen=new LinkedHashSet<>();
    public RealtimeSoundGate(long now) { openedAt=now; }
    public boolean begin(boolean fromCache) { return ready && !fromCache; }
    public boolean observe(String id,long created,long now,boolean eligible) {
        // Once live, an unconfirmed local/cache event must wait for its server acknowledgement.
        if (!eligible && ready) return false;
        if (id==null || id.isEmpty()) return false;
        boolean fresh=seen.add(id);
        if(seen.size()>4096) seen.remove(seen.iterator().next());
        return eligible && fresh && created>=openedAt-5000L && created<=now+30_000L && now-created<=120_000L;
    }
    public void end(boolean fromCache) { if(!fromCache) ready=true; }
}
