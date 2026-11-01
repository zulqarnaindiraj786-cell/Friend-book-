package com.friendbook.social.core;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.SetOptions;

import java.util.HashMap;
import java.util.Map;

/**
 * Real-time Online / Active Status + typing indicators backed by BOTH Cloud Firestore
 * (25s foreground heartbeat + 95s staleness TTL) and Firebase Realtime Database (onDisconnect).
 */
public class Presence {

    public interface Callback { void onPresence(boolean online, long lastSeen); }

    public static final long HEARTBEAT_INTERVAL_MS = 25_000L;
    public static final long ONLINE_TTL_MS = 95_000L;

    private static final Handler H = new Handler(Looper.getMainLooper());
    private static DatabaseReference statusRef;
    private static ValueEventListener connListener;
    private static String boundUid;
    private static volatile boolean foregroundActive = false;

    private static final Runnable HEARTBEAT = new Runnable() {
        @Override
        public void run() {
            if (!foregroundActive) return;
            String uid = Fb.uid();
            if (uid == null) return;
            Context ctx = com.friendbook.social.App.instance;
            if (ctx != null && !Prefs.activeStatus(ctx)) return;
            writeFirestore(uid, true);
            writeRtdbOnline(uid);
            H.postDelayed(this, HEARTBEAT_INTERVAL_MS);
        }
    };

