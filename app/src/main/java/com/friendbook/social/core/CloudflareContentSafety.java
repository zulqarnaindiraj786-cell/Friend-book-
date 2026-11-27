package com.friendbook.social.core;

import android.graphics.Bitmap;
import android.util.Base64;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Response;

/** Authenticated Cloudflare Workers AI moderation for upload-time image/video frames. */
final class CloudflareContentSafety {
    private CloudflareContentSafety() {}

    static ContentSafetyEngine.Verdict scan(List<Bitmap> frames) {
        if (frames == null || frames.isEmpty() || frames.size() > 4)
            return ContentSafetyEngine.Verdict.block("scan_unavailable", "Media could not be verified. Please try again.");
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return ContentSafetyEngine.Verdict.block("scan_unavailable", "Sign in to verify media before upload.");
        try {
            String uid = user.getUid();
            String token = Tasks.await(user.getIdToken(false), 15, TimeUnit.SECONDS).getToken();
            if (token == null || !uid.equals(FirebaseAuth.getInstance().getUid()))
                return ContentSafetyEngine.Verdict.block("scan_unavailable", "Could not verify media. Please sign in again.");
            JSONArray images = new JSONArray();
            for (Bitmap source : frames) {
                if (source == null || source.isRecycled())
                    return ContentSafetyEngine.Verdict.block("scan_unavailable", "Could not inspect media safely.");
                Bitmap scaled = Bitmap.createScaledBitmap(source, Math.min(256, source.getWidth()), Math.min(256, source.getHeight()), true);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try {
                    if (!scaled.compress(Bitmap.CompressFormat.JPEG, 72, bytes) || bytes.size() > 180_000)
                        return ContentSafetyEngine.Verdict.block("scan_unavailable", "Could not prepare media for safety verification.");
                    images.put("data:image/jpeg;base64," + Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP));
                } finally {
                    if (scaled != source) scaled.recycle();
                }
            }
            JSONObject body = new JSONObject().put("images", images);
            try (Response response = CloudflareHttp.openDocument("/v1/safety/scan", "POST", token,
                    body.toString().getBytes(StandardCharsets.UTF_8))) {
                String text = CloudflareHttp.readUtf8(response.body(), 32 * 1024);
                if (!response.isSuccessful())
                    return ContentSafetyEngine.Verdict.block("scan_unavailable", "Cloudflare safety check is unavailable. Upload paused; try again later.");
                JSONObject result = new JSONObject(text);
                if (result.optBoolean("blocked", true))
                    return ContentSafetyEngine.Verdict.block(result.optString("category", "sexual_nudity"),
                            "Upload blocked by Cloudflare AI safety review.");
                return ContentSafetyEngine.Verdict.safe();
            }
        } catch (Throwable ignored) {
            return ContentSafetyEngine.Verdict.block("scan_unavailable", "Cloudflare safety check failed. Upload paused; try again later.");
        }
    }
}
