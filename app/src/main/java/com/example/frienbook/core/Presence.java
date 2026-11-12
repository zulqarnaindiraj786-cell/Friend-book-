package com.example.frienbook.core;

import android.os.Handler;
import android.os.Looper;

import com.example.frienbook.core.cloud.SetOptions;

import org.json.JSONObject;

import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import com.example.frienbook.core.cloud.Database;

/**
 * Online/offline presence and typing indicators on Cloudflare - the Firebase Realtime Database is
 * not part of this product any more.
 *
 * The Worker keeps the state in D1 (`friendbook_cf_presence`, `friendbook_cf_typing`) so every
 * isolate and every colo answers the same fact, and it expires on its own: online goes stale after
 * 5 minutes without a heartbeat and typing after 8 seconds, which is what RTDB's `onDisconnect`
 * used to guarantee. A killed app can therefore never leave a user "online" forever.
 *
 * The app speaks plain HTTPS with its Firebase ID token (Firebase is authentication only):
 *   PUT /v1/presence/me            heartbeat {online, visible}
 *   GET /v1/presence/contact/{uid} a contact's online + lastSeen
 *   PUT /v1/presence/typing/{chat} {typing}
 *   GET /v1/presence/typing/{chat} is the PEER typing (the Worker never reports the caller)
 *
 * Watchers poll only while their screen is alive (10s presence, 3s typing) and always deliver on the
 * main thread. The `isOnline`/`lastSeen` copy on the user document is still written - profile cards
 * read the document, not the presence socket - and it is mirrored to D1 like every other field.
 */
public class Presence {

    public interface Callback { void onPresence(boolean online, long lastSeen); }

