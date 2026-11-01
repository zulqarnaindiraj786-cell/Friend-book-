package com.friendbook.social.push;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import com.onesignal.Continue;
import com.onesignal.OneSignal;

/** One install-scoped prompt. OneSignal delegates to Android's native permission dialog. */
public final class NotificationPermissionPrompt {
    private static final String PREFS = "notification_permission_install";
    private static final String ATTEMPTED = "prompt_attempted";

    private NotificationPermissionPrompt() { }

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
            OneSignal.getNotifications().requestPermission(true, Continue.<Boolean>with(result -> {
                boolean granted = result.isSuccess() && Boolean.TRUE.equals(result.getData());
                prefs.edit().putBoolean("last_granted", granted).apply();
                // Denial/cancellation/error is a valid outcome. Never loop, reopen settings, or block use.
                android.util.Log.i("FriendBookPush", granted
                        ? "Notification permission allowed" : "Notification permission denied or unavailable");
            }));
        } catch (RuntimeException e) {
            android.util.Log.w("FriendBookPush", "Notification permission request unavailable", e);
        }
    }
}
