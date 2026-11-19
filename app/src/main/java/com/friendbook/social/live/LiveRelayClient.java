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
final class LiveRelayClient {
    interface Listener {
        void ready();
        void packet(String kind, String data, long seq);
        void disconnected();
    }

    private static final OkHttpClient HTTP = new OkHttpClient();
    private static final int MAX_PACKET = 96 * 1024;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private volatile WebSocket socket;
    private volatile boolean stopped;
    private volatile boolean ready;
    private final Runnable renew = new Runnable() {
        @Override public void run() {
            WebSocket s = socket;
            if (stopped || !ready || s == null) return;
            if (!s.send("renew")) { fail(); return; }
            main.postDelayed(this, 2000);
        }
    };

    LiveRelayClient(Listener listener) { this.listener = listener; }

    void open(String room, boolean publish) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null || room == null || !room.matches("[A-Za-z0-9_-]{1,128}")) { fail(); return; }
        user.getIdToken(false).addOnSuccessListener(token -> {
            if (stopped) return;
            FirebaseUser current = FirebaseAuth.getInstance().getCurrentUser();
            if (current == null || !user.getUid().equals(current.getUid()) || token.getToken() == null) { fail(); return; }
            Request request = new Request.Builder().url(BuildConfig.CLOUDFLARE_API_URL + "/v1/live/" + room + (publish ? "/publish" : "/watch"))
                    .header("Authorization", "Bearer " + token.getToken()).build();
            socket = HTTP.newWebSocket(request, new WebSocketListener() {
                @Override public void onOpen(WebSocket ws, Response response) {
                    main.post(() -> {
                        if (stopped) { ws.cancel(); return; }
                        ready = true;
                        main.postDelayed(renew, 2000);
                        listener.ready();
                    });
                }
                @Override public void onMessage(WebSocket ws, String packet) {
                    if (publish || stopped || packet.length() > MAX_PACKET) return;
                    try {
                        JSONObject json = new JSONObject(packet);
                        String kind = json.getString("kind"), data = json.getString("data");
                        long seq = json.getLong("seq");
                        if (seq > 0 && !data.isEmpty() && (kind.equals("audio") || kind.equals("video"))) {
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

    void send(String kind, String data, long seq) {
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

    void close() {
        stopped = true;
        ready = false;
        main.removeCallbacks(renew);
        WebSocket s = socket;
        if (s != null) s.cancel();
    }
}
