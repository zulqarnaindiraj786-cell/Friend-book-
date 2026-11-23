package com.friendbook.social.core;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.friendbook.social.BuildConfig;
import com.google.firebase.auth.FirebaseUser;

import org.json.JSONArray;
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
 * Opportunistic authenticated list reads; authoritative writes use D1DocumentStore.
 */
public final class CloudflareStore {
    private CloudflareStore() {}

    private static final String BASE = "https://rough-firefly-dd7b.zulqarnaindiraj786.workers.dev/v1/docs";
    private static final ExecutorService IO = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static boolean enabled() {
        return BuildConfig.D1_INTERACTIONS_ENABLED;
    }

    public static void list(String collection, int limit, Consumer<List<Map<String, Object>>> callback) {
        if (callback == null) return;
        if (!enabled() || collection == null) {
            MAIN.post(() -> callback.accept(new ArrayList<>()));
            return;
        }
        final int safeLimit = Math.max(1, Math.min(50, limit));
        // List responses include visibility-filtered content. Reauthorize on every
        // request rather than replaying a five-minute snapshot after a block or deletion.
        try {
            FirebaseUser user = Fb.auth().getCurrentUser();
            if (user == null) { MAIN.post(() -> callback.accept(new ArrayList<>())); return; }
            final String actor=user.getUid();
            user.getIdToken(false).addOnSuccessListener(res -> {
                String token = res != null ? res.getToken() : null;
                if (token == null || token.isEmpty()) { MAIN.post(() -> { if (actor.equals(Fb.uid())) callback.accept(new ArrayList<>()); }); return; }
                IO.execute(() -> {
                    try {
                        if (!actor.equals(Fb.uid())) return;
                        URL url = new URL(BASE + "?collection=" + Uri.encode(collection) + "&limit=" + safeLimit);
                        JSONObject data = D1HttpTransport.send(url, "GET", token, null);
                        JSONArray arr = data.optJSONArray("documents");
                        List<Map<String, Object>> docs = arr != null ? D1Client.maps(arr) : new ArrayList<>();
                        MAIN.post(() -> { if (actor.equals(Fb.uid())) callback.accept(docs); });
                    } catch (Throwable ignored) {
                        MAIN.post(() -> { if (actor.equals(Fb.uid())) callback.accept(new ArrayList<>()); });
                    }
                });
            }).addOnFailureListener(error -> MAIN.post(() -> { if (actor.equals(Fb.uid())) callback.accept(new ArrayList<>()); }));
        } catch (Throwable ignored) {
            MAIN.post(() -> callback.accept(new ArrayList<>()));
        }
    }
}
