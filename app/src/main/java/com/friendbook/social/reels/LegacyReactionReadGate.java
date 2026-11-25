package com.friendbook.social.reels;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Coalesces the legacy Firebase fallback read used by Reel cards.
 * Empty results are briefly suppressed because legacy count metadata can
 * outlive its reaction subcollection; failures remain immediately retryable.
 */
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

    private static final LinkedHashMap<Key,Long> IN_FLIGHT = new LinkedHashMap<>(32,0.75f,true);
    private static final LinkedHashMap<Key,Long> EMPTY_UNTIL = new LinkedHashMap<>(32,0.75f,true);

    private LegacyReactionReadGate() {}

    static synchronized boolean begin(String actor,String document,long now) {
        if (actor==null || actor.isEmpty() || document==null || document.isEmpty()) return false;
        Key key=new Key(actor,document);
        Long started=IN_FLIGHT.get(key);
        if (started!=null && now-started<FLIGHT_TIMEOUT_MS) return false;
        IN_FLIGHT.remove(key);
        Long until=EMPTY_UNTIL.get(key);
        if (until!=null && now<until) return false;
        EMPTY_UNTIL.remove(key);
        IN_FLIGHT.put(key,now);
        trim(IN_FLIGHT);
        return true;
    }

    static synchronized void success(String actor,String document,boolean empty,long now) {
        Key key=new Key(actor,document);
        IN_FLIGHT.remove(key);
        if (empty) {
            EMPTY_UNTIL.put(key,now+EMPTY_RETRY_MS);
            trim(EMPTY_UNTIL);
        } else {
            EMPTY_UNTIL.remove(key);
        }
    }

    static synchronized void failure(String actor,String document) {
        IN_FLIGHT.remove(new Key(actor,document));
    }

    private static <K,V> void trim(LinkedHashMap<K,V> map) {
        while (map.size()>MAX_ENTRIES) {
            K eldest=map.keySet().iterator().next();
            map.remove(eldest);
        }
    }
}
