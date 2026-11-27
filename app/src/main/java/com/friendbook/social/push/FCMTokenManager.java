package com.friendbook.social.push;

import android.content.Context;
import com.friendbook.social.core.Fb;
import org.json.JSONObject;

/** Per-install, account-scoped token registration through a Firebase-authenticated gateway. */
public final class FCMTokenManager {
    private static final java.util.concurrent.ExecutorService CLEANUP = java.util.concurrent.Executors.newSingleThreadExecutor();
    private static boolean initialized;
    private static String activeUid;
    public static synchronized void init(Context c) {
        if (initialized) return;
        initialized = true;
        Context app = c.getApplicationContext();
        com.google.firebase.messaging.FirebaseMessaging.getInstance().setAutoInitEnabled(true);
        Fb.auth().addAuthStateListener(a -> {
            String uid = a.getUid();
            PushTrace.stage("auth_state", "signed_in=" + (uid != null) + " actor=" + PushTrace.id(uid));
            if (activeUid != null && !activeUid.equals(uid)) {
                androidx.work.WorkManager.getInstance(app).cancelAllWorkByTag("fcm_user_" + activeUid);
            }
            activeUid = uid;
            if (uid == null) {
                androidx.work.WorkManager.getInstance(app).cancelUniqueWork("fcm_registration_maintenance");
                return;
            }
            sync(app); // Cold start/restored session, sign-up, email login and Google login.
        });
    }
    public static synchronized String deviceId(Context c) {
        android.content.SharedPreferences p = c.getSharedPreferences("fcm_install", 0);
        String id = p.getString("device", "");
        if (id.isEmpty()) { id = java.util.UUID.randomUUID().toString(); p.edit().putString("device", id).commit(); }
        return id;
    }
    public static void sync(Context c) {
        String uid = Fb.uid();
        if (uid == null) return;
        try {
            JSONObject request = new JSONObject().put("action", "register").put("device_id", deviceId(c));
            PushTrace.stage("registration_enqueue", "actor=" + PushTrace.id(uid)
                    + " device=" + PushTrace.id(deviceId(c)));
            PushDeliveryWorker.enqueue(request);
            // Cancel the old repeating job on upgrade. Token refresh and sign-in
            // already register the device; failed registrations have durable retries.
            androidx.work.WorkManager.getInstance(c.getApplicationContext()).cancelUniqueWork("fcm_registration_maintenance");
        } catch (Exception e) {
            android.util.Log.w("FriendBookPush", "Automatic FCM registration could not be queued", e);
        }
    }
    public static void onTokenChanged(Context c) {
        if (Fb.uid() == null) return; // The auth listener obtains the latest token after sign-in.
        try {
            PushDeliveryWorker.enqueue(new JSONObject().put("action", "register")
                    .put("device_id", deviceId(c)), true);
        } catch (Exception e) { android.util.Log.w("FriendBookPush", "Token refresh registration unavailable", e); }
    }
    public static void unregister(Context c) {
        // Capture the old session before the caller signs out; best effort cleanup only.
        com.google.firebase.auth.FirebaseUser user = Fb.auth().getCurrentUser();
        if (user == null) return;
        androidx.work.WorkManager.getInstance(c).cancelAllWorkByTag("fcm_user_" + user.getUid());
        androidx.work.WorkManager.getInstance(c).cancelUniqueWork("fcm_registration_maintenance");
        String id = deviceId(c);
        user.getIdToken(false).addOnSuccessListener(t -> CLEANUP.execute(() -> {
            try { PushGateway.call(new JSONObject().put("action", "unregister").put("device_id", id), t.getToken()); }
            catch (Exception ignored) { /* Local account check rejects any stale delivery after logout. */ }
        }));
        c.getSharedPreferences("push_diagnostics", 0).edit().clear().apply();
    }
}
