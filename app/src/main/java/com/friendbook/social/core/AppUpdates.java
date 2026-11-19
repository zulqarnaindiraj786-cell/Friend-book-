package com.friendbook.social.core;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import com.friendbook.social.BuildConfig;
import com.google.android.play.core.appupdate.AppUpdateManagerFactory;
import com.google.android.play.core.install.model.UpdateAvailability;

/** Requires an update only after Google Play confirms it is available to this installation. */
public final class AppUpdates {
    static final String STORE = AdsConfig.PLAY_STORE_URL;
    private AlertDialog gate;

    public void onResume(Activity activity) {
        AppUpdateManagerFactory.create(activity).getAppUpdateInfo().addOnSuccessListener(activity, info -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            if (requiresUpdate(info.updateAvailability(), info.availableVersionCode())) {
                if (gate == null || !gate.isShowing()) gate = showRequired(activity);
            } else if (gate != null && info.updateAvailability() == UpdateAvailability.UPDATE_NOT_AVAILABLE) {
                dismiss();
            }
        });
        // Offline/unsupported installs must still be able to launch; an existing gate stays up.
    }

    static boolean requiresUpdate(int availability, int availableVersion) {
        return availability == UpdateAvailability.UPDATE_AVAILABLE && availableVersion > BuildConfig.VERSION_CODE;
    }

    static AlertDialog showRequired(Activity activity) {
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Update required")
                .setMessage("Install the latest FriendBook version from Google Play to continue.")
                .setCancelable(false)
                .setPositiveButton("Update on Google Play", null).show();
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
