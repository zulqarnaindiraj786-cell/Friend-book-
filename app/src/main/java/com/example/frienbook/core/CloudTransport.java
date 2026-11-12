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

    /**
     * The synchronous form every data-layer caller uses: one bounded attempt against a relative
     * Worker path with a fresh Firebase ID token attached. Must be called off the main thread - it
     * blocks until the answer or the timeout arrives.
     *
     * @return null when the Worker answered 2xx (the body lands in `out`), otherwise a short reason.
     *         Nothing is retried here and no value is invented: an offline caller gets the error and
     *         decides what to show.
     */
    public static String call(String path, String method, JSONObject body, JSONObject out) {
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        final String[] error = {null};
        withToken(CALL_IO, CALL_MAIN, token -> {
            try {
                JSONObject answer = send(new URL(BASE + path), method, token, body);
                if (out != null && answer != null) {
                    java.util.Iterator<String> keys = answer.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        out.put(key, answer.get(key));
                    }
                }
            } catch (Exception failed) {
                String why = failed.getMessage();
                error[0] = why == null || why.isEmpty() ? "Cloudflare request failed" : why;
            } finally {
                done.countDown();
            }
        }, () -> {
            error[0] = "not signed in";
            done.countDown();
        });
        try {
            // The token fetch is async, so the latch covers both it and the request.
            done.await(25, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            if (error[0] == null) error[0] = "interrupted";
        }
        if (error[0] == null && done.getCount() > 0) error[0] = "timed out";
        return error[0];
    }

    private static final java.util.concurrent.ExecutorService CALL_IO =
            java.util.concurrent.Executors.newFixedThreadPool(3);
    private static final android.os.Handler CALL_MAIN = new android.os.Handler(android.os.Looper.getMainLooper());

    /** A non-2xx answer: the status the Worker chose, and the reason it gave. */
    public static final class HttpError extends IOException {
        public final int status;

        HttpError(int status, String reason) {
            super(reason == null || reason.isEmpty() ? "Cloudflare request failed (" + status + ")" : reason);
            this.status = status;
        }
    }

    private static String readError(HttpURLConnection connection) {
        InputStream stream = null;
        try {
            stream = connection.getErrorStream();
            if (stream == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[2048];
            int n;
            while ((n = stream.read(buffer)) != -1 && out.size() < 8192) out.write(buffer, 0, n);
            String text = out.toString("UTF-8").trim();
            if (text.isEmpty()) return null;
            if (text.charAt(0) == '{') {
                String reason = new JSONObject(text).optString("error", "");
                if (!reason.isEmpty()) return reason;
            }
            return text.length() > 200 ? text.substring(0, 200) : text;
        } catch (Exception ignored) {
            return null;
        } finally {
            if (stream != null) try { stream.close(); } catch (Exception ignored) { }
        }
    }

    /**
     * The form the document layer uses: one bounded attempt that either answers with the Worker's JSON
     * or throws, carrying the status when the Worker refused. Off the main thread only.
     */
    public static JSONObject request(String path, String method, JSONObject body) throws Exception {
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        final JSONObject[] answer = {null};
        final Exception[] failure = {null};
        withToken(CALL_IO, CALL_MAIN, token -> {
            try {
                answer[0] = send(new URL(BASE + path), method, token, body);
            } catch (Exception failed) {
                failure[0] = failed;
            } finally {
                done.countDown();
            }
        }, () -> {
            failure[0] = new HttpError(401, "not signed in");
            done.countDown();
        });
        try {
            done.await(25, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            if (failure[0] == null) failure[0] = new IOException("interrupted");
        }
        if (failure[0] == null && done.getCount() > 0) failure[0] = new IOException("timed out");
        if (failure[0] != null) throw failure[0];
        return answer[0] == null ? new JSONObject() : answer[0];
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
                // The Worker always says why ("Not allowed", "chatId filter required", "Document is
                // deleted"). Losing that would leave a screen explaining a failure it does not
                // understand, so the reason travels with the status.
                throw new HttpError(status, readError(connection));
            }
            try (InputStream in = connection.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    if (out.size() + n > 1048576) throw new IOException("Response too large");
                    out.write(buffer, 0, n);
                }
                String text = out.toString("UTF-8");
                return text.isEmpty() ? new JSONObject() : new JSONObject(text);
            }
        } finally {
            connection.disconnect();
        }
    }
}
