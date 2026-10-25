package com.example.frienbook.core;

import android.content.Context;
import android.net.Uri;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * ☁️ Cloudinary media uploads — unsigned preset, image + video + audio via /auto/upload.
 * No SDK needed: plain multipart POST on a background thread, returns the secure_url.
 * If Cloudinary is unreachable the caller should fall back to Firebase Storage (Fb.upload).
 */
public class Cloudinary {

    // ---- real config (unsigned upload preset) ----
    private static final String CLOUD_NAME = "x5ed4d0b";
    private static final String PRESET = "Myapp_upload";
    private static final String ENDPOINT =
            "https://api.cloudinary.com/v1_1/" + CLOUD_NAME + "/auto/upload";

    /**
     * Upload a local media Uri (image / video / voice) to Cloudinary.
     * Returns a Task<String> whose result is the https secure_url to save in Firestore.
     */
    public static Task<String> upload(final Context ctx, final Uri uri) {
        final TaskCompletionSource<String> tcs = new TaskCompletionSource<>();
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                String mime = ctx.getContentResolver().getType(uri);
                if (mime == null || mime.isEmpty()) mime = "application/octet-stream";
                String ext = "bin";
                if (mime.startsWith("image/")) ext = mime.substring(6).replace("jpeg", "jpg");
                else if (mime.startsWith("video/")) ext = mime.substring(6);
                else if (mime.startsWith("audio/")) ext = mime.substring(6).replace("mpeg", "mp3");
                String fname = "friendbook_" + System.currentTimeMillis() + "." + ext;

                String boundary = "fb" + System.currentTimeMillis();
                conn = (HttpURLConnection) new URL(ENDPOINT).openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(20000);
                conn.setReadTimeout(60000);
                conn.setChunkedStreamingMode(0);
                conn.setRequestProperty("Content-Type",
                        "multipart/form-data; boundary=" + boundary);

                OutputStream raw = conn.getOutputStream();
                DataOutputStream out = new DataOutputStream(raw);

                // upload_preset part
                out.writeBytes("--" + boundary + "\r\n");
                out.writeBytes("Content-Disposition: form-data; name=\"upload_preset\"\r\n\r\n");
                out.writeBytes(PRESET + "\r\n");

                // file part
                out.writeBytes("--" + boundary + "\r\n");
                out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\""
                        + fname + "\"\r\n");
                out.writeBytes("Content-Type: " + mime + "\r\n\r\n");
                InputStream in = ctx.getContentResolver().openInputStream(uri);
                if (in == null) throw new Exception("Cannot read the selected file");
                byte[] buf = new byte[64 * 1024];
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
     * Upload to Cloudinary, and if that fails (network/config) fall back to
     * Firebase Storage — media uploads should NEVER fail silently.
     */
    public static Task<String> uploadOrFirebase(final Context ctx, final String path, final Uri uri) {
        final TaskCompletionSource<String> tcs = new TaskCompletionSource<>();
        upload(ctx, uri).addOnCompleteListener(t -> {
            if (t.isSuccessful()) { tcs.setResult(t.getResult()); return; }
            android.util.Log.w("Cloudinary", "cloudinary failed, trying Firebase: "
                    + t.getException());
            Fb.upload(path, uri).addOnCompleteListener(fb -> {
                if (fb.isSuccessful() && fb.getResult() != null)
                    tcs.setResult(fb.getResult().toString());
                else
                    tcs.setException(fb.getException() != null ? fb.getException()
                            : new Exception("Upload failed"));
            });
        });
        return tcs.getTask();
    }
}
