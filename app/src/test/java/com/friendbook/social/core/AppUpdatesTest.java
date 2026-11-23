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

    @Test public void requiredUpdateHasNoSkipAndOpensOfficialPlayListing() {
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
        AlertDialog dialog=AppUpdates.showRequired(activity);
        assertEquals(android.view.View.GONE, dialog.getButton(AlertDialog.BUTTON_NEGATIVE).getVisibility());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        Intent intent=Shadows.shadowOf(activity).getNextStartedActivity();
        assertEquals(Intent.ACTION_VIEW,intent.getAction());
        assertEquals("https://play.google.com/store/apps/details?id=com.friendbook.social",intent.getDataString());
        assertTrue(dialog.isShowing());
    }
}
