package com.friendbook.social.push;

import android.content.Context;
import com.friendbook.social.core.Fb;
import org.json.JSONObject;

/** Per-install, account-scoped token registration through a Firebase-authenticated gateway. */
public final class FCMTokenManager {
    private static final java.util.concurrent.ExecutorService CLEANUP = java.util.concurrent.Executors.newSingleThreadExecutor();
    public static void init(Context c) { Fb.auth().addAuthStateListener(a -> sync(c)); }
    public static synchronized String deviceId(Context c) {
        android.content.SharedPreferences p = c.getSharedPreferences("fcm_install", 0);
        String id = p.getString("device", "");
        if (id.isEmpty()) { id = java.util.UUID.randomUUID().toString(); p.edit().putString("device", id).commit(); }
        return id;
    }
    public static void sync(Context c) {
        if (Fb.uid() == null) return;
        try { PushDeliveryWorker.enqueue(new JSONObject().put("action", "register").put("device_id", deviceId(c))); }
        catch (Exception ignored) { }
    }
    public static void unregister(Context c) {
        // Capture the old session before the caller signs out; best effort cleanup only.
        com.google.firebase.auth.FirebaseUser user = Fb.auth().getCurrentUser();
        if (user == null) return;
        androidx.work.WorkManager.getInstance(c).cancelAllWorkByTag("fcm_user_" + user.getUid());
        String id = deviceId(c);
        user.getIdToken(false).addOnSuccessListener(t -> CLEANUP.execute(() -> {
            try { PushGateway.call(new JSONObject().put("action", "unregister").put("device_id", id), t.getToken()); }
            catch (Exception ignored) { /* Local account check rejects any stale delivery after logout. */ }
        }));
        c.getSharedPreferences("push_diagnostics", 0).edit().clear().apply();
    }
}
