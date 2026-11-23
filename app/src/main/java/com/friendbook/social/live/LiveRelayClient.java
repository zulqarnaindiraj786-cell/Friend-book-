package com.friendbook.social.live;

import android.os.Handler;
import android.os.Looper;

import com.friendbook.social.BuildConfig;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.json.JSONObject;

/** Authenticated, bounded, in-memory transport for the existing live codecs. */
public final class LiveRelayClient {
    public interface Listener {
        void ready();
        void packet(String kind, String data, long seq);
        void disconnected();
        void audience(int count);
    }

    private static final OkHttpClient HTTP = new OkHttpClient();
    private static final int MAX_PACKET = 96 * 1024;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private volatile WebSocket socket;
    private volatile boolean stopped;
    private volatile boolean ready;
    // The relay announces its lease once. Renewal is piggy-backed on real traffic
    // when the lease is nearly over, so no client-side interval timer exists.
    private volatile long leaseUntil;
    private volatile boolean renewRequested;

    public LiveRelayClient(Listener listener) { this.listener = listener; }

    void open(String room, boolean publish) {
        open(room, publish ? "publish" : "watch");
    }

    void open(String room, String role) {
        if (room == null || !room.matches("[A-Za-z0-9_-]{1,128}")) { fail(); return; }
        connect("/v1/live/" + room + "/" + role, role);
    }

    public void openCall(String chat, String call) {
        if (chat == null || !chat.matches("[A-Za-z0-9_-]{1,200}") || call == null || !call.matches("[A-Za-z0-9_-]{1,80}")) { fail(); return; }
        connect("/v1/chat/call/" + chat + "/" + call, "call");
    }

    private void connect(String path, String role) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) { fail(); return; }
        user.getIdToken(false).addOnSuccessListener(token -> {
            if (stopped) return;
            FirebaseUser current = FirebaseAuth.getInstance().getCurrentUser();
            if (current == null || !user.getUid().equals(current.getUid()) || token.getToken() == null) { fail(); return; }
            Request request = new Request.Builder().url(BuildConfig.CLOUDFLARE_API_URL + path)
                    .header("Authorization", "Bearer " + token.getToken()).build();
            socket = HTTP.newWebSocket(request, new WebSocketListener() {
                @Override public void onOpen(WebSocket ws, Response response) {
                    main.post(() -> {
                        if (stopped) { ws.cancel(); return; }
                        ready = true;
                        listener.ready();
                    });
                }
                @Override public void onMessage(WebSocket ws, String packet) {
                    if (stopped || packet.length() > MAX_PACKET) return;
                    maybeRenew();
                    try {
                        JSONObject json = new JSONObject(packet);
                        String kind = json.getString("kind");
                        if (kind.equals("lease")) {
                            long ms = json.optLong("ms", 0L);
                            if (ms > 0 && ms <= 60_000L) {
                                leaseUntil = android.os.SystemClock.elapsedRealtime() + ms;
                                renewRequested = false;
                            }
                            return;
                        }
                        if (kind.equals("audience")) {
                            int count = json.getInt("count");
                            if (count >= 0 && count <= 48) main.post(() -> { if (!stopped) listener.audience(count); });
                            return;
                        }
                        if (kind.startsWith("co_") || kind.startsWith("pk_")) {
                            if (!("watch".equals(role) || "publish".equals(role))) return;
                        } else if (!("watch".equals(role) || "call".equals(role))) return;
                        String data = json.getString("data");
                        long seq = json.getLong("seq");
                        if (seq > 0 && !data.isEmpty() && (("call".equals(role) && kind.equals("camera")) || kind.equals("audio") || kind.equals("video") || kind.equals("co_audio") || kind.equals("co_video") || kind.equals("pk_audio") || kind.equals("pk_video"))) {
                            listener.packet(kind, data, seq);
                        }
                    } catch (org.json.JSONException ignored) { }
                }
                @Override public void onClosing(WebSocket ws, int code, String reason) { fail(); }
                @Override public void onFailure(WebSocket ws, Throwable error, Response response) { fail(); }
            });
            if (stopped) socket.cancel();
        }).addOnFailureListener(error -> fail());
    }

    /** Called from real traffic only: no timer drives this. */
    private void maybeRenew() {
        WebSocket s = socket;
        if (stopped || !ready || s == null || renewRequested) return;
        long until = leaseUntil;
        if (until == 0L || android.os.SystemClock.elapsedRealtime() < until - 5_000L) return;
        renewRequested = true;
        if (!s.send("renew")) fail();
    }

    public void send(String kind, String data, long seq) {
        WebSocket s = socket;
        if (!ready || stopped || s == null || data == null || data.length() > MAX_PACKET - 128 || s.queueSize() > MAX_PACKET) return;
        try {
            if (!s.send(new JSONObject().put("kind", kind).put("data", data).put("seq", seq).toString())) fail();
        } catch (org.json.JSONException ignored) { }
    }

    private void fail() {
        main.post(() -> {
            if (stopped) return;
            close();
            listener.disconnected();
        });
    }

    public void close() {
        stopped = true;
        ready = false;
        WebSocket s = socket;
        if (s != null) s.cancel();
    }
}
