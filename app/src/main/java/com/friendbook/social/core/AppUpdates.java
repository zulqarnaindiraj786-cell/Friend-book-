package com.friendbook.social.core;

import android.app.Activity;

import com.friendbook.social.BuildConfig;
import com.google.android.play.core.appupdate.AppUpdateManager;
import com.google.android.play.core.appupdate.AppUpdateManagerFactory;
import com.google.android.play.core.appupdate.AppUpdateOptions;
import com.google.android.play.core.install.model.AppUpdateType;
import com.google.android.play.core.install.model.UpdateAvailability;

/**
 * Update entry point used by the settings screen and by app launch.
 *
 * <p>There is exactly one update channel: the Play Store. The previous implementation
 * downloaded an APK from a GitHub release, which was both a security problem (a sideloaded
 * binary is not the artifact Play reviewers signed off on) and a support problem (users ended
 * up on a build with no Play update state, so they could never be moved forward again). Both
 * are gone: the only action offered anywhere is the Play Store listing.
 */
public final class AppUpdates {

    private AppUpdates() {
    }

    /**
     * Checks Play for a newer build.
     *
     * @param manual true when the user tapped a menu item, so we explain a no-op result
     */
    public static void check(final Activity activity, final boolean manual) {
        if (activity == null || activity.isFinishing()) return;

        // A build below the floor is blocked outright; the blocking screen is the answer.
        if (ForceUpdate.isBlocked(activity)) {
            ForceUpdate.recheck(activity);
            return;
        }

        try {
            final AppUpdateManager manager = AppUpdateManagerFactory.create(activity);
            manager.getAppUpdateInfo().addOnSuccessListener(info -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                boolean available = info != null
                        && info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE;
                if (available && info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
                    try {
                        manager.startUpdateFlow(info, activity,
                                AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build());
                        return;
                    } catch (Throwable ignored) {
                        // Fall through to the listing.
                    }
                }
                if (available) {
                    ForceUpdate.openPlayStore(activity);
                } else if (manual) {
                    Ui.toast(activity, "Aap ke paas latest version hai (" + BuildConfig.VERSION_NAME + ").");
                }
            }).addOnFailureListener(error -> {
                // Sideloaded builds have no Play state; send them to the listing rather than
                // claiming they are current.
                if (manual && !activity.isFinishing()) ForceUpdate.openPlayStore(activity);
            });
        } catch (Throwable missingPlayServices) {
            if (manual) {
                Ui.toast(activity, "Play Store se app update karo.");
                ForceUpdate.openPlayStore(activity);
            }
        }
    }
}
