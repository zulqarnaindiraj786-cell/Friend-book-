package com.friendbook.social.core;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.friendbook.social.BuildConfig;
import com.friendbook.social.models.Models;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.SetOptions;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Cloudflare Realtime Presence & Typing over WebSocket + D1.
 * - Presence: WebSocket (wss://rough-firefly-dd7b.zulqarnaindiraj786.workers.dev/v1/presence/socket)
 * - Typing: HTTP PUT/GET to /v1/presence/typing/{chatId}
 * - RTDB: Kept ONLY for activeCall (call signaling)
 */
public class Presence {
    /** Callback for presence updates: (online, lastSeenMs). */
    public interface PresenceCallback { void onPresence(boolean online, long lastSeen); }

    /**
     * How often we persist "I am online" to Firestore.
     *
     * This used to be 30s, which cost 120 Firestore writes per user per hour: 200 users for 8
     * hours was ~192,000 writes/day against a 20,000/day free quota, so the app went dark every
     * day purely from presence. Cloudflare presence (websocket + /v1/presence) is now the
     * real-time source, and the Firestore write only has to outlive an app kill, so 2 minutes
     * keeps a safe margin under the 5-minute ONLINE_TTL. 200 users x 30 writes/hour x 8h =
     * 48,000/day on its own, and far less once combined with the typing fix below.
     */
    public static final long HEARTBEAT_INTERVAL_MS = 2 * 60_000L; // 2 minutes (TTL is 5 min)
    public static final long ONLINE_TTL_MS = PresencePolicy.ONLINE_TTL_MS; // 5 minutes (300s)
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final PresenceWritePolicy FIRESTORE_WRITES = new PresenceWritePolicy();

    private static CloudPresenceSocket cloudSocket;
    private static String boundUid;
    private static boolean foregroundActive, activeStatusVisible;
    private static String installId;
    private static long generation, serverOffset;
    private static ValueEventListener offsetListener;
    private static DatabaseReference typingRef;
    private static ValueEventListener typingListener;

    // OkHttp for typing HTTP calls
    private static final OkHttpClient CF_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build();

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String CF_PRESENCE_BASE = "https://rough-firefly-dd7b.zulqarnaindiraj786.workers.dev/v1/presence";

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

    private static synchronized CloudPresenceSocket ensureCloudSocket() {
        if (cloudSocket == null) cloudSocket = new CloudPresenceSocket(Fb.auth());
        return cloudSocket;
    }

    /** Initialize presence: connect to Cloudflare WebSocket, start Firestore heartbeat. */
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
        activeStatusVisible = (c != null && Prefs.activeStatus(c));
        boundUid = uid;
        generation++;

        // Cloudflare WebSocket connection
        try { ensureCloudSocket().foreground(activeStatusVisible); } catch (Throwable ignored) { }

        // Firestore heartbeat (persisted, survives app kill)
        writeFirestore(uid, true);
        H.removeCallbacks(FIRESTORE_HEARTBEAT);
        H.postDelayed(FIRESTORE_HEARTBEAT, HEARTBEAT_INTERVAL_MS);

        // RTDB server time offset (for now() sync) - keep for call signaling
        if (Fb.rtdb() != null && offsetListener == null) {
            offsetListener = new ValueEventListener() {
                public void onDataChange(@NonNull DataSnapshot data) {
                    Long offset = data.getValue(Long.class); if (offset != null) serverOffset = offset;
                }
                public void onCancelled(@NonNull DatabaseError e) {}
            };
            Fb.rtdb().getReference(".info/serverTimeOffset").addValueEventListener(offsetListener);
        }
    }

    private static final Runnable FIRESTORE_HEARTBEAT = new Runnable() {
        @Override public void run() {
            String uid = boundUid != null ? boundUid : Fb.uid();
            if (uid == null || !foregroundActive || !uid.equals(Fb.uid())) return;
            Context c = com.friendbook.social.App.instance;
            if (c != null && !Prefs.activeStatus(c)) { goOffline(); return; }
            writeFirestore(uid, true);
            H.postDelayed(this, HEARTBEAT_INTERVAL_MS);
        }
    };

    /** Go offline: close Cloudflare WS, write offline to Firestore. */
    public static void goOffline() {
        foregroundActive = false;
        H.removeCallbacks(FIRESTORE_HEARTBEAT);
        if (cloudSocket != null) {
            try { cloudSocket.background(); } catch (Throwable ignored) { }
        }
        String uid = boundUid != null ? boundUid : Fb.uid();
        if (Fb.rtdb() != null && typingListener != null && typingRef != null) {
            try { typingRef.removeEventListener(typingListener); } catch (Throwable ignored) { }
            typingListener = null; typingRef = null;
        }
        if (uid != null) writeOfflineInternal(uid);
        boundUid = null;
    }

    /** Update active status visibility (privacy toggle). */
    public static void setActiveStatusVisible(boolean visible) {
        activeStatusVisible = visible;
        if (cloudSocket != null) {
            try { cloudSocket.activeStatus(visible); } catch (Throwable ignored) { }
        }
        String uid = Fb.uid();
        if (uid != null && foregroundActive) writeFirestore(uid, true);
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
            m.put("isOnline", active);
            m.put("isActive", active);
            m.put("showActiveStatus", allowed);
            m.put("lastSeenMs", now());
            m.put("lastSeen", FieldValue.serverTimestamp());
            // Presence goes to D1. The Firestore write was the one that reported the outcome, so
            // a user showed as offline while online - and the ticket that callers wait on never
            // completed.
            com.friendbook.social.core.DataRouter.batch()
                    .set("users", uid, m)
                    .commit((success, error) -> FIRESTORE_WRITES.complete(
                            ticket, success, android.os.SystemClock.elapsedRealtime()));
        } catch (Exception e) {
            if (attempt != 0) FIRESTORE_WRITES.complete(attempt, false, android.os.SystemClock.elapsedRealtime());
            Log.w("FriendBookPresence", "Presence write could not be queued");
        }
    }

    private static void writeOfflineInternal(String uid) {
        writeFirestore(uid, false);
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
        private ListenerRegistration fsL;
        private ValueEventListener rtdbL;
        private DatabaseReference ref;
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

    // ---------- PRESENCE WATCHING (Cloudflare HTTP + Firestore) ----------

    private static final java.util.concurrent.ExecutorService CF_IO = java.util.concurrent.Executors.newFixedThreadPool(2);
    private static final android.util.LruCache<String, Long> CF_PRESENCE_FETCH_AT = new android.util.LruCache<>(256);

    /**
     * Watch presence for a user: combines Firestore (server-known) + Cloudflare HTTP (real-time).
     * Returns Handle to detach.
     * @deprecated Use watchPresence(String, PresenceCallback) instead. Kept for backward compatibility.
     */
    @Deprecated
    public static Handle watch(String uid, PresenceCallback cb) {
        return watchPresence(uid, cb);
    }

    /**
     * Watch presence for a user: combines Firestore (server-known) + Cloudflare HTTP (real-time).
     * Returns Handle to detach.
     */
    public static Handle watchPresence(String uid, PresenceCallback cb) {
        Handle h = new Handle();
        final boolean[] fsSeen = {false};
        final boolean[] fsOnline = {false};
        final long[] fsLastSeen = {0L};

        Runnable emit = () -> {
            if (!h.closed) cb.onPresence(fsOnline[0], fsLastSeen[0]);
        };

        // Use RAM FastCache first so opening Chat/Profile does not re-read users/{uid} a second time.
        Models.User cachedUser = FastCache.getUser(uid);
        if (cachedUser != null) {
            fsSeen[0] = true;
            boolean allowed = cachedUser.showActiveStatus && !cachedUser.deactivated && !cachedUser.banned;
            fsLastSeen[0] = cachedUser.lastSeen;
            fsOnline[0] = allowed && PresencePolicy.isFreshOnline(cachedUser.isOnline, cachedUser.lastSeen, now());
            emit.run();
        } else {
            CloudflareStore.get("users", uid, doc -> {
                        if (h.closed) return;
                        if (doc == null) {
                            fsSeen[0] = false; fsOnline[0] = false; fsLastSeen[0] = 0L; emit.run(); return;
                        }
                        Models.User u = Models.User.fromMap(uid, doc);
                        if (u != null) FastCache.putUser(u);
                        fsSeen[0] = true;
                        Object show = doc.get("showActiveStatus");
                        boolean allowed = (!(show instanceof Boolean) || (Boolean) show)
                                && !Boolean.TRUE.equals(doc.get("deactivated"))
                                && !Boolean.TRUE.equals(doc.get("banned"));
                        fsOnline[0] = allowed && firestoreActive(com.friendbook.social.core.Doc.of(uid, doc));
                        fsLastSeen[0] = extractLastSeenMs(com.friendbook.social.core.Doc.of(uid, doc));
                        emit.run();
                    });
        }

        // Cloudflare HTTP presence polling (real-time WebSocket state). 60s, not 30s: the
        // chat list watches every peer, so a 30s tick was 40 requests a minute for presence
        // dots. A minute-old dot is still a dot; the WebSocket carries the live state.
        if (BuildConfig.CLOUDFLARE_PRESENCE_ENABLED) {
            h.ticker = () -> {
                if (!h.closed) {
                    fetchCloudflarePresence(uid, h, fsSeen, fsOnline, fsLastSeen, emit);
                    H.postDelayed(h.ticker, 60_000L); // poll every 60s
                }
            };
            H.post(h.ticker); // immediate first fetch
        } else {
            // Fallback: just use Firestore
            h.ticker = () -> { if (!h.closed) emit.run(); };
            H.postDelayed(h.ticker, 10_000L);
        }

        return h;
    }

    private static void fetchCloudflarePresence(String uid, Handle h, boolean[] fsSeen, boolean[] fsOnline,
                                                long[] fsLastSeen, Runnable emit) {
        if (!BuildConfig.CLOUDFLARE_PRESENCE_ENABLED || h.closed || uid == null) return;
        long nowMs = android.os.SystemClock.elapsedRealtime();
        Long lastFetch = CF_PRESENCE_FETCH_AT.get(uid);
        if (lastFetch != null && (nowMs - lastFetch) < 60_000L) return; // rate limit
        CF_PRESENCE_FETCH_AT.put(uid, nowMs);

        final String me = Fb.uid();
        if (me == null) return;
        AuthToken.with(token -> {
            if (h.closed) return;
            CF_IO.execute(() -> {
                try {
                    String route = uid.equals(me) ? "/me" : ("/contact/" + android.net.Uri.encode(uid));
                    JSONObject data = D1HttpTransport.send(new java.net.URL(CF_PRESENCE_BASE + route), "GET", token, null);
                    boolean cfOnline = data.optBoolean("online", false);
                    long cfSeen = data.optLong("lastSeen", 0L);
                    H.post(() -> {
                        if (h.closed) return;
                        if (cfOnline || cfSeen > 0L) {
                            fsSeen[0] = true;
                            fsOnline[0] = cfOnline;
                            if (cfSeen > 0L) fsLastSeen[0] = Math.max(fsLastSeen[0], cfSeen);
                            emit.run();
                        }
                    });
                } catch (Throwable ignored) { }
            });
        }, () -> { });
    }

    /** Same freshness rule, for a D1 row. */
    public static long extractLastSeenMs(@Nullable com.friendbook.social.core.Doc d) {
        if (d == null || !d.exists()) return 0L;
        long ms = Format.longOf(d.getData(), "lastSeenMs");
        long ts = Format.ts(d.get("lastSeen"));
        return ts > 0 ? ts : ms;
    }

    private static boolean activeOf(java.util.Map<String, Object> m) {
        if (m == null) return false;
        Object active = m.get("isActive"), legacy = m.get("isOnline");
        if (Boolean.FALSE.equals(active) || Boolean.FALSE.equals(legacy)) return false;
        return Boolean.TRUE.equals(active) || Boolean.TRUE.equals(legacy);
    }

    private static boolean firestoreActive(com.friendbook.social.core.Doc d) {
        return activeOf(d == null ? null : d.getData());
    }

    private static boolean firestoreActive(DocumentSnapshot data) {
        Boolean active = data.getBoolean("isActive"), legacy = data.getBoolean("isOnline");
        if (Boolean.FALSE.equals(active) || Boolean.FALSE.equals(legacy)) return false;
        return Boolean.TRUE.equals(active) || Boolean.TRUE.equals(legacy);
    }

    // ---------- TYPING (disabled to save Worker/D1 quota; zero network requests) ----------

    /** No-op: typing indicator polling was dropped to save quota, so writing typing state is pure waste. */
    public static void setTyping(String chatId, boolean typing) {
        // Intentionally empty: zero Worker invocations on keystrokes.
    }

    /** No-op handle: avoids reading chats/{chatId} a second time on every chat open. */
    public static Handle watchTyping(String chatId, String myUid, java.util.function.Consumer<Boolean> cb) {
        return new Handle();
    }

    // ---------- RTDB: ONLY for activeCall (call signaling) ----------

    /** Start listening for activeCall on RTDB (call signaling). */
    public static void startCallSignal(String chatId, String myUid, java.util.function.Consumer<Boolean> cb) {
        if (Fb.rtdb() == null) return;
        try {
            DatabaseReference ref = Fb.rtdb().getReference("chats").child(chatId).child("activeCall");
            ValueEventListener listener = new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot s) {
                    boolean hasCall = s.exists() && s.getValue() != null;
                    cb.accept(hasCall);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { }
            };
            ref.addValueEventListener(listener);
        } catch (Throwable ignored) { }
    }

    /** Set activeCall on RTDB (call signaling). */
    public static void setCallSignal(String chatId, boolean active, Map<String, Object> callData) {
        if (Fb.rtdb() == null) return;
        try {
            DatabaseReference ref = Fb.rtdb().getReference("chats").child(chatId).child("activeCall");
            if (active) ref.setValue(callData);
            else ref.removeValue();
        } catch (Throwable ignored) { }
    }
}