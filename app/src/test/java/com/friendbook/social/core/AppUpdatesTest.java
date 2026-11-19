package com.friendbook.social.core;
import android.app.Activity;
import android.content.Intent;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=android.app.Application.class)
public class AppUpdatesTest {
 private JSONObject policy()throws Exception{return new JSONObject().put("packageName","com.friendbook.social").put("versionCode",999).put("versionName","9.9.9").put("storeUrl",AppUpdates.STORE);}
 @Test public void rejectsForeignPackagesLinksAndMalformedVersions()throws Exception{
  assertTrue(AppUpdates.valid(policy()));
  assertFalse(AppUpdates.valid(policy().put("packageName","other.app")));
  assertFalse(AppUpdates.valid(policy().put("storeUrl","https://evil.invalid/app.apk")));
  assertFalse(AppUpdates.valid(policy().put("versionCode",1.5)));
  assertFalse(AppUpdates.valid(policy().put("versionCode",0)));
  assertFalse(AppUpdates.valid(policy().put("versionCode","999")));
  assertFalse(AppUpdates.valid(policy().put("versionName","<script>")));
 }
 @Test public void optionalNoticeOpensOnlyOfficialPlayStoreWithoutClearingData()throws Exception{
  Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
  activity.getSharedPreferences("user_data_fixture",0).edit().putString("draft","retained").commit();
  AppUpdates.show(activity,policy());
  android.app.AlertDialog dialog=(android.app.AlertDialog)ShadowAlertDialog.getLatestAlertDialog();
  assertNotNull(dialog);assertTrue(dialog.isShowing());
  dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
  Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
  Intent intent=Shadows.shadowOf(activity).getNextStartedActivity();
  assertEquals(Intent.ACTION_VIEW,intent.getAction());assertEquals(AppUpdates.STORE,intent.getDataString());
  assertEquals("retained",activity.getSharedPreferences("user_data_fixture",0).getString("draft",null));
 }
 @Test public void manualCheckOpensGooglePlayDirectly(){
  Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
  AppUpdates.check(activity,true);
  Intent intent=Shadows.shadowOf(activity).getNextStartedActivity();
  assertEquals(Intent.ACTION_VIEW,intent.getAction());
  assertEquals("https://play.google.com/store/apps/details?id=com.friendbook.social",intent.getDataString());
 }
 @Test public void laterKeepsAppUsableAndDoesNotLaunchAnInstaller()throws Exception{
  Activity activity=Robolectric.buildActivity(Activity.class).setup().get();AppUpdates.show(activity,policy());
  android.app.AlertDialog dialog=(android.app.AlertDialog)ShadowAlertDialog.getLatestAlertDialog();
  dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick();
  Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
  assertNull(Shadows.shadowOf(activity).getNextStartedActivity());assertFalse(activity.isFinishing());
 }
}
