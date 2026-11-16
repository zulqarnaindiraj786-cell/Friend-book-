package com.friendbook.social.core;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.view.KeyEvent;

import com.friendbook.social.BuildConfig;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The update gate is the one screen that must never let someone through, so its escape hatches
 * get their own tests.
 *
 * <p>The rules these lock in: there is no skip, no "later", no BACK, and the only action opens
 * the Play Store listing. The old behaviour - a dismissable notice that downloaded an APK from
 * a GitHub release - must not come back.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = android.app.Application.class)
public class AppUpdatesTest {

    private Activity activity() {
        ForceUpdate.resetGateForTests();
        return Robolectric.buildActivity(Activity.class).setup().get();
    }

    @Test
    public void theGateOffersExactlyOneActionAndItIsThePlayListing() {
        Activity a = activity();
        ForceUpdate.showBlocking(a, a.getSharedPreferences("force_update", 0));
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();

        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull("the blocking dialog must be shown", dialog);
        assertTrue(dialog.isShowing());
        assertEquals("Play Store Se Update Karo",
                String.valueOf(dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText()));
        assertEquals("no negative button / no skip", -1, dialog.getButton(AlertDialog.BUTTON_NEGATIVE) == null ? -1 : 0);
        assertEquals("no neutral button / no later", -1, dialog.getButton(AlertDialog.BUTTON_NEUTRAL) == null ? -1 : 0);
    }

    @Test
    public void theOnlyActionOpensThePlayStoreListingForThisPackage() {
        Activity a = activity();
        ForceUpdate.showBlocking(a, a.getSharedPreferences("force_update", 0));
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();

        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();

        Intent intent = Shadows.shadowOf(a).getNextStartedActivity();
        assertNotNull("pressing the button must leave the app for the store", intent);
        String target = String.valueOf(intent.getDataString());
        assertTrue("must never point at a GitHub APK: " + target,
                target.startsWith("market://details?id=") || target.startsWith("https://play.google.com/store/apps/details?id="));
        assertTrue("must address this app: " + target, target.contains(BuildConfig.APPLICATION_ID));
    }

    @Test
    public void backCannotDismissTheGate() {
        Activity a = activity();
        ForceUpdate.showBlocking(a, a.getSharedPreferences("force_update", 0));
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();

        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertFalse("the dialog itself must not be cancelable", isCancelable(dialog));
        boolean consumed = dialog.onKeyDown(KeyEvent.KEYCODE_BACK,
                new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
        assertTrue("BACK must be swallowed", consumed);
        assertTrue("the gate must still be up", dialog.isShowing());
    }

    /**
     * Reads Dialog.isCancelable by reflection: the unit-test compile classpath is the
     * mockable android.jar, which does not expose every framework method by name.
     */
    private static boolean isCancelable(AlertDialog dialog) {
        try {
            java.lang.reflect.Method m = android.app.Dialog.class.getMethod("isCancelable");
            return Boolean.TRUE.equals(m.invoke(dialog));
        } catch (Exception unavailable) {
            return false;
        }
    }

    @Test
    public void aBlockedBuildIsReportedAsBlocked() {
        Activity a = activity();
        // This build is the version being shipped, so the baked-in floor must already accept it.
        assertFalse("v2.9.0 must not block itself",
                ForceUpdate.isBlocked(a));
        assertTrue("the floor must be at or below the version being shipped",
                BuildConfig.VERSION_CODE >= ForceUpdate.MIN_SUPPORTED_VERSION_CODE);
    }
}
