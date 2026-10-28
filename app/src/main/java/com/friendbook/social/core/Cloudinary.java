package com.friendbook.social.core;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * ☁️ friendbook 2G Cloudinary Uploader:
 * Automatically downscales & compresses camera/gallery images on-device BEFORE upload
 * (~55 KB in 2G/Data Saver mode, ~110 KB in Standard mode instead of 8–12 MB raw photos!),
 * so photo posts, stories, avatars & chat images upload in 1–2 seconds even on 2G!
 */
public class Cloudinary {

    private static final String CLOUD_NAME = "x5ed4d0b";
    private static final String PRESET = "Myapp_upload";
    private static final String ENDPOINT =
            "https://api.cloudinary.com/v1_1/" + CLOUD_NAME + "/auto/upload";

    /** Compress an image Uri in memory for ultra-fast 2G upload (returns null if non-image or animated GIF). */
    private static byte[] compressImageFor2G(Context ctx, Uri uri, String mime) {
        if (mime == null || !mime.startsWith("image/") || mime.contains("gif")) return null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            InputStream s1 = ctx.getContentResolver().openInputStream(uri);
            if (s1 == null) return null;
            BitmapFactory.decodeStream(s1, null, bounds);
            s1.close();
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            boolean slow2g = FastCache.isSlow2G(ctx);
            int maxDim = slow2g ? 720 : (Prefs.turbo2g(ctx) ? 1080 : 1440);
            int quality = slow2g ? 60 : (Prefs.turbo2g(ctx) ? 75 : 85);

            int sample = 1;
            int w = bounds.outWidth, h = bounds.outHeight;
            while (w / sample > maxDim * 2 || h / sample > maxDim * 2) {
                sample *= 2;
            }

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            InputStream s2 = ctx.getContentResolver().openInputStream(uri);
            if (s2 == null) return null;
            Bitmap bmp = BitmapFactory.decodeStream(s2, null, opts);
            s2.close();
            if (bmp == null) return null;

            int bw = bmp.getWidth(), bh = bmp.getHeight();
            if (bw > maxDim || bh > maxDim) {
                float ratio = Math.min((float) maxDim / bw, (float) maxDim / bh);
                int nw = Math.max(1, Math.round(bw * ratio));
                int nh = Math.max(1, Math.round(bh * ratio));
                Bitmap scaled = Bitmap.createScaledBitmap(bmp, nw, nh, true);
                if (scaled != bmp) bmp.recycle();
                bmp = scaled;
            }

            ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos);
            bmp.recycle();
            byte[] compressed = bos.toByteArray();
            Prefs.recordBytes(ctx, compressed.length, Math.max(0L, 1_800_000L - compressed.length));
            return compressed;
        } catch (Throwable t) {
            return null;
        }
    }

    public static Task<String> upload(final Context ctx, final Uri uri) {
        final TaskCompletionSource<String> tcs = new TaskCompletionSource<>();
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                String mime = ctx.getContentResolver().getType(uri);
                if (mime == null || mime.isEmpty()) mime = "application/octet-stream";

                byte[] compressedImg = compressImageFor2G(ctx, uri, mime);
                String ext = "bin";
                if (compressedImg != null) {
                    mime = "image/jpeg";
                    ext = "jpg";
                } else if (mime.startsWith("image/")) {
                    ext = mime.substring(6).replace("jpeg", "jpg");
                } else if (mime.startsWith("video/")) {
                    ext = mime.substring(6);
                } else if (mime.startsWith("audio/")) {
                    ext = mime.substring(6).replace("mpeg", "mp3");
                }
                String fname = "friendbook_" + System.currentTimeMillis() + "." + ext;

                String boundary = "fb" + System.currentTimeMillis();
                conn = (HttpURLConnection) new URL(ENDPOINT).openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(35000);
                conn.setReadTimeout(90000);
                conn.setChunkedStreamingMode(16 * 1024); // small 16 KB chunks for 2G stability
                conn.setRequestProperty("Content-Type",
                        "multipart/form-data; boundary=" + boundary);

                OutputStream raw = conn.getOutputStream();
                DataOutputStream out = new DataOutputStream(raw);

                out.writeBytes("--" + boundary + "\r\n");
                out.writeBytes("Content-Disposition: form-data; name=\"upload_preset\"\r\n\r\n");
                out.writeBytes(PRESET + "\r\n");

                out.writeBytes("--" + boundary + "\r\n");
                out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\""
                        + fname + "\"\r\n");
                out.writeBytes("Content-Type: " + mime + "\r\n\r\n");

                InputStream in = compressedImg != null
                        ? new ByteArrayInputStream(compressedImg)
                        : ctx.getContentResolver().openInputStream(uri);
                if (in == null) throw new Exception("Cannot read the selected file");
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                in.close();
                out.writeBytes("\r\n--" + boundary + "--\r\n");
                out.flush();
                out.close();

                int code = conn.getResponseCode();
                InputStream rs = code >= 200 && code < 300
                        ? conn.getInputStream() : conn.getErrorStream();
                BufferedReader br = new BufferedReader(new InputStreamReader(rs));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();

                if (code >= 200 && code < 300) {
                    String secure = new JSONObject(sb.toString()).optString("secure_url", "");
                    if (!secure.isEmpty()) tcs.setResult(secure);
                    else tcs.setException(new Exception("Cloudinary response had no secure_url"));
                } else {
                    tcs.setException(new Exception("Cloudinary " + code + ": "
                            + sb.substring(0, Math.min(160, sb.length()))));
                }
            } catch (Exception e) {
                tcs.setException(e);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }, "cloudinary-upload").start();
        return tcs.getTask();
    }

    /**
     * Upload to Cloudinary (compressed for 2G!), and if that fails fall back to
     * Firebase Storage using the compressed temp file if available.
     */
    public static Task<String> uploadOrFirebase(final Context ctx, final String path, final Uri uri) {
        final TaskCompletionSource<String> tcs = new TaskCompletionSource<>();
        upload(ctx, uri).addOnCompleteListener(t -> {
            if (t.isSuccessful()) { tcs.setResult(t.getResult()); return; }
            android.util.Log.w("Cloudinary", "cloudinary failed, trying Firebase: " + t.getException());
            new Thread(() -> {
                Uri fallbackUri = uri;
                File tempFile = null;
                try {
                    String mime = ctx.getContentResolver().getType(uri);
                    byte[] compressed = compressImageFor2G(ctx, uri, mime);
                    if (compressed != null) {
                        tempFile = new File(ctx.getCacheDir(), "up_" + System.currentTimeMillis() + ".jpg");
                        FileOutputStream fos = new FileOutputStream(tempFile);
                        fos.write(compressed);
                        fos.close();
                        fallbackUri = Uri.fromFile(tempFile);
                    }
                } catch (Throwable ignored) { }
                final File toClean = tempFile;
                Fb.upload(path, fallbackUri).addOnCompleteListener(fb -> {
                    if (toClean != null) { try { toClean.delete(); } catch (Throwable ignored) { } }
                    if (fb.isSuccessful() && fb.getResult() != null)
                        tcs.setResult(fb.getResult().toString());
                    else
                        tcs.setException(fb.getException() != null ? fb.getException()
                                : new Exception("Upload failed"));
                });
            }, "fb-upload-fallback").start();
        });
        return tcs.getTask();
    }
}
