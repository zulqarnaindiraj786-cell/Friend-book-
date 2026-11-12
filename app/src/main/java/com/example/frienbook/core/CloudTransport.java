package com.example.frienbook.core;

import com.google.firebase.auth.FirebaseUser;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * The single HTTP door to the Cloudflare Worker for every non-auth feature.
 *
 * Firebase appears here for AUTHENTICATION ONLY: a Firebase ID token is fetched and handed to the
 * Worker, which verifies it and owns the data (D1, R2, Analytics Engine). No Firestore read or write
 * happens on any path that goes through this class.
 *
 * One bounded attempt per call: connect 10s, read 15s, response capped at 1 MiB, request body capped
 * at 32 KiB. Failures throw to the caller's background thread - nothing is retried in a loop and no
 * number or document is ever invented locally.
 */
public final class CloudTransport {

    private CloudTransport() {}

    /** The deployed Worker. Relative paths are appended by each feature class. */
    public static final String BASE = "https://rough-firefly-dd7b.zulqarnaindiraj786.workers.dev";

    public interface TokenWork {
        void run(String token);
    }

    /**
     * Hands a fresh Firebase ID token to `work` on a background thread. When no token is available
     * (signed out, account switch, saturated queue) `onUnavailable` runs instead so callers can
     * release their in-flight guards - nothing is faked, nothing is retried forever.
     */
    public static void withToken(final java.util.concurrent.ExecutorService io,
                                 final android.os.Handler main,
                                 final TokenWork work, final Runnable onUnavailable) {
        if (work == null) return;
        try {
            final FirebaseUser user = Fb.auth().getCurrentUser();
            final String actor = Fb.uid();
            if (user == null || actor == null || actor.isEmpty()) {
                if (onUnavailable != null) onUnavailable.run();
                return;
            }
            user.getIdToken(false)
                    .addOnSuccessListener(res -> {
                        final String token = res != null ? res.getToken() : null;
                        if (token == null || token.isEmpty()) {
                            if (onUnavailable != null) onUnavailable.run();
                            return;
                        }
                        try {
                            io.execute(() -> {
                                try {
                                    // Never act for a different account than the one that asked.
                                    if (!actor.equals(Fb.uid())) {
                                        if (onUnavailable != null) main.post(onUnavailable);
                                        return;
                                    }
                                    work.run(token);
                                } catch (Throwable ignored) { }
                            });
                        } catch (Throwable rejected) {
                            if (onUnavailable != null) onUnavailable.run();
                        }
                    })
                    .addOnFailureListener(e -> {
                        if (onUnavailable != null) onUnavailable.run();
                    });
        } catch (Throwable ignored) {
            if (onUnavailable != null) onUnavailable.run();
        }
    }

    /** One bounded HTTP attempt. Non-2xx throws; the body (if any) is sent as JSON. */
    public static JSONObject send(URL url, String method, String token, JSONObject body) throws Exception {
        byte[] bytes = body == null ? null : body.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes != null && bytes.length > 32768) throw new IOException("Request body too large");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod(method);
            if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Accept", "application/json");
            if (bytes != null) {
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(bytes.length);
                connection.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("Cloudflare request failed (" + status + ")");
            }
            try (InputStream in = connection.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    if (out.size() + n > 1048576) throw new IOException("Response too large");
                    out.write(buffer, 0, n);
                }
                return new JSONObject(out.toString("UTF-8"));
            }
        } finally {
            connection.disconnect();
        }
    }
}
