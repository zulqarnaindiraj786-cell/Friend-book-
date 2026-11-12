package com.example.frienbook.core;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Videos and photos live on Cloudflare R2 (final architecture, point 4) - uploaded straight to the
 * Worker's `/v1/videos` and `/v1/photos` endpoints, which store them in bucket `friendbook` with
 * retention "forever" and answer the verified public URL.
 *
 * The object id is the SHA-256 of the bytes, so the same file can never be stored twice and the
 * Worker's own head-check makes repeats free. The response is accepted ONLY when the Worker says
 * provider "r2" and the URL is the bucket's public origin - a guessed or echoed URL is rejected, so
 * a displayed media URL always points at a real stored object.
 *
 * Firebase Storage and Cloudinary are not on this path at all.
 */
public final class CloudflareMedia {

    private CloudflareMedia() {}

    private static final String BASE = CloudTransport.BASE;
    private static final String ORIGIN = "https://pub-88ab0a56178742af95cbbf62df152a67.r2.dev";
    private static final long MAX_VIDEO_BYTES = 99_000_000L;
    private static final long MAX_PHOTO_BYTES = 25_000_000L;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cf-media");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static void uploadVideo(Context ctx, Uri uri, Consumer<String> onUrl, Consumer<String> onError) {
        upload(ctx, uri, "/v1/videos", "X-Video-Id", "video/mp4", MAX_VIDEO_BYTES, onUrl, onError);
    }

    /**
     * Photos, avatars, covers, comment images, payment screenshots and voice notes.
     * Pass {@code mime} as null to let the resolver pick the real type (png/webp/gif/m4a/mp3 are all
     * stored with their own extension by the Worker, so a PNG never lands wearing a .jpg name).
     */
    public static void uploadPhoto(Context ctx, Uri uri, String mime, Consumer<String> onUrl, Consumer<String> onError) {
        String resolved = mime;
        if (resolved == null || resolved.trim().isEmpty()) {
            try { resolved = ctx.getApplicationContext().getContentResolver().getType(uri); } catch (Throwable ignored) {}
        }
        upload(ctx, uri, "/v1/photos", "X-Photo-Id",
                resolved == null || resolved.trim().isEmpty() ? "image/jpeg" : resolved.trim(),
                MAX_PHOTO_BYTES, onUrl, onError);
    }

    private static void upload(Context ctx, Uri uri, String path, String idHeader, String mime,
                               long maxBytes, Consumer<String> onUrl, Consumer<String> onError) {
        final Context app = ctx.getApplicationContext();
        CloudTransport.withToken(IO, MAIN, token -> {
            try {
                byte[] bytes = readAll(app, uri, maxBytes);
                String id = sha256Hex(bytes);
                JSONObject body = postRaw(new URL(BASE + path), token, bytes, idHeader, id, mime);
                String url = body != null ? body.optString("url", "") : "";
                String provider = body != null ? body.optString("provider", "") : "";
                if (!"r2".equals(provider) || !url.startsWith(ORIGIN + "/")) {
                    throw new java.io.IOException("Upload not confirmed on R2");
                }
                MAIN.post(() -> onUrl.accept(url));
            } catch (Throwable e) {
                final String message = e.getMessage() == null ? "upload failed" : e.getMessage();
                MAIN.post(() -> onError.accept(message));
            }
        }, () -> MAIN.post(() -> onError.accept("sign-in unavailable")));
    }

    private static byte[] readAll(Context ctx, Uri uri, long maxBytes) throws Exception {
        try (InputStream in = ctx.getContentResolver().openInputStream(uri);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) throw new java.io.IOException("Cannot open file");
            byte[] buffer = new byte[65536];
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (out.size() + n > maxBytes) throw new java.io.IOException("File too large");
                out.write(buffer, 0, n);
            }
            if (out.size() == 0) throw new java.io.IOException("Empty file");
            return out.toByteArray();
        }
    }

    private static String sha256Hex(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(data);
        StringBuilder text = new StringBuilder();
        for (byte b : hash) text.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
        return text.toString();
    }

    private static JSONObject postRaw(URL url, String token, byte[] bytes, String idHeader, String id, String mime)
            throws Exception {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(120000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(bytes.length);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty(idHeader, id);
            connection.setRequestProperty("Content-Type", mime);
            connection.setRequestProperty("Accept", "application/json");
            try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            int status = connection.getResponseCode();
            InputStream stream = status < 400 ? connection.getInputStream() : connection.getErrorStream();
            String text = "";
            if (stream != null) {
                try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[4096];
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        if (out.size() + n > 262144) break;
                        out.write(buffer, 0, n);
                    }
                    text = out.toString("UTF-8");
                }
            }
            if (status < 200 || status >= 300) {
                throw new java.io.IOException("R2 upload failed (" + status + ")");
            }
            return new JSONObject(text);
        } finally {
            connection.disconnect();
        }
    }
}