    /** Called on app launch, foreground resume, login, or toggling Active Status ON. */
    public static void init() {
        foregroundActive = true;
        String uid = Fb.uid();
        if (uid == null) return;

        Context ctx = com.friendbook.social.App.instance;
        boolean allowActive = (ctx == null) || Prefs.activeStatus(ctx);
        if (!allowActive) {
            writeOfflineInternal(uid);
            return;
        }

        // 1. Immediately publish online + fresh heartbeat timestamp to Firestore (never wait on RTDB!)
        writeFirestore(uid, true);

        // 2. Start 25s foreground heartbeat so peers know our session is genuinely alive
        H.removeCallbacks(HEARTBEAT);
        H.postDelayed(HEARTBEAT, HEARTBEAT_INTERVAL_MS);

        // 3. Also attach RTDB socket-level presence + onDisconnect where available
        if (Fb.rtdb() == null) return;
        try {
            statusRef = Fb.rtdb().getReference("status").child(uid);
            writeRtdbOnline(uid);
            if (uid.equals(boundUid) && connListener != null) return;
            DatabaseReference connected = Fb.rtdb().getReference(".info/connected");
            if (connListener != null) {
                try { connected.removeEventListener(connListener); } catch (Throwable ignored) { }
            }
            boundUid = uid;
            connListener = new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    Boolean ok = snap.getValue(Boolean.class);
                    if (ok == null || !ok || Fb.uid() == null || !foregroundActive) return;
                    Context c = com.friendbook.social.App.instance;
                    if (c != null && !Prefs.activeStatus(c)) return;
                    Map<String, Object> online = new HashMap<>();
                    online.put("state", "online");
                    online.put("lastChanged", ServerValue.TIMESTAMP);
                    Map<String, Object> offline = new HashMap<>();
                    offline.put("state", "offline");
                    offline.put("lastChanged", ServerValue.TIMESTAMP);
                    if (statusRef != null) {
                        statusRef.onDisconnect().setValue(offline);
                        statusRef.setValue(online);
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError error) { }
            };
            connected.addValueEventListener(connListener);
        } catch (Throwable ignored) { }
    }

    private static void writeRtdbOnline(@NonNull String uid) {
        if (Fb.rtdb() == null) return;
        try {
            Map<String, Object> online = new HashMap<>();
            online.put("state", "online");
            online.put("lastChanged", ServerValue.TIMESTAMP);
            Fb.rtdb().getReference("status").child(uid).setValue(online);
        } catch (Throwable ignored) { }
    }

    private static void writeFirestore(@NonNull String uid, boolean online) {
        try {
            Context ctx = com.friendbook.social.App.instance;
            boolean allowed = (ctx == null) || Prefs.activeStatus(ctx);
            long now = System.currentTimeMillis();
            Map<String, Object> m = new HashMap<>();
            m.put("isOnline", online && allowed);
            m.put("showActiveStatus", allowed);
            m.put("lastSeenMs", now);
            m.put("lastSeen", FieldValue.serverTimestamp());
            Fb.db().collection("users").document(uid).set(m, SetOptions.merge());
        } catch (Throwable ignored) { }
    }

    /** Called when app moves to background, user logs out, switches accounts, or turns off Active Status. */
    public static void goOffline() {
        foregroundActive = false;
        H.removeCallbacks(HEARTBEAT);
        String uid = Fb.uid();
        if (uid == null) uid = boundUid;
        if (Fb.rtdb() != null && connListener != null) {
            try { Fb.rtdb().getReference(".info/connected").removeEventListener(connListener); }
            catch (Throwable ignored) { }
        }
        connListener = null;
        boundUid = null;
        if (uid != null) {
            writeOfflineInternal(uid);
        }
    }

    private static void writeOfflineInternal(@NonNull String uid) {
        writeFirestore(uid, false);
        if (Fb.rtdb() != null) {
            try {
                Map<String, Object> offline = new HashMap<>();
                offline.put("state", "offline");
                offline.put("lastChanged", ServerValue.TIMESTAMP);
                Fb.rtdb().getReference("status").child(uid).setValue(offline);
            } catch (Throwable ignored) { }
        }
    }

    /** Extracts freshest epoch-ms timestamp from user doc (`lastSeenMs` or `lastSeen`). */
    public static long extractLastSeenMs(@Nullable DocumentSnapshot d) {
        if (d == null || !d.exists()) return 0L;
        long ms = 0L;
        Object rawMs = d.get("lastSeenMs");
        if (rawMs instanceof Number) ms = ((Number) rawMs).longValue();
        long ts = Format.ts(d.get("lastSeen"));
        return Math.max(ms, ts);
    }

    /** Evaluates whether a Firestore user doc is genuinely online right now (respects privacy + 95s TTL). */
    public static boolean isUserOnlineDoc(@Nullable DocumentSnapshot d) {
        if (d == null || !d.exists()) return false;
        Boolean show = d.getBoolean("showActiveStatus");
        if (show != null && !show) return false;
        if (!Boolean.TRUE.equals(d.getBoolean("isOnline"))) return false;
        long ls = extractLastSeenMs(d);
        if (ls <= 0L) return false;
        return (System.currentTimeMillis() - ls) <= ONLINE_TTL_MS;
    }

    /** Formats clean Messenger-style status ("Active now", "Active 5m ago", "Active 1h ago"). */
    @NonNull
    public static String formatActiveStatus(boolean online, long lastSeenMs) {
        if (online) return "Active now";
        if (lastSeenMs <= 0L) return "";
        long diff = Math.max(0L, System.currentTimeMillis() - lastSeenMs);
        if (diff < 60_000L) return "Active just now";
        long mins = diff / 60_000L;
        if (mins < 60L) return "Active " + mins + "m ago";
        long hrs = mins / 60L;
        if (hrs < 24L) return "Active " + hrs + "h ago";
        long days = hrs / 24L;
        if (days < 7L) return "Active " + days + "d ago";
        return "Active " + (days / 7L) + "w ago";
    }

    /** Handle to a live presence or typing watcher; close() to detach. */
    public static class Handle {
        private ValueEventListener rtdbL;
        private DatabaseReference ref;
        private ListenerRegistration fsL;
        private Runnable ticker;
        private boolean closed = false;

        public void close() {
            closed = true;
            if (ticker != null) H.removeCallbacks(ticker);
            if (ref != null && rtdbL != null) {
                try { ref.removeEventListener(rtdbL); } catch (Throwable ignored) { }
            }
            if (fsL != null) {
                try { fsL.remove(); } catch (Throwable ignored) { }
            }
        }
    }

    /** Watch a user's presence by merging Firestore + RTDB with live 30s staleness re-evaluation. */
    public static Handle watch(String uid, Callback cb) {
        Handle h = new Handle();
        if (uid == null || uid.isEmpty() || cb == null) return h;

        final boolean[] showActive = {true};
        final boolean[] fsSeen = {false};
        final boolean[] fsOnline = {false};
        final long[] fsLastSeen = {0L};

        final boolean[] rtdbSeen = {false};
        final boolean[] rtdbOnline = {false};
        final long[] rtdbLastChanged = {0L};

        final Runnable emit = () -> {
            if (h.closed) return;
            if (!showActive[0]) {
                cb.onPresence(false, 0L);
                return;
            }
            long now = System.currentTimeMillis();
            long bestLastSeen = Math.max(fsLastSeen[0], rtdbLastChanged[0]);

            boolean fsFreshOnline = fsOnline[0]
                    && (fsLastSeen[0] > 0L ? (now - fsLastSeen[0]) <= ONLINE_TTL_MS : !rtdbSeen[0]);
            boolean rtdbFreshOnline = rtdbSeen[0] && rtdbOnline[0]
                    && !(fsSeen[0] && !fsOnline[0] && fsLastSeen[0] > rtdbLastChanged[0] + 2_000L);

            boolean mergedOnline;
            if (fsFreshOnline) {
                if (rtdbSeen[0] && !rtdbOnline[0] && rtdbLastChanged[0] > fsLastSeen[0] + 2_000L) {
                    mergedOnline = false;
                } else {
                    mergedOnline = true;
                }
            } else {
                mergedOnline = rtdbFreshOnline;
            }
            cb.onPresence(mergedOnline, bestLastSeen);
        };

        if (Fb.rtdb() != null) {
            try {
                h.ref = Fb.rtdb().getReference("status").child(uid);
                h.rtdbL = new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot s) {
                        if (h.closed || !s.exists()) return;
                        rtdbSeen[0] = true;
                        rtdbOnline[0] = "online".equals(s.child("state").getValue(String.class));
                        Long lc = s.child("lastChanged").getValue(Long.class);
                        if (lc != null) rtdbLastChanged[0] = lc;
                        emit.run();
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) { }
                };
                h.ref.addValueEventListener(h.rtdbL);
            } catch (Throwable ignored) { }
        }

        h.fsL = Fb.db().collection("users").document(uid)
                .addSnapshotListener((d, err) -> {
                    if (h.closed || d == null || !d.exists()) return;
                    fsSeen[0] = true;
                    Boolean show = d.getBoolean("showActiveStatus");
                    showActive[0] = (show == null || show);
                    fsOnline[0] = Boolean.TRUE.equals(d.getBoolean("isOnline"));
                    fsLastSeen[0] = extractLastSeenMs(d);
                    emit.run();
                });

        h.ticker = new Runnable() {
            @Override public void run() {
                if (h.closed) return;
                emit.run();
                H.postDelayed(this, 30_000L);
            }
        };
        H.postDelayed(h.ticker, 30_000L);
        return h;
    }

    // ---- typing ----
    private static final Map<String, Runnable> typingClear = new HashMap<>();

    public static void setTyping(String chatId, boolean typing) {
        String uid = Fb.uid();
        if (uid == null || chatId == null) return;
        Fb.db().collection("chats").document(chatId).update("typing." + uid, typing);

        if (Fb.rtdb() != null) {
            try {
                DatabaseReference ref = Fb.rtdb().getReference("typing").child(chatId).child(uid);
                ref.setValue(typing);
                Runnable old = typingClear.remove(chatId);
                if (old != null) H.removeCallbacks(old);
                if (typing) {
                    Runnable clear = () -> {
                        try { ref.setValue(false); } catch (Throwable ignored) { }
                        Fb.db().collection("chats").document(chatId).update("typing." + uid, false);
                    };
                    typingClear.put(chatId, clear);
                    H.postDelayed(clear, 4000);
                }
            } catch (Throwable ignored) { }
        }
    }

    /** Watch whether any OTHER participant in a chat is typing. */
    public static Handle watchTyping(String chatId, String myUid, java.util.function.Consumer<Boolean> cb) {
        Handle h = new Handle();
        final boolean[] fsTyping = {false};
        final boolean[] rtdbTyping = {false};
        Runnable emit = () -> {
            if (!h.closed) cb.accept(fsTyping[0] || rtdbTyping[0]);
        };

        h.fsL = Fb.db().collection("chats").document(chatId)
                .addSnapshotListener((d, err) -> {
                    fsTyping[0] = false;
                    if (d != null && d.exists()) {
                        Object t = d.get("typing");
                        if (t instanceof Map) {
                            for (Map.Entry<?, ?> e : ((Map<?, ?>) t).entrySet()) {
                                if (!String.valueOf(e.getKey()).equals(myUid)
                                        && Boolean.TRUE.equals(e.getValue())) {
                                    fsTyping[0] = true;
                                    break;
                                }
                            }
                        }
                    }
                    emit.run();
                });
        if (Fb.rtdb() != null) {
            try {
                h.ref = Fb.rtdb().getReference("typing").child(chatId);
                h.rtdbL = new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot s) {
                        rtdbTyping[0] = false;
                        for (DataSnapshot c : s.getChildren()) {
                            if (!String.valueOf(myUid).equals(c.getKey())
                                    && Boolean.TRUE.equals(c.getValue(Boolean.class))) {
                                rtdbTyping[0] = true;
                                break;
                            }
                        }
                        emit.run();
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) { }
                };
                h.ref.addValueEventListener(h.rtdbL);
            } catch (Throwable ignored) { }
        }
        return h;
    }
}
