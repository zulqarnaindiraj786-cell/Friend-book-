package com.friendbook.social.core;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import com.friendbook.social.BuildConfig;
import com.google.android.play.core.install.model.UpdateAvailability;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=android.app.Application.class)
public class AppUpdatesTest {
    @Test public void onlyNewerPlayAvailableVersionRequiresUpdate() {
        int installed=BuildConfig.VERSION_CODE;
        assertTrue(AppUpdates.requiresUpdate(UpdateAvailability.UPDATE_AVAILABLE, installed+1));
        assertFalse(AppUpdates.requiresUpdate(UpdateAvailability.UPDATE_AVAILABLE, installed));
        assertFalse(AppUpdates.requiresUpdate(UpdateAvailability.UNKNOWN, installed+1));
        assertFalse(AppUpdates.requiresUpdate(UpdateAvailability.UPDATE_NOT_AVAILABLE, installed+1));
    }

    @Test public void minimumVersionConfigDefaultsFailOpenAndDoesNotBlock() {
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
        assertEquals(1,CloudAppConfig.minimumVersionCode(activity));
        activity.getSharedPreferences("cloud_app_config",Activity.MODE_PRIVATE).edit()
                .putInt("min_version_code",123).apply();
        assertEquals(123,CloudAppConfig.minimumVersionCode(activity));
    }

    @Test public void availableUpdateIsOptionalAndOpensOfficialPlayListing() {
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
        AlertDialog dialog=AppUpdates.showAvailable(activity, true);
        assertTrue(dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isShown());
        assertTrue(dialog.isCancelable());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        Intent intent=Shadows.shadowOf(activity).getNextStartedActivity();
        assertEquals(Intent.ACTION_VIEW,intent.getAction());
        assertEquals("https://play.google.com/store/apps/details?id=com.friendbook.social",intent.getDataString());
        assertTrue(dialog.isShowing());
    }
}
