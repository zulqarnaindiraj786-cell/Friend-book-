package com.friendbook.social.core;

import com.friendbook.social.BuildConfig;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Shared connection pool for document and engagement APIs. No credential forwarding or write replay. */
public final class CloudflareHttp {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final OkHttpClient DOCUMENTS = new OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .addNetworkInterceptor(chain -> {
                Response response = chain.proceed(chain.request());
                // Prevent OkHttp's implicit 503 Retry-After replay.
                return response.code() == 503 ? response.newBuilder().removeHeader("Retry-After").build() : response;
            })
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .build();
    private static final OkHttpClient ENGAGEMENT = DOCUMENTS.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build();

    private CloudflareHttp() {}

    public static Response openDocument(String path, String method, String token, byte[] body) throws IOException {
        return open(DOCUMENTS, BuildConfig.CLOUDFLARE_API_URL + path, method, token, body);
    }

    static Response openEngagement(String url, String method, String token, byte[] body) throws IOException {
        return open(ENGAGEMENT, url, method, token, body);
    }

    private static Response open(OkHttpClient client, String url, String method, String token, byte[] body) throws IOException {
        Request.Builder request = new Request.Builder().url(url).header("Accept", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        RequestBody payload = body == null && !"POST".equals(method) && !"PUT".equals(method) && !"PATCH".equals(method)
                ? null : RequestBody.create(body == null ? new byte[0] : body, JSON);
        return client.newCall(request.method(method, payload).build()).execute();
    }

    public static String readUtf8(ResponseBody body, int limit) throws IOException {
        if (body == null) return "";
        if (body.contentLength() > limit) throw new IOException("Cloudflare response too large");
        try (InputStream input = body.byteStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) != -1;) {
                if (count > limit - output.size()) throw new IOException("Cloudflare response too large");
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
