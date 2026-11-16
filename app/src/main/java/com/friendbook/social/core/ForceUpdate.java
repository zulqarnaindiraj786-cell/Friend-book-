package com.friendbook.social.core;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;

import com.friendbook.social.BuildConfig;
import com.google.android.play.core.appupdate.AppUpdateManager;
import com.google.android.play.core.appupdate.AppUpdateManagerFactory;
import com.google.android.play.core.appupdate.AppUpdateOptions;
import com.google.android.play.core.install.model.AppUpdateType;
import com.google.android.play.core.install.model.UpdateAvailability;

/**
 * Blocks a launch that is too old to talk to the current backend.
 *
 * <p>Two layers, because neither alone is sufficient:
 *
 * <ol>
 *   <li>A local gate on the minimum supported version code. It is evaluated
 *       <b>synchronously</b> at launch against a value cached from the release policy, so the
 *       decision never waits on the network and a dead network can never be mistaken for
 *       "you are up to date".</li>
 *   <li>The Play In-App Update API in <b>Immediate</b> mode. Play downloads and installs the
 *       update for us and restarts the app. This is the only path that actually replaces the
 *       build, and it only works for installs that came from Play.</li>
 * </ol>
 *
 * <p>There is deliberately no skip, no "later", no back-button escape and no browser or GitHub
 * link. The only action offered is the Play Store listing.
 */
public final class ForceUpdate {

    /** First version allowed to run against the current backend: v2.9.0. */
    public static final int MIN_SUPPORTED_VERSION_CODE = 126;

    private static final String TITLE = "Nayi Update Aayi Hai";
    private static final String BODY =
            "App Update Karo Warna App Nahi Chalegi."
                    + "\n\nPurani version ab kaam nahi karegi. Play Store se nayi version install karo,"
                    + " phir app khud khul jayegi."
                    + "\n\nApp ko uninstall mat karo - aap ka data safe hai.";

    private static final String POLICY =
            "https://rough-firefly-dd7b.zulqarnaindiraj786.workers.dev/v1/android-release";

    private static final String PREFS = "force_update";
    private static final String KEY_MIN_REMOTE = "min_version";
    private static final String KEY_CHECKED_AT = "checked_at";
    private static final long CHECK_WINDOW_MS = 60 * 60 * 1000L;

    private static boolean blocking;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ForceUpdate() {
    }

    /** The requirement this build must satisfy: the larger of our constant and the policy. */
    private static int required(SharedPreferences prefs) {
        return Math.max(MIN_SUPPORTED_VERSION_CODE, prefs.getInt(KEY_MIN_REMOTE, 0));
    }

    /**
     * The launch gate. Shows the blocking dialog and returns false when this build may not run;
     * otherwise runs {@code proceed} and refreshes the policy in the background.
     *
     * @return true when the caller may continue
     */
    public static boolean gate(final Activity activity, final Runnable proceed) {
        if (activity == null || activity.isFinishing()) return false;
        final SharedPreferences prefs = activity.getSharedPreferences(PREFS, 0);

        if (BuildConfig.VERSION_CODE < required(prefs)) {
            showBlocking(activity, prefs);
            refreshPolicy(activity, prefs);
            return false;
        }

        if (proceed != null) proceed.run();
        refreshPolicy(activity, prefs);
        startImmediateUpdate(activity);
        return true;
    }

    /**
     * Clears the "a dialog is already up" latch. Test-only: the latch exists so a second call
     * cannot stack dialogues, and unit tests share one JVM across cases.
     */
    static void resetGateForTests() {
        blocking = false;
    }

    /** True when this build is below the required version. */
    public static boolean isBlocked(Activity activity) {
        if (activity == null) return false;
        SharedPreferences prefs = activity.getSharedPreferences(PREFS, 0);
        return BuildConfig.VERSION_CODE < required(prefs);
    }

