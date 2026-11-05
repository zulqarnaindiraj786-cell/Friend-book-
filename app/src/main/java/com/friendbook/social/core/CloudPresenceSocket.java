package com.friendbook.social.core;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.friendbook.social.BuildConfig;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import java.util.concurrent.TimeUnit;
import okhttp3.*;

/** Real transport adapter, disabled by default and NOT attached to production App/Presence. */
public final class CloudPresenceSocket implements AutoCloseable {
    private static final String ENDPOINT = "wss://rough-firefly-dd7b.zulqarnaindiraj786.workers.dev/v1/presence/socket";
    private final FirebaseAuth auth;
    private final Handler main;
    private final OkHttpClient client;
    private final boolean enabled;
    private final String endpoint;
    private final PresenceSocketController controller;
    private boolean foreground, visible, disposed;
    private final FirebaseAuth.AuthStateListener authListener;
    public CloudPresenceSocket(FirebaseAuth auth) {
        this(auth, new Handler(Looper.getMainLooper()), new OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS).connectTimeout(10, TimeUnit.SECONDS).pingInterval(30, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build(), BuildConfig.CLOUDFLARE_PRESENCE_ENABLED, ENDPOINT);
    }
    // Package-private transport seam for loopback tests only; public endpoint/gate are fixed.
    CloudPresenceSocket(FirebaseAuth auth, Handler handler, OkHttpClient client, boolean enabled, String endpoint) {
        this.auth = auth; this.main = handler; this.client = client; this.enabled = enabled; this.endpoint = endpoint;
        controller = new PresenceSocketController(new PresenceSocketController.Runtime() {
            public long elapsed() { return SystemClock.elapsedRealtime(); }
            public double random() { return Math.random(); }
            public boolean currentActor(String uid) { return !disposed && uid.equals(auth.getUid()); }
            public PresenceSocketController.Cancel later(long delay, Runnable task) {
                main.postDelayed(task, delay); return () -> main.removeCallbacks(task);
            }
            public void token(String uid, PresenceSocketController.TokenResult result) { fetchToken(uid, false, result); }
            public PresenceSocketController.Connection connect(String token, PresenceSocketController.Events events) {
                Handle handle = new Handle();
                Request request = new Request.Builder().url(CloudPresenceSocket.this.endpoint)
                    .header("Authorization", "Bearer " + token).build();
                WebSocket candidate = CloudPresenceSocket.this.client.newWebSocket(request, new WebSocketListener() {
                    @Override public void onOpen(WebSocket socket, Response response) { handle.bind(socket); onMain(() -> events.opened(handle)); }
                    @Override public void onFailure(WebSocket socket, Throwable error, Response response) {
                        int code = response == null ? 0 : response.code();
                        boolean retryable = response == null || code == 500 || code == 502 || code == 504;
                        if (response != null) try { response.close(); } catch (Exception ignored) { }
                        onMain(() -> events.failed(handle, retryable));
                    }
                    @Override public void onClosing(WebSocket socket, int code, String reason) {
                        socket.close(code, null);
                        onMain(() -> events.failed(handle, code == 1000 || code == 1001 || (code == 1008 && "Session expired".equals(reason))));
                    }
                });
                handle.bind(candidate);
                return handle;
            }
        });
        authListener = ignored -> onMain(this::apply);
        if (enabled) auth.addAuthStateListener(authListener);
    }
    private void onMain(Runnable action) {
        if (Looper.myLooper() == main.getLooper()) action.run(); else main.post(action);
    }
    public void foreground(boolean activeStatusVisible) { onMain(() -> { foreground = true; visible = activeStatusVisible; apply(); }); }
    public void background() { onMain(() -> { foreground = false; apply(); }); }
    public void activeStatus(boolean activeStatusVisible) { onMain(() -> { visible = activeStatusVisible; apply(); }); }
    private void apply() { controller.update(enabled && !disposed ? auth.getUid() : null, foreground && !disposed, visible); }
    private void fetchToken(String uid, boolean force, PresenceSocketController.TokenResult result) {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null || !uid.equals(user.getUid())) { result.failed(); return; }
        user.getIdToken(force).addOnCompleteListener(Runnable::run, task -> onMain(() -> {
            if (disposed || !uid.equals(auth.getUid()) || !task.isSuccessful() || task.getResult() == null) { result.failed(); return; }
            long lease = task.getResult().getExpirationTimestamp() * 1000L - System.currentTimeMillis();
            if (!force && lease <= 30_000L) { fetchToken(uid, true, result); return; }
            result.ready(task.getResult().getToken(), lease);
        }));
    }
    @Override public void close() { onMain(() -> {
        if (disposed) return;
        disposed = true;
        if (enabled) auth.removeAuthStateListener(authListener);
        controller.stop();
    }); }
    private final class Handle implements PresenceSocketController.Connection {
        private volatile WebSocket socket;
        private boolean closing;
        public boolean send(String text) { return socket != null && socket.send(text); }
        public synchronized void bind(WebSocket candidate) {
            if (socket != null) return;
            socket = candidate;
            if (closing) closeBound(candidate);
        }
        public synchronized void close() {
            if (closing) return;
            closing = true;
            if (socket != null) closeBound(socket);
        }
        private void closeBound(WebSocket old) {
            old.close(1000, "Inactive");
            // Cancel this captured old socket only, never a replacement session.
            main.postDelayed(old::cancel, 2000L);
        }
    }
}
