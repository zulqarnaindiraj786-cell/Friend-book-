package com.friendbook.social.core;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import com.friendbook.social.BuildConfig;
import com.google.android.play.core.appupdate.AppUpdateManagerFactory;
import com.google.android.play.core.install.model.UpdateAvailability;

/** Shows an optional update prompt only after Google Play confirms a newer build is available. */
public final class AppUpdates {
    static final String STORE = AdsConfig.PLAY_STORE_URL;
    private AlertDialog gate;
    private static final long PROMPT_COOLDOWN_MS = 24L * 60L * 60L * 1000L;

    public void onResume(Activity activity) {
        AppUpdateManagerFactory.create(activity).getAppUpdateInfo().addOnSuccessListener(activity, info -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            if (requiresUpdate(info.updateAvailability(), info.availableVersionCode())) {
                boolean belowMinimum = Ui.appVersionCode(activity) < CloudAppConfig.minimumVersionCode(activity);
                long now = System.currentTimeMillis();
                android.content.SharedPreferences prefs = activity.getSharedPreferences("app_update_prompt", Activity.MODE_PRIVATE);
                long lastPromptAtMs = prefs.getLong("last_prompt_at", 0L);
                if ((gate == null || !gate.isShowing()) && now - lastPromptAtMs >= PROMPT_COOLDOWN_MS) {
                    prefs.edit().putLong("last_prompt_at", now).apply();
                    gate = showAvailable(activity, belowMinimum);
                }
            } else if (gate != null && info.updateAvailability() == UpdateAvailability.UPDATE_NOT_AVAILABLE) {
                dismiss();
            }
        });
        // Offline/unsupported installs must still be able to launch; an existing gate stays up.
    }

    static boolean requiresUpdate(int availability, int availableVersion) {
        return availability == UpdateAvailability.UPDATE_AVAILABLE && availableVersion > BuildConfig.VERSION_CODE;
    }

    static AlertDialog showAvailable(Activity activity, boolean belowMinimum) {
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(belowMinimum ? "Update recommended" : "Update available")
                .setMessage(belowMinimum
                        ? "Your version is below FriendBook's current minimum. Please update when you can; access is not blocked."
                        : "A newer FriendBook version is available with improvements and fixes.")
                .setCancelable(true)
                .setNegativeButton("Later", (d, which) -> d.dismiss())
                .setPositiveButton("Update", null).show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> openStore(activity));
        return dialog;
    }

    public void dismiss() {
        if (gate != null) { gate.dismiss(); gate = null; }
    }

    public static void openStore(Activity activity) {
        Intent listing = new Intent(Intent.ACTION_VIEW, Uri.parse(STORE));
        try { activity.startActivity(new Intent(listing).setPackage("com.android.vending")); }
        catch (android.content.ActivityNotFoundException noStore) {
            try { activity.startActivity(listing); }
            catch (android.content.ActivityNotFoundException noBrowser) { Ui.toast(activity, "Couldn't open Google Play."); }
        }
    }
}