    /** Re-applies the gate when an activity resumes; a blocked build stays blocked. */
    public static void recheck(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        SharedPreferences prefs = activity.getSharedPreferences(PREFS, 0);
        if (BuildConfig.VERSION_CODE < required(prefs)) showBlocking(activity, prefs);
    }

    private static void refreshPolicy(final Activity activity, final SharedPreferences prefs) {
        long last = prefs.getLong(KEY_CHECKED_AT, 0L);
        long now = System.currentTimeMillis();
        if (last > 0 && now >= last && now - last < CHECK_WINDOW_MS) return;
        new Thread(() -> {
            int remote = 0;
            try {
                org.json.JSONObject policy = D1HttpTransport.send(new java.net.URL(POLICY), "GET", null, null);
                if (policy != null) remote = Math.max(0, policy.optInt("minVersionCode", 0));
            } catch (Throwable ignored) {
                return; // Keep the last known requirement; never lower it on a failed read.
            }
            final int value = remote;
            prefs.edit().putInt(KEY_MIN_REMOTE, value)
                    .putLong(KEY_CHECKED_AT, System.currentTimeMillis()).apply();
            MAIN.post(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (BuildConfig.VERSION_CODE < Math.max(MIN_SUPPORTED_VERSION_CODE, value)) {
                    showBlocking(activity, prefs);
                }
            });
        }, "ForceUpdate policy").start();
    }

    /**
     * Asks Play to install a newer build in Immediate mode. A no-op for builds Play did not
     * install, and for builds Play already considers current.
     */
    private static void startImmediateUpdate(final Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        try {
            final AppUpdateManager manager = AppUpdateManagerFactory.create(activity);
            manager.getAppUpdateInfo().addOnSuccessListener(info -> {
                if (info == null || activity.isFinishing()) return;
                boolean available =
                        info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                                || info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS;
                if (!available || !info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) return;
                try {
                    manager.startUpdateFlow(info, activity,
                            AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build());
                } catch (Throwable ignored) {
                    // Play refused the flow; the blocking dialog is the fallback.
                }
            }).addOnFailureListener(error -> {
                // Sideloaded install: Play has no state to read. Nothing to do.
            });
        } catch (Throwable ignored) {
            // Play services missing entirely.
        }
    }

    /** Shows the non-dismissable gate. Safe to call more than once. */
    public static void showBlocking(final Activity activity, SharedPreferences prefs) {
        if (activity == null || activity.isFinishing() || blocking) return;
        blocking = true;

        final AlertDialog dialog = new AlertDialog.Builder(activity,
                android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(TITLE)
                .setMessage(BODY)
                .setCancelable(false)
                .setPositiveButton("Play Store Se Update Karo", null)
                .create();
        dialog.setCanceledOnTouchOutside(false);
        // Some skins still let a hardware BACK reach a dialog that claims to be uncancellable.
        dialog.setOnKeyListener((d, keyCode, event) ->
                keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_DOWN);
        dialog.setOnDismissListener(d -> {
            blocking = false;
            // Re-arm: the app must stay blocked until the installed version actually changes.
            if (!activity.isFinishing() && BuildConfig.VERSION_CODE < required(prefs)) {
                MAIN.post(() -> showBlocking(activity, prefs));
            }
        });
        dialog.show();
        // The only button opens the listing and leaves the dialog in place underneath.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> openPlayStore(activity));
    }

    /** Opens the Play Store listing; falls back to the web listing if Play is absent. */
    public static void openPlayStore(Activity activity) {
        String pkg = BuildConfig.APPLICATION_ID;
        try {
            Intent market = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg));
            market.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(market);
            return;
        } catch (ActivityNotFoundException noPlayStore) {
            // Fall through to the web listing.
        } catch (Throwable ignored) {
            // Fall through.
        }
        try {
            Intent web = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=" + pkg));
            web.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(web);
        } catch (Throwable ignored) {
            Ui.toast(activity, "Play Store khol nahi paye. Play Store se app update karo.");
        }
    }
}
