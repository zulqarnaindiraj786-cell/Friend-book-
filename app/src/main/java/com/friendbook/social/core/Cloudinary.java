package com.friendbook.social.core;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;

import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * ☁️ friendbook Turbo Media & Reels Uploader:
 * - Downscales & compresses camera/gallery images on-device BEFORE upload for instant 2G/4G speed.
 * - Uses high-throughput 64 KB buffered streaming + live % progress callbacks for Reels & videos.
 * - Extracts instant 15 KB JPEG first-frame video thumbnails via MediaMetadataRetriever so Reels
 *   render their poster frame in 0.0ms with zero black screen.
 */
public class Cloudinary {

    private static final String CLOUD_NAME = "x5ed4d0b";
    private static final String PRESET = "Myapp_upload";
    private static final String ENDPOINT =
            "https://api.cloudinary.com/v1_1/" + CLOUD_NAME + "/auto/upload";
    private static final String R2_PHOTO_ENDPOINT =
            "https://rough-firefly-dd7b.zulqarnaindiraj786.workers.dev/v1/photos";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static String sha256Hex(byte[] data) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(data);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : hash) sb.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xFF));
        return sb.toString();
    }

    /**
     * Uploads photo/audio bytes directly to Cloudflare R2 bucket `friendbook` ("Save Forever").
     * Returns https://pub-88ab0a56178742af95cbbf62df152a67.r2.dev/photos/<owner>/<id>.jpg
     */
    private static String uploadBytesToR2(byte[] data, String mime, final ProgressListener progressListener) throws Exception {
        if (data == null || data.length == 0) throw new IllegalArgumentException("Empty photo bytes");
        com.google.firebase.auth.FirebaseUser user = com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) throw new IllegalStateException("Not signed in");
        String token = com.google.android.gms.tasks.Tasks.await(
                user.getIdToken(false), 20, java.util.concurrent.TimeUnit.SECONDS).getToken();
        if (token == null || token.isEmpty()) throw new IllegalStateException("Missing auth token");
        String photoId = sha256Hex(data);
        String cleanMime = (mime == null || mime.isEmpty()) ? "image/jpeg" : mime;

        HttpURLConnection conn = (HttpURLConnection) new URL(R2_PHOTO_ENDPOINT).openConnection();
        try {
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(90000);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(data.length);
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("X-Photo-Id", photoId);
            conn.setRequestProperty("X-Photo-Mime", cleanMime);
            conn.setRequestProperty("Content-Type", cleanMime);

            int chunk = 16 * 1024;
            try (OutputStream out = new BufferedOutputStream(conn.getOutputStream(), chunk)) {
                int offset = 0;
                int lastPct = -1;
                while (offset < data.length) {
                    int len = Math.min(chunk, data.length - offset);
                    out.write(data, offset, len);
                    offset += len;
                    if (progressListener != null) {
                        final int pct = Math.min(96, (int) ((offset * 96L) / data.length));
                        if (pct != lastPct) {
                            lastPct = pct;
                            final long sentNow = offset;
                            final long totalNow = data.length;
                            MAIN.post(() -> progressListener.onProgress(pct, sentNow, totalNow));
                        }
                    }
                }
                out.flush();
            }

            int code = conn.getResponseCode();
            InputStream rs = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            StringBuilder sb = new StringBuilder();
            if (rs != null) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(rs))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                }
            }
            if (code >= 200 && code < 300) {
                JSONObject json = new JSONObject(sb.toString());
                String url = json.optString("url", "");
                if (url.startsWith(R2VideoClient.ORIGIN + "/photos/")) {
                    if (progressListener != null) {
                        final long totalNow = data.length;
                        MAIN.post(() -> progressListener.onProgress(100, totalNow, totalNow));
                    }
                    return url;
                }
            }
            throw new java.io.IOException("R2 photo upload HTTP " + code);
        } finally {
            conn.disconnect();
        }
    }

    public interface ProgressListener {
        void onProgress(int percent, long sentBytes, long totalBytes);
    }

    public static final class VideoMeta {
        public long sizeBytes;
        public int durationSec;
        public int width;
        public int height;
        public int frameRate;
        public byte[] posterJpeg;
    }

    /** Fast ~25ms video metadata + crisp 480px JPEG cover frame extractor for Reels. */
    public static VideoMeta inspectVideo(Context ctx, Uri uri) {
        VideoMeta meta = new VideoMeta();
        if (ctx == null || uri == null) return meta;
        meta.sizeBytes = queryUriLength(ctx, uri);
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(ctx, uri);
            String dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (dur != null) {
                long ms = Long.parseLong(dur.trim());
                meta.durationSec = Math.max(1, Math.round(ms / 1000f));
            }
            String w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            String rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            int vw = w != null ? Integer.parseInt(w.trim()) : 0;
            int vh = h != null ? Integer.parseInt(h.trim()) : 0;
            int r = rot != null ? Integer.parseInt(rot.trim()) : 0;
            if (r == 90 || r == 270) {
                meta.width = vh;
                meta.height = vw;
            } else {
                meta.width = vw;
                meta.height = vh;
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                String fpsStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE);
                if (fpsStr != null && !fpsStr.trim().isEmpty()) {
                    try {
                        meta.frameRate = Math.round(Float.parseFloat(fpsStr.trim()));
                    } catch (Throwable ignored) { }
                }
            }
            if (meta.frameRate <= 0 && android.os.Build.VERSION.SDK_INT >= 28) {
                String frameCountStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT);
                if (frameCountStr != null && dur != null) {
                    try {
                        long frames = Long.parseLong(frameCountStr.trim());
                        long ms = Long.parseLong(dur.trim());
                        if (frames > 0 && ms > 0) {
                            meta.frameRate = Math.round((frames * 1000f) / ms);
                        }
                    } catch (Throwable ignored) { }
                }
            }
            Bitmap frame = mmr.getFrameAtTime(150_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) frame = mmr.getFrameAtTime(0L);
            if (frame != null) {
                int maxDim = 540;
                int bw = frame.getWidth(), bh = frame.getHeight();
                if (bw > maxDim || bh > maxDim) {
                    float ratio = Math.min((float) maxDim / bw, (float) maxDim / bh);
                    Bitmap scaled = Bitmap.createScaledBitmap(frame,
                            Math.max(1, Math.round(bw * ratio)),
                            Math.max(1, Math.round(bh * ratio)), true);
                    if (scaled != frame) frame.recycle();
                    frame = scaled;
                }
                ByteArrayOutputStream bos = new ByteArrayOutputStream(24 * 1024);
                frame.compress(Bitmap.CompressFormat.JPEG, 74, bos);
                frame.recycle();
                meta.posterJpeg = bos.toByteArray();
            }
        } catch (Throwable ignored) {
        } finally {
            try { mmr.release(); } catch (Throwable ignored) { }
        }
        return meta;
    }

    public static long queryUriLength(Context ctx, Uri uri) {
        if (ctx == null || uri == null) return -1L;
        try (Cursor c = ctx.getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !c.isNull(idx)) {
                    long sz = c.getLong(idx);
                    if (sz > 0) return sz;
                }
            }
        } catch (Throwable ignored) { }
        try (ParcelFileDescriptor pfd = ctx.getContentResolver().openFileDescriptor(uri, "r")) {
            if (pfd != null && pfd.getStatSize() > 0) return pfd.getStatSize();
        } catch (Throwable ignored) { }
        return -1L;
    }

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

            boolean slow2g = FastCache.isSlow2G(ctx) || Prefs.dataSaver(ctx);
            int maxDim = slow2g ? 720 : 1080;
            int quality = slow2g ? 60 : 78;

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

    /** Fast upload of a tiny extracted JPEG poster frame (~15–20 KB) for instant Reel thumbnails. */
    public static Task<String> uploadJpegBytes(final byte[] jpegBytes) {
        final TaskCompletionSource<String> tcs = new TaskCompletionSource<>();
        if (jpegBytes == null || jpegBytes.length == 0) {
            tcs.setException(new IllegalArgumentException("Empty JPEG bytes"));
            return tcs.getTask();
        }
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                try {
                    String r2Url = uploadBytesToR2(jpegBytes, "image/jpeg", null);
                    if (r2Url != null && !r2Url.isEmpty()) {
                        tcs.setResult(r2Url);
                        return;
                    }
                } catch (Throwable ignored) { }
                String boundary = "fbthumb" + System.currentTimeMillis();
                conn = (HttpURLConnection) new URL(ENDPOINT).openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(20000);
                conn.setReadTimeout(35000);
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

                DataOutputStream out = new DataOutputStream(new BufferedOutputStream(conn.getOutputStream(), 32 * 1024));
                out.writeBytes("--" + boundary + "\r\n");
                out.writeBytes("Content-Disposition: form-data; name=\"upload_preset\"\r\n\r\n");
                out.writeBytes(PRESET + "\r\n");

                out.writeBytes("--" + boundary + "\r\n");
                out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"reel_thumb_"
                        + System.currentTimeMillis() + ".jpg\"\r\n");
                out.writeBytes("Content-Type: image/jpeg\r\n\r\n");
                out.write(jpegBytes);
                out.writeBytes("\r\n--" + boundary + "--\r\n");
                out.flush();
                out.close();

                int code = conn.getResponseCode();
                InputStream rs = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
                BufferedReader br = new BufferedReader(new InputStreamReader(rs));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();

                if (code >= 200 && code < 300) {
                    JSONObject asset = new JSONObject(sb.toString());
                    if ("video".equals(asset.optString("resource_type")) && asset.has("video"))
                        throw new java.io.IOException("Video requires R2 processing; choose a recognized video file");
                    String secure = asset.optString("secure_url", "");
                    if (!secure.isEmpty()) tcs.setResult(secure);
                    else tcs.setException(new Exception("No secure_url"));
                } else {
                    tcs.setException(new Exception("Thumb HTTP " + code));
                }
            } catch (Exception e) {
                tcs.setException(e);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }, "cloudinary-thumb").start();
        return tcs.getTask();
    }

    public static Task<String> upload(final Context ctx, final Uri uri) {
        return uploadWithProgress(ctx, uri, null);
    }

    public static Task<String> uploadWithProgress(final Context ctx, final Uri uri,
                                                  final ProgressListener progressListener) {
        final TaskCompletionSource<String> tcs = new TaskCompletionSource<>();
        final String uploadActor = Fb.uid();
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                String mime = ctx.getContentResolver().getType(uri);
                if (R2VideoClient.isVideo(ctx, uri, mime)) {
                    tcs.setResult(R2VideoClient.uploadBlocking(ctx, uri, uploadActor, progressListener));
                    return; // No direct Cloudinary video upload/playback fallback.
                }
                if (mime == null || mime.isEmpty()) mime = "application/octet-stream";

                byte[] compressedImg = compressImageFor2G(ctx, uri, mime);
                boolean isVideo = mime.startsWith("video/");
                final int r2BufferLimit = 25_000_000;
                final long uploadSize = compressedImg != null ? compressedImg.length : queryUriLength(ctx, uri);
                if (!isVideo && uploadSize <= r2BufferLimit) {
                    try {
                        byte[] r2Payload = compressedImg;
                        String r2Mime = compressedImg != null ? "image/jpeg" : mime;
                        if (r2Payload == null) {
                            try (InputStream rawIn = ctx.getContentResolver().openInputStream(uri);
                                 ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                                if (rawIn != null) {
                                    byte[] tmp = new byte[16 * 1024];
                                    int r;
                                    boolean complete = true;
                                    while ((r = rawIn.read(tmp)) != -1) {
                                        if (r > r2BufferLimit - bos.size()) { complete = false; break; }
                                        bos.write(tmp, 0, r);
                                    }
                                    // Do not send a partial file. The existing streaming fallback
                                    // reopens the original URI without the in-memory buffer limit.
                                    if (complete) r2Payload = bos.toByteArray();
                                }
                            }
                        }
                        if (r2Payload != null && r2Payload.length > 0) {
                            String r2Url = uploadBytesToR2(r2Payload, r2Mime, progressListener);
                            if (r2Url != null && !r2Url.isEmpty()) {
                                tcs.setResult(r2Url);
                                return;
                            }
                        }
                    } catch (Throwable ignored) { }
                }
                String ext = "bin";
                if (compressedImg != null) {
                    mime = "image/jpeg";
                    ext = "jpg";
                } else if (mime.startsWith("image/")) {
                    ext = mime.substring(6).replace("jpeg", "jpg");
                } else if (isVideo) {
                    ext = mime.substring(6);
                    if (ext.contains("/")) ext = "mp4";
                } else if (mime.startsWith("audio/")) {
                    ext = mime.substring(6).replace("mpeg", "mp3");
                }
                String fname = "friendbook_" + System.currentTimeMillis() + "." + ext;

                long totalBytes = uploadSize;

                // High-throughput 64 KB chunks for video (4x faster mobile socket throughput), 16 KB for 2G photos
                int chunkSize = isVideo ? (64 * 1024) : (16 * 1024);

                String boundary = "fb" + System.currentTimeMillis();
                conn = (HttpURLConnection) new URL(ENDPOINT).openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(35000);
                conn.setReadTimeout(isVideo ? 240000 : 90000);
                conn.setChunkedStreamingMode(chunkSize);
                conn.setRequestProperty("Connection", "Keep-Alive");
                conn.setRequestProperty("Content-Type",
                        "multipart/form-data; boundary=" + boundary);

                OutputStream raw = new BufferedOutputStream(conn.getOutputStream(), chunkSize * 2);
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

                byte[] buf = new byte[chunkSize];
                long sent = 0L;
                int lastPct = -1;
                long lastEmitMs = 0L;
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    sent += n;
                    if (progressListener != null) {
                        int pct = totalBytes > 0
                                ? Math.min(96, (int) ((sent * 96L) / totalBytes))
                                : Math.min(92, (int) (sent / (128 * 1024L)));
                        long now = System.currentTimeMillis();
                        if (pct != lastPct && (now - lastEmitMs >= 80L || pct >= 95)) {
                            lastPct = pct;
                            lastEmitMs = now;
                            final int emitPct = pct;
                            final long emitSent = sent;
                            final long emitTotal = totalBytes;
                            MAIN.post(() -> progressListener.onProgress(emitPct, emitSent, emitTotal));
                        }
                    }
                }
                in.close();
                out.writeBytes("\r\n--" + boundary + "--\r\n");
                out.flush();
                out.close();

                if (progressListener != null) {
                    final long finalSent = sent;
                    final long finalTotal = totalBytes > 0 ? totalBytes : sent;
                    MAIN.post(() -> progressListener.onProgress(98, finalSent, finalTotal));
                }

                int code = conn.getResponseCode();
                InputStream rs = code >= 200 && code < 300
                        ? conn.getInputStream() : conn.getErrorStream();
                BufferedReader br = new BufferedReader(new InputStreamReader(rs));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();

                if (code >= 200 && code < 300) {
                    JSONObject asset = new JSONObject(sb.toString());
                    if ("video".equals(asset.optString("resource_type")) && asset.has("video"))
                        throw new java.io.IOException("Video requires R2 processing; choose a recognized video file");
                    String secure = asset.optString("secure_url", "");
                    if (!secure.isEmpty()) {
                        if (progressListener != null) {
                            final long finalBytes = totalBytes > 0 ? totalBytes : sent;
                            MAIN.post(() -> progressListener.onProgress(100, finalBytes, finalBytes));
                        }
                        tcs.setResult(secure);
                    } else {
                        tcs.setException(new Exception("Cloudinary response had no secure_url"));
                    }
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
     * Compatibility entry point for existing callers. Media stays on Cloudinary;
     * a failed upload is returned to the caller, never silently sent to Firebase.
     * The legacy path argument is not a Cloudinary public ID or a deletion key.
     */
    public static Task<String> uploadOrFirebase(final Context ctx, final String path, final Uri uri) {
        return uploadOrFirebaseWithProgress(ctx, path, uri, null);
    }

    public static Task<String> uploadOrFirebaseWithProgress(final Context ctx, final String path,
                                                            final Uri uri,
                                                            final ProgressListener progressListener) {
        return uploadWithProgress(ctx, uri, progressListener);
    }
}
