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

/** RTDB onDisconnect presence, per-install connections, bounded freshness and privacy-aware readers. */
public class Presence {
    public interface Callback { void onPresence(boolean online, long lastSeen); }
    public static final long HEARTBEAT_INTERVAL_MS = 25_000L;
    public static final long ONLINE_TTL_MS = PresencePolicy.ONLINE_TTL_MS;
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final PresenceWritePolicy FIRESTORE_WRITES = new PresenceWritePolicy();
    private static CloudPresenceSocket cloudSocket;
    private static DatabaseReference statusRef;
    private static ValueEventListener connListener;
    private static String boundUid;
    private static boolean foregroundActive, disconnectArmed;
    private static String installId;
    private static long generation, serverOffset;
    private static ValueEventListener offsetListener;
    public static long now() { return System.currentTimeMillis() + serverOffset; }
    private static String installId() {
        if (installId == null) {
            Context c = com.friendbook.social.App.instance;
            if (c == null) return "session_" + java.util.UUID.randomUUID();
            android.content.SharedPreferences p = c.getSharedPreferences("presence_install", 0);
            installId = p.getString("id", "");
            if (installId.isEmpty()) { installId = java.util.UUID.randomUUID().toString(); p.edit().putString("id", installId).apply(); }
        }
        return installId;
    }
    private static Map<String, Object> status(boolean online) {
        Map<String, Object> m = new HashMap<>();
        m.put("state", online ? "online" : "offline"); m.put("isActive", online);
        m.put("lastSeen", ServerValue.TIMESTAMP); m.put("lastChanged", ServerValue.TIMESTAMP);
        String key = "connections/" + installId() + "/";
        m.put(key + "state", online ? "online" : "offline"); m.put(key + "isActive", online);
        m.put(key + "lastSeen", ServerValue.TIMESTAMP); m.put(key + "lastChanged", ServerValue.TIMESTAMP);
        return m;
    }
    private static final Runnable HEARTBEAT = new Runnable() {
        @Override public void run() {
            if (!foregroundActive || boundUid == null || !boundUid.equals(Fb.uid())) return;
            Context c = com.friendbook.social.App.instance;
            if (c != null && !Prefs.activeStatus(c)) { goOffline(); return; }
            writeFirestore(boundUid, true);
            // Never queue an online write before the server acknowledges onDisconnect registration.
            if (disconnectArmed && statusRef != null) statusRef.updateChildren(status(true));
            H.postDelayed(this, HEARTBEAT_INTERVAL_MS);
        }
    };
    private static synchronized CloudPresenceSocket ensureCloudSocket() {
        if (cloudSocket == null) cloudSocket = new CloudPresenceSocket(Fb.auth());
        return cloudSocket;
    }
    public static void init() {
        String uid = Fb.uid();
        if (uid == null) { goOffline(); return; }
        Context c = com.friendbook.social.App.instance;
        if (c != null && !Prefs.activeStatus(c)) {
            if (cloudSocket != null) cloudSocket.activeStatus(false);
            goOffline(); writeOfflineInternal(uid); return;
        }
        if (boundUid != null && !uid.equals(boundUid)) goOffline();
        foregroundActive = true;
        try { ensureCloudSocket().foreground(true); } catch (Throwable ignored) { }
        writeFirestore(uid, true);
        H.removeCallbacks(HEARTBEAT); H.postDelayed(HEARTBEAT, HEARTBEAT_INTERVAL_MS);
        if (uid.equals(boundUid) && connListener != null) return;
        boundUid = uid;
        if (Fb.rtdb() == null) return;
        if (offsetListener == null) {
            offsetListener = new ValueEventListener() {
                public void onDataChange(@NonNull DataSnapshot data) {
                    Long offset = data.getValue(Long.class); if (offset != null) serverOffset = offset;
                }
                public void onCancelled(@NonNull DatabaseError e) {}
            };
            Fb.rtdb().getReference(".info/serverTimeOffset").addValueEventListener(offsetListener);
        }
        final DatabaseReference target = Fb.rtdb().getReference("status").child(uid);
        statusRef = target;
        final long session = ++generation;
        connListener = new ValueEventListener() {
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (session != generation) return;
                disconnectArmed = false;
                if (!Boolean.TRUE.equals(snapshot.getValue(Boolean.class)) || !foregroundActive || !uid.equals(Fb.uid())) return;
                target.onDisconnect().updateChildren(status(false), (error, ref) -> {
                    if (error != null || session != generation || !foregroundActive || !uid.equals(Fb.uid())) return;
                    disconnectArmed = true;
                    target.updateChildren(status(true));
                });
            }
            public void onCancelled(@NonNull DatabaseError error) { disconnectArmed = false; }
        };
        Fb.rtdb().getReference(".info/connected").addValueEventListener(connListener);
    }
    private static void writeFirestore(@NonNull String uid, boolean online) {
        long attempt = 0;
        try {
            Context ctx = com.friendbook.social.App.instance;
            boolean allowed = ctx == null || Prefs.activeStatus(ctx);
            final boolean active = online && allowed;
            final long ticket = FIRESTORE_WRITES.begin(uid, active, android.os.SystemClock.elapsedRealtime());
            if (ticket == 0) return;
            attempt = ticket;
            Map<String, Object> m = new HashMap<>();
            m.put("isOnline", active); m.put("isActive", active);
            m.put("showActiveStatus", allowed);
            m.put("lastSeenMs", now()); m.put("lastSeen", FieldValue.serverTimestamp());
            Fb.db().collection("users").document(uid).set(m, SetOptions.merge())
                    .addOnSuccessListener(unused -> FIRESTORE_WRITES.complete(ticket, true, android.os.SystemClock.elapsedRealtime()))
                    .addOnFailureListener(error -> FIRESTORE_WRITES.complete(ticket, false, android.os.SystemClock.elapsedRealtime()));
        } catch (Exception e) {
            if (attempt != 0) FIRESTORE_WRITES.complete(attempt, false, android.os.SystemClock.elapsedRealtime());
            android.util.Log.w("FriendBookPresence", "Presence write could not be queued");
        }
    }
    public static void goOffline() {
        foregroundActive = false; disconnectArmed = false; generation++;
        H.removeCallbacks(HEARTBEAT);
        if (cloudSocket != null) {
            try { cloudSocket.background(); } catch (Throwable ignored) { }
        }
        String uid = boundUid != null ? boundUid : Fb.uid();
        if (Fb.rtdb() != null && connListener != null)
            Fb.rtdb().getReference(".info/connected").removeEventListener(connListener);
        connListener = null; statusRef = null; boundUid = null;
        if (uid != null) writeOfflineInternal(uid);
        // Keep the armed onDisconnect operation: it is a safety net if this offline write cannot reach the server.
    }
    private static void writeOfflineInternal(String uid) {
        writeFirestore(uid, false);
        if (Fb.rtdb() != null) Fb.rtdb().getReference("status").child(uid).updateChildren(status(false));
    }

    /** Extracts freshest epoch-ms timestamp from user doc (`lastSeenMs` or `lastSeen`). */
    public static long extractLastSeenMs(@Nullable DocumentSnapshot d) {
        if (d == null || !d.exists()) return 0L;
        long ms = 0L;
        Object rawMs = d.get("lastSeenMs");
        if (rawMs instanceof Number) ms = ((Number) rawMs).longValue();
        long ts = Format.ts(d.get("lastSeen"));
        return ts > 0 ? ts : ms;
    }

    /** Evaluates whether a Firestore user doc is genuinely online right now (respects privacy + five-minute freshness). */
    public static boolean isUserOnlineDoc(@Nullable DocumentSnapshot d) {
        if (d == null || !d.exists()) return false;
        Boolean show = d.getBoolean("showActiveStatus");
        if (show != null && !show) return false;
        boolean active = firestoreActive(d);
        return PresencePolicy.isFreshOnline(active, extractLastSeenMs(d), now());
    }

    @NonNull public static String formatActiveStatus(boolean online, long lastSeenMs) {
        return online ? "Active now" : TimeAgoUtil.formatLastSeen(lastSeenMs, now());
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

    /** Watch RTDB-authoritative presence, Firestore privacy, and deadline-based freshness expiry. */
    public static Handle watch(String uid, Callback cb) {
        Handle h = new Handle();
        if (uid == null || uid.isEmpty() || cb == null) return h;

        final boolean[] showActive = {false};
        final boolean[] fsSeen = {false};
        final boolean[] fsOnline = {false};
        final long[] fsLastSeen = {0L};

        final boolean[] rtdbSeen = {false};
        final boolean[] rtdbOnline = {false};
        final long[] rtdbLastChanged = {0L};
        final long[] rtdbBestSeen = {0L};

        final Runnable emit = () -> {
            if (h.closed) return;
            long clock = now();
            boolean online = PresencePolicy.resolve(showActive[0], rtdbSeen[0], rtdbOnline[0], rtdbLastChanged[0],
                    fsSeen[0], fsOnline[0], fsLastSeen[0], clock);
            long bestLastSeen = rtdbSeen[0] && rtdbBestSeen[0] > 0 ? rtdbBestSeen[0] : fsLastSeen[0];
            cb.onPresence(online, showActive[0] ? bestLastSeen : 0L);
            if (h.ticker != null) {
                H.removeCallbacks(h.ticker);
                long activeTimestamp = rtdbSeen[0] ? rtdbLastChanged[0] : fsLastSeen[0];
                long remaining = ONLINE_TTL_MS - (clock - activeTimestamp);
                H.postDelayed(h.ticker, online ? Math.max(1L, Math.min(30_000L, remaining)) : 30_000L);
            }
        };

        if (Fb.rtdb() != null) {
            try {
                h.ref = Fb.rtdb().getReference("status").child(uid);
                h.rtdbL = new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot s) {
                        if (h.closed) return;
                        rtdbSeen[0] = true;
                        long rootTime = timestamp(s);
                        long bestTime = rootTime, activeTime = 0, connectionTime = 0;
                        boolean hasConnections = s.child("connections").exists();
                        for (DataSnapshot connection : s.child("connections").getChildren()) {
                            long time = timestamp(connection);
                            bestTime = Math.max(bestTime, time); connectionTime = Math.max(connectionTime, time);
                            if (PresencePolicy.isFreshOnline(active(connection), time, now())) activeTime = Math.max(activeTime, time);
                        }
                        // Each installation disconnects only itself; another fresh foreground installation stays online.
                        boolean legacyRoot = !hasConnections || rootTime > connectionTime + 2_000L;
                        rtdbOnline[0] = activeTime > 0 || (legacyRoot && PresencePolicy.isFreshOnline(active(s), rootTime, now()));
                        rtdbBestSeen[0] = bestTime;
                        rtdbLastChanged[0] = rtdbOnline[0] ? Math.max(activeTime, legacyRoot ? rootTime : 0) : bestTime;
                        emit.run();
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        rtdbSeen[0] = true; rtdbOnline[0] = false; emit.run();
                    }
                };
                h.ref.addValueEventListener(h.rtdbL);
            } catch (Throwable ignored) { }
        }

        h.fsL = Fb.db().collection("users").document(uid)
                .addSnapshotListener(com.google.firebase.firestore.MetadataChanges.INCLUDE, (d, err) -> {
                    if (h.closed) return;
                    if (d == null || !d.exists()) { showActive[0] = false; fsSeen[0] = false; fsOnline[0] = false; emit.run(); return; }
                    fsSeen[0] = !d.getMetadata().isFromCache();
                    Boolean show = d.getBoolean("showActiveStatus");
                    showActive[0] = (show == null || show) && !Boolean.TRUE.equals(d.getBoolean("deactivated"))
                            && !Boolean.TRUE.equals(d.getBoolean("banned"));
                    fsOnline[0] = firestoreActive(d);
                    fsLastSeen[0] = extractLastSeenMs(d);
                    emit.run();
                });

        h.ticker = () -> {
            if (h.closed) return;
            fetchCloudflarePresence(uid, h, rtdbSeen, rtdbOnline, rtdbLastChanged, rtdbBestSeen, emit);
            emit.run();
        };
        fetchCloudflarePresence(uid, h, rtdbSeen, rtdbOnline, rtdbLastChanged, rtdbBestSeen, emit);
        emit.run();
        return h;
    }

    private static final String CF_PRESENCE_BASE = "https://rough-firefly-dd7b.zulqarnaindiraj786.workers.dev/v1/presence";
    private static final java.util.concurrent.ExecutorService CF_IO = java.util.concurrent.Executors.newCachedThreadPool();

    private static void fetchCloudflarePresence(String uid, Handle h, boolean[] rtdbSeen, boolean[] rtdbOnline,
                                                long[] rtdbLastChanged, long[] rtdbBestSeen, Runnable emit) {
        if (!com.friendbook.social.BuildConfig.CLOUDFLARE_PRESENCE_ENABLED || h.closed) return;
        try {
            com.google.firebase.auth.FirebaseUser user = Fb.auth().getCurrentUser();
            if (user == null) return;
            user.getIdToken(false).addOnSuccessListener(res -> {
                String token = res != null ? res.getToken() : null;
                if (token == null || token.isEmpty() || h.closed) return;
                CF_IO.execute(() -> {
                    try {
                        String route = uid.equals(user.getUid()) ? "/me" : ("/contact/" + android.net.Uri.encode(uid));
                        org.json.JSONObject data = D1HttpTransport.send(new java.net.URL(CF_PRESENCE_BASE + route), "GET", token, null);
                        boolean cfOnline = data.optBoolean("online", false);
                        long cfSeen = data.optLong("lastSeen", 0L);
                        H.post(() -> {
                            if (h.closed) return;
                            if (cfOnline || cfSeen > 0L) {
                                rtdbSeen[0] = true;
                                rtdbOnline[0] = cfOnline;
                                if (cfSeen > 0L) {
                                    rtdbBestSeen[0] = Math.max(rtdbBestSeen[0], cfSeen);
                                    rtdbLastChanged[0] = cfOnline ? cfSeen : rtdbBestSeen[0];
                                }
                                emit.run();
                            }
                        });
                    } catch (Throwable ignored) { }
                });
            });
        } catch (Throwable ignored) { }
    }

    private static boolean firestoreActive(DocumentSnapshot data) {
        Boolean active = data.getBoolean("isActive"), legacy = data.getBoolean("isOnline");
        if (Boolean.FALSE.equals(active) || Boolean.FALSE.equals(legacy)) return false;
        return Boolean.TRUE.equals(active) || Boolean.TRUE.equals(legacy);
    }
    private static long timestamp(DataSnapshot data) {
        Object seen = data.child("lastSeen").getValue(), changed = data.child("lastChanged").getValue();
        return Math.max(seen instanceof Number ? ((Number) seen).longValue() : 0,
                changed instanceof Number ? ((Number) changed).longValue() : 0);
    }
    private static boolean active(DataSnapshot data) {
        // isActive is authoritative if present; state supports installed legacy clients.
        Object flag = data.child("isActive").getValue();
        return flag instanceof Boolean ? Boolean.TRUE.equals(flag) : "online".equals(data.child("state").getValue(String.class));
    }

    // ---- typing ----
    private static final Map<String, Runnable> typingClear = new HashMap<>();

    public static void setTyping(String chatId, boolean typing) {
        String uid = Fb.uid();
        if (uid == null || chatId == null) return;
        sendCloudflareTyping(chatId, typing);
        Fb.db().collection("chats").document(chatId).update("typing." + uid, typing);

        if (Fb.rtdb() != null) {
            try {
                DatabaseReference ref = Fb.rtdb().getReference("typing").child(chatId).child(uid);
                ref.setValue(typing);
                Runnable old = typingClear.remove(chatId);
                if (old != null) H.removeCallbacks(old);
                if (typing) {
                    Runnable clear = () -> {
                        sendCloudflareTyping(chatId, false);
                        try { ref.setValue(false); } catch (Throwable ignored) { }
                        Fb.db().collection("chats").document(chatId).update("typing." + uid, false);
                    };
                    typingClear.put(chatId, clear);
                    H.postDelayed(clear, 4000);
                }
            } catch (Throwable ignored) { }
        }
    }

    private static void sendCloudflareTyping(String chatId, boolean typing) {
        if (!com.friendbook.social.BuildConfig.CLOUDFLARE_PRESENCE_ENABLED || chatId == null || chatId.isEmpty()) return;
        try {
            com.google.firebase.auth.FirebaseUser user = Fb.auth().getCurrentUser();
            if (user == null) return;
            user.getIdToken(false).addOnSuccessListener(res -> {
                String token = res != null ? res.getToken() : null;
                if (token == null || token.isEmpty()) return;
                CF_IO.execute(() -> {
                    try {
                        D1HttpTransport.send(
                                new java.net.URL(CF_PRESENCE_BASE + "/typing/" + android.net.Uri.encode(chatId)),
                                "PUT", token, D1Client.object("typing", typing));
                    } catch (Throwable ignored) { }
                });
            });
        } catch (Throwable ignored) { }
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
        if (com.friendbook.social.BuildConfig.CLOUDFLARE_PRESENCE_ENABLED && chatId != null && !chatId.isEmpty()) {
            h.ticker = new Runnable() {
                @Override public void run() {
                    if (h.closed) return;
                    pollCloudflareTyping(chatId, h, rtdbTyping, emit);
                    H.postDelayed(this, 3000L);
                }
            };
            h.ticker.run();
        }
        return h;
    }

    private static void pollCloudflareTyping(String chatId, Handle h, boolean[] rtdbTyping, Runnable emit) {
        try {
            com.google.firebase.auth.FirebaseUser user = Fb.auth().getCurrentUser();
            if (user == null || h.closed) return;
            user.getIdToken(false).addOnSuccessListener(res -> {
                String token = res != null ? res.getToken() : null;
                if (token == null || token.isEmpty() || h.closed) return;
                CF_IO.execute(() -> {
                    try {
                        org.json.JSONObject data = D1HttpTransport.send(
                                new java.net.URL(CF_PRESENCE_BASE + "/typing/" + android.net.Uri.encode(chatId)),
                                "GET", token, null);
                        boolean cfTyping = data.optBoolean("typing", false);
                        H.post(() -> {
                            if (h.closed) return;
                            if (cfTyping) {
                                rtdbTyping[0] = true;
                                emit.run();
                            }
                        });
                    } catch (Throwable ignored) { }
                });
            });
        } catch (Throwable ignored) { }
    }
}
