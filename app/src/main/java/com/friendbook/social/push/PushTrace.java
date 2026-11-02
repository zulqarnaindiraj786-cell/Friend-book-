package com.friendbook.social.push;

import android.util.Log;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONObject;

/** Stage-only diagnostics: never pass tokens, bearer headers, names or message text here. */
public final class PushTrace {
    private PushTrace() {}
    public static String id(String value) {
        if (value == null || value.isEmpty()) return "missing";
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < 6; i++) result.append(String.format(java.util.Locale.ROOT, "%02x", bytes[i] & 255));
            return result.toString();
        } catch (Exception ignored) { return "unavailable"; }
    }
    public static String label(String value) {
        if (value == null) return "missing";
        String safe = value.replaceAll("[^A-Za-z0-9_:-]", "_");
        return safe.substring(0, Math.min(80, safe.length()));
    }
    public static void stage(String stage, String fields) { Log.d("FriendBookPush", stage + " " + fields); }
    public static void failure(String stage, Exception error) {
        String code = error instanceof com.google.firebase.firestore.FirebaseFirestoreException
                ? ((com.google.firebase.firestore.FirebaseFirestoreException) error).getCode().name()
                : error.getClass().getSimpleName();
        Log.w("FriendBookPush", stage + " code=" + label(code));
    }
    public static String request(JSONObject body) {
        JSONObject data = body.optJSONObject("data");
        return "action=" + label(body.optString("action"))
                + " receiver=" + id(body.optString("target_user_id"))
                + " event=" + id(data == null ? null : data.optString("notificationId", data.optString("messageId")))
                + " type=" + label(data == null ? null : data.optString("type"));
    }
}