    private static final String BASE = CloudTransport.BASE + "/v1/presence";
    private static final long HEARTBEAT_MS = 60_000L;
    private static final long PRESENCE_POLL_MS = 10_000L;
    private static final long TYPING_POLL_MS = 3_000L;
    private static final long TYPING_CLEAR_MS = 4_000L;
    private static final long TYPING_THROTTLE_MS = 1_000L;

    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cf-presence");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private static boolean beating;
    private static final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (!beating) return;
            if (Fb.uid() == null) { beating = false; return; }
            beat(true);
            H.postDelayed(this, HEARTBEAT_MS);
        }
    };

    /** Starts (or keeps alive) the online heartbeat for the signed-in user. */
    public static void init() {
        if (Fb.uid() == null) return;
        beat(true);
        if (!beating) {
            beating = true;
            H.removeCallbacks(heartbeat);
            H.postDelayed(heartbeat, HEARTBEAT_MS);
        }
    }

    private static boolean visible() {
        try {
            return Prefs.activeStatus(com.example.frienbook.App.instance);
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static void beat(boolean online) {
        String uid = Fb.uid();
        if (uid == null) return;
        boolean show = online && visible();
        final JSONObject body = new JSONObject();
        try {
            body.put("online", show);
            body.put("visible", visible());
        } catch (Throwable ignored) { return; }
        CloudTransport.withToken(IO, H, token -> {
            try {
                CloudTransport.send(new URL(BASE + "/me"), "PUT", token, body);
            } catch (Throwable ignored) {
                // Best effort: the next heartbeat carries the same fact, and the Worker's TTL means a
                // missed beat can only ever show us offline - never online by mistake.
            }
        }, null);
        writeUserDocument(show);
    }

    /** Profile cards read the user document, so the same fact is kept there (and mirrored to D1). */
    private static void writeUserDocument(boolean online) {
        String uid = Fb.uid();
        if (uid == null) return;
        Map<String, Object> m = new HashMap<>();
        m.put("isOnline", online);
        if (!online) m.put("lastSeen", com.example.frienbook.core.cloud.FieldValue.serverTimestamp());
        try {
            Fb.db().collection("users").document(uid).set(m, SetOptions.merge());
        } catch (Throwable ignored) { }
        CloudflareDocs.mirror("users", uid, m);
    }

    public static void goOffline() {
        beating = false;
        H.removeCallbacks(heartbeat);
        writeUserDocument(false);
        if (Fb.uid() == null) return;
        final JSONObject body = new JSONObject();
        try {
            body.put("online", false);
            body.put("visible", visible());
        } catch (Throwable ignored) { return; }
        CloudTransport.withToken(IO, H, token -> {
            try {
                CloudTransport.send(new URL(BASE + "/me"), "PUT", token, body);
            } catch (Throwable ignored) { }
        }, null);
    }

    /** Handle to a live watcher; close() stops the polling for good. */
    public static class Handle {
        private Runnable poll;
        private boolean closed;
        private boolean inFlight;
        public void close() {
            closed = true;
            if (poll != null) H.removeCallbacks(poll);
        }
    }

    /** Watches one user's presence on Cloudflare. Emits on the main thread whenever it changes. */
    public static Handle watch(String uid, Callback cb) {
        Handle h = new Handle();
        if (uid == null || uid.isEmpty() || cb == null) return h;
        final boolean[] emitted = {false};
        final boolean[] lastOnline = {false};
        final long[] lastSeen = {0};
        h.poll = new Runnable() {
            @Override public void run() {
                if (h.closed || h.inFlight) return;
                if (Fb.uid() == null) { h.closed = true; return; }
                h.inFlight = true;
                CloudTransport.withToken(IO, H, token -> {
                    boolean online = false;
                    long seen = 0;
                    boolean ok = false;
                    try {
                        JSONObject data = CloudTransport.send(new URL(BASE + "/contact/" + enc(uid)), "GET", token, null);
                        if (data != null) {
                            online = data.optBoolean("online", false);
                            seen = data.optLong("lastSeen", 0);
                            ok = true;
                        }
                    } catch (Throwable ignored) { }
                    final boolean fOk = ok, fOnline = online;
                    final long fSeen = seen;
                    H.post(() -> {
                        h.inFlight = false;
                        if (h.closed) return;
                        // An unreachable Worker is not a presence change: keep the last known state.
                        if (!fOk) return;
                        if (!emitted[0] || fOnline != lastOnline[0] || fSeen != lastSeen[0]) {
                            emitted[0] = true;
                            lastOnline[0] = fOnline;
                            lastSeen[0] = fSeen;
                            cb.onPresence(fOnline, fSeen);
                        }
                    });
                }, () -> H.post(() -> h.inFlight = false));
                if (!h.closed) H.postDelayed(this, PRESENCE_POLL_MS);
            }
        };
        H.post(h.poll);
        return h;
    }

    // ---- typing ----
    private static final Map<String, Runnable> typingClear = new HashMap<>();
    private static final Map<String, Long> typingSent = new HashMap<>();

    public static void setTyping(String chatId, boolean typing) {
        String uid = Fb.uid();
        if (uid == null || chatId == null || chatId.isEmpty()) return;

        // Throttle repeats: a keystroke storm must not become a request storm.
        Long last = typingSent.get(chatId);
        long now = System.currentTimeMillis();
        if (typing && last != null && now - last < TYPING_THROTTLE_MS) return;
        typingSent.put(chatId, now);

        final JSONObject body = new JSONObject();
        try { body.put("typing", typing); } catch (Throwable ignored) { return; }
        CloudTransport.withToken(IO, H, token -> {
            try {
                CloudTransport.send(new URL(BASE + "/typing/" + enc(chatId)), "PUT", token, body);
            } catch (Throwable ignored) { }
        }, null);

        Runnable old = typingClear.remove(chatId);
        if (old != null) H.removeCallbacks(old);
        if (typing) {
            Runnable clear = () -> setTyping(chatId, false);
            typingClear.put(chatId, clear);
            H.postDelayed(clear, TYPING_CLEAR_MS);
        }
    }

    /**
     * Watches whether the OTHER participant of a chat is typing. The Worker already excludes the
     * caller from its own answer, so no client-side uid comparison is needed.
     */
    public static Handle watchTyping(String chatId, String myUid, Consumer<Boolean> cb) {
        Handle h = new Handle();
        if (chatId == null || chatId.isEmpty() || cb == null) return h;
        final Boolean[] last = {null};
        h.poll = new Runnable() {
            @Override public void run() {
                if (h.closed || h.inFlight) return;
                if (Fb.uid() == null) { h.closed = true; return; }
                h.inFlight = true;
                CloudTransport.withToken(IO, H, token -> {
                    boolean typing = false;
                    boolean ok = false;
                    try {
                        JSONObject data = CloudTransport.send(new URL(BASE + "/typing/" + enc(chatId)), "GET", token, null);
                        if (data != null) {
                            typing = data.optBoolean("typing", false);
                            ok = true;
                        }
                    } catch (Throwable ignored) { }
                    final boolean fOk = ok, fTyping = typing;
                    H.post(() -> {
                        h.inFlight = false;
                        if (h.closed || !fOk) return;
                        if (last[0] == null || last[0] != fTyping) {
                            last[0] = fTyping;
                            cb.accept(fTyping);
                        }
                    });
                }, () -> H.post(() -> h.inFlight = false));
                if (!h.closed) H.postDelayed(this, TYPING_POLL_MS);
            }
        };
        H.post(h.poll);
        return h;
    }

    private static String enc(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (Throwable ignored) {
            return value;
        }
    }
}
