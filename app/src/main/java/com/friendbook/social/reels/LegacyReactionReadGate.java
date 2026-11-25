package com.friendbook.social.reels;

import java.util.LinkedHashMap;

/** Coalesces legacy Firebase fallback reads used by Reel cards. */
final class LegacyReactionReadGate {
    static final long EMPTY_RETRY_MS = 15_000L;
    static final long FLIGHT_TIMEOUT_MS = 20_000L;
    private static final int MAX_ENTRIES = 256;

    private static final class Key {
        final String actor;
        final String document;
        Key(String actor, String document) { this.actor=actor; this.document=document; }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Key)) return false;
            Key key=(Key)other;
            return actor.equals(key.actor) && document.equals(key.document);
        }
        @Override public int hashCode() { return 31*actor.hashCode()+document.hashCode(); }
    }

    private static final class Flight {
        final long startedAt;
        Flight(long startedAt) { this.startedAt=startedAt; }
    }

    private static final LinkedHashMap<Key,Flight> IN_FLIGHT = new LinkedHashMap<>(32,0.75f,true);
    private static final LinkedHashMap<Key,Long> EMPTY_UNTIL = new LinkedHashMap<>(32,0.75f,true);

    private LegacyReactionReadGate() {}

    static synchronized Object begin(String actor,String document,long now) {
        if (actor==null || actor.isEmpty() || document==null || document.isEmpty()) return null;
        Key key=new Key(actor,document);
        Flight current=IN_FLIGHT.get(key);
        if (current!=null && now-current.startedAt<FLIGHT_TIMEOUT_MS) return null;
        IN_FLIGHT.remove(key);
        Long until=EMPTY_UNTIL.get(key);
        if (until!=null && now<until) return null;
        EMPTY_UNTIL.remove(key);
        Flight flight=new Flight(now);
        IN_FLIGHT.put(key,flight);
        trim(IN_FLIGHT);
        return flight;
    }

    static synchronized void success(String actor,String document,Object token,boolean empty,long now) {
        if (actor==null || document==null || token==null) return;
        Key key=new Key(actor,document);
        if (IN_FLIGHT.get(key)!=token) return;
        IN_FLIGHT.remove(key);
        if (empty) {
            EMPTY_UNTIL.put(key,now+EMPTY_RETRY_MS);
            trim(EMPTY_UNTIL);
        } else {
            EMPTY_UNTIL.remove(key);
        }
    }

    static synchronized void failure(String actor,String document,Object token) {
        if (actor==null || document==null || token==null) return;
        Key key=new Key(actor,document);
        if (IN_FLIGHT.get(key)==token) IN_FLIGHT.remove(key);
    }

    private static <K,V> void trim(LinkedHashMap<K,V> map) {
        while (map.size()>MAX_ENTRIES) {
            K eldest=map.keySet().iterator().next();
            map.remove(eldest);
        }
    }
}
