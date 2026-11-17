package com.friendbook.social.core;

import org.json.JSONObject;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import okhttp3.ConnectionPool;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * The one HTTP path to the worker.
 *
 * <p><b>Why this is OkHttp now.</b> Every call used {@code HttpURLConnection} and ended in
 * {@code disconnect()}, which tears the socket down. Nothing was pooled, so each write and each
 * read paid a fresh DNS resolution and a full TLS handshake to Cloudflare - on a mobile network
 * that is easily 300-600ms of pure overhead. A like, a comment and a message each make two or
 * three such calls, which is exactly the "app is very slow" complaint. A shared client with a
 * keep-alive pool reuses one connection for all of them, so only the first call pays the
 * handshake.
 *
 * <p>The contract is unchanged: one bounded attempt, a JSON body back, and a thrown exception on
 * anything that is not 2xx. Retrying stays with the caller, because a retried write has to be
 * idempotent at the document level.
 */
final class D1HttpTransport {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    /** One client for the whole process: this is what makes the connections reusable. */
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            // Keep idle sockets for five minutes so a user moving between screens keeps the
            // connection; four idle sockets covers the feed plus a chat plus one detail screen.
            .connectionPool(new ConnectionPool(4, 5, TimeUnit.MINUTES))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            // Never follow a redirect. OkHttp would resend the bearer token and the request body
            // to whatever host the Location header names, and the old transport refused too.
            .followRedirects(false)
            .followSslRedirects(false)
            .build();

    private D1HttpTransport() {
    }

    /** Exposed so a test can assert the redirect rule without a live server. */
    static boolean followsRedirects() {
        return CLIENT.followRedirects();
    }

    static JSONObject send(URL url, String method, String token, JSONObject body) throws IOException {
        byte[] bytes = body == null ? null : body.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes != null && bytes.length > 32768) throw new IOException("Comment request too large");

        RequestBody requestBody = bytes == null ? null : RequestBody.create(bytes, JSON);
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .method(method, "GET".equals(method) ? null : (requestBody == null
                        ? RequestBody.create(new byte[0], JSON) : requestBody));
        if (token != null) builder.header("Authorization", "Bearer " + token);

        try (Response response = CLIENT.newCall(builder.build()).execute()) {
            int status = response.code();
            ResponseBody payload = response.body();
            String text = payload == null ? "" : payload.string();
            if (status < 200 || status >= 300) {
                throw new IOException("D1 request failed (" + status + "). Retry here; no Firebase write was made.");
            }
            if (text.length() > 1_048_576) throw new IOException("Response too large");
            if (text.isEmpty()) return new JSONObject();
            return new JSONObject(text);
        } catch (org.json.JSONException malformed) {
            throw new IOException("Malformed D1 response", malformed);
        }
    }
}
