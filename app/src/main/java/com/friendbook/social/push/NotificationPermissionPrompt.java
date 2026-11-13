package com.friendbook.social.push;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;


/** One install-scoped Android 13+ native notification prompt. */
public final class NotificationPermissionPrompt {
    private static final String PREFS = "notification_permission_install";
    private static final String ATTEMPTED = "prompt_attempted";

    private NotificationPermissionPrompt() { }

    public static void onResult(Activity activity, int requestCode, int[] results) {
        if (requestCode != 4401) return;
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("last_granted", granted).apply();
        android.util.Log.i("FriendBookPush", granted ? "Notification permission allowed" : "Notification permission denied");
        if (granted) FCMTokenManager.sync(activity);
    }

    public static void requestOnce(Activity activity) {
        if (Build.VERSION.SDK_INT < 33 || activity.isFinishing() || activity.isDestroyed()
                || !activity.hasWindowFocus()) return;
        Context app = activity.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            prefs.edit().putBoolean("last_granted", true).apply();
            return;
        }
        // Commit BEFORE showing the dialog so recreation/process death cannot prompt again.
        // This dedicated file is excluded from backup/transfer: reinstall starts a new prompt cycle.
        if (prefs.getBoolean(ATTEMPTED, false)) return;
        if (!prefs.edit().putBoolean(ATTEMPTED, true).putBoolean("last_granted", false).commit()) {
            android.util.Log.w("FriendBookPush", "Permission prompt state could not be saved");
            return;
        }
        try {
            activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 4401);
        } catch (RuntimeException e) {
            android.util.Log.w("FriendBookPush", "Notification permission request unavailable", e);
        }
    }
}
