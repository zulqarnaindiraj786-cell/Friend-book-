package com.friendbook.social.core;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.friendbook.social.BuildConfig;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FieldValue;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Cloudflare D1 App Document Store client (/v1/docs) for updated APK clients.
 * Preserves all historical data in D1 while writing new/updated user records to Cloudflare.
 */
public final class CloudflareStore {
    private CloudflareStore() {}

    private static final String BASE = "https://rough-firefly-dd7b.zulqarnaindiraj786.workers.dev/v1/docs";
    private static final ExecutorService IO = Executors.newCachedThreadPool();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static boolean enabled() {
        return BuildConfig.D1_INTERACTIONS_ENABLED;
    }

    public static JSONObject toJson(Map<String, Object> map) {
        JSONObject out = new JSONObject();
        if (map == null) return out;
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Object> e : map.entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            if (k == null || v == null) continue;
            try {
                if (v instanceof FieldValue) {
                    if (k.toLowerCase().contains("at") || k.toLowerCase().contains("time") || k.toLowerCase().contains("seen")) {
                        out.put(k, now);
                    }
                } else if (v instanceof List) {
                    JSONArray arr = new JSONArray();
                    for (Object item : (List<?>) v) {
                        if (item instanceof Map) {
                            //noinspection unchecked
                            arr.put(toJson((Map<String, Object>) item));
                        } else {
                            arr.put(item);
                        }
                    }
                    out.put(k, arr);
                } else if (v instanceof Map) {
                    //noinspection unchecked
                    out.put(k, toJson((Map<String, Object>) v));
                } else if (v instanceof Boolean || v instanceof Number || v instanceof String) {
                    out.put(k, v);
                }
            } catch (JSONException ignored) { }
        }
        return out;
    }

    public static void put(String collection, String docId, Map<String, Object> fields) {
        put(collection, docId, fields, null);
    }

    public static void put(String collection, String docId, Map<String, Object> fields, Runnable onSuccess) {
        if (!enabled() || collection == null || docId == null || docId.isEmpty() || fields == null) return;
        final JSONObject body = toJson(fields);
        try {
            FirebaseUser user = Fb.auth().getCurrentUser();
            if (user == null) return;
            user.getIdToken(false).addOnSuccessListener(res -> {
                String token = res != null ? res.getToken() : null;
                if (token == null || token.isEmpty()) return;
                IO.execute(() -> {
                    try {
                        URL url = new URL(BASE + "/" + Uri.encode(collection) + "/" + Uri.encode(docId));
                        D1HttpTransport.send(url, "PUT", token, body);
                        if (onSuccess != null) MAIN.post(onSuccess);
                    } catch (Throwable ignored) { }
                });
            });
        } catch (Throwable ignored) { }
    }

    public static void list(String collection, int limit, Consumer<List<Map<String, Object>>> callback) {
        if (!enabled() || collection == null || callback == null) return;
        try {
            FirebaseUser user = Fb.auth().getCurrentUser();
            if (user == null) return;
            user.getIdToken(false).addOnSuccessListener(res -> {
                String token = res != null ? res.getToken() : null;
                if (token == null || token.isEmpty()) return;
                IO.execute(() -> {
                    try {
                        URL url = new URL(BASE + "?collection=" + Uri.encode(collection) + "&limit=" + Math.max(1, Math.min(50, limit)));
                        JSONObject data = D1HttpTransport.send(url, "GET", token, null);
                        JSONArray arr = data.optJSONArray("documents");
                        List<Map<String, Object>> docs = arr != null ? D1Client.maps(arr) : new ArrayList<>();
                        MAIN.post(() -> callback.accept(docs));
                    } catch (Throwable ignored) { }
                });
            });
        } catch (Throwable ignored) { }
    }
}
