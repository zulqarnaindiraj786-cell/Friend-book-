package com.friendbook.social.core;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import com.friendbook.social.BuildConfig;
import org.json.JSONObject;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

/** Optional update notice. Never blocks launch, changes backend or installs silently. */
public final class AppUpdates {
 private AppUpdates(){}
 static final String APK="https://github.com/zulqarnaindiraj786-cell/Friend-book-/releases/latest/download/friendbook.apk";
 private static final String POLICY="https://rough-firefly-dd7b.zulqarnaindiraj786.workers.dev/v1/android-release";
 private static final AtomicBoolean BUSY=new AtomicBoolean();
 static boolean valid(JSONObject policy){
  Object code=policy.opt("versionCode");
  return code instanceof Number && ((Number)code).doubleValue()==((Number)code).longValue()
   && ((Number)code).longValue()>0 && ((Number)code).longValue()<=Integer.MAX_VALUE
   && BuildConfig.APPLICATION_ID.equals(policy.optString("packageName"))
   && APK.equals(policy.optString("apkUrl")) && policy.optString("versionName").matches("[0-9]+(\\.[0-9]+){1,3}");
 }
 public static void check(Activity activity,boolean manual){
  android.content.SharedPreferences prefs=activity.getSharedPreferences("apk_updates",0);
  long last=prefs.getLong("checked_at",0),now=System.currentTimeMillis();
  if(!manual&&last<=now&&now-last<12*60*60*1000L)return;
  if(!BUSY.compareAndSet(false,true))return;
  new Thread(()->{
   JSONObject policy=null;
   try{policy=D1HttpTransport.send(new URL(POLICY),"GET",null,null);if(!valid(policy))policy=null;}
   catch(Exception ignored){}
   final JSONObject result=policy;
   new Handler(Looper.getMainLooper()).post(()->{
    BUSY.set(false);
    if(activity.isFinishing()||activity.isDestroyed())return;
    if(result==null){if(manual)Ui.toast(activity,"Couldn't check for updates. Please retry.");return;}
    prefs.edit().putLong("checked_at",System.currentTimeMillis()).apply();
    if(result.optInt("versionCode")>BuildConfig.VERSION_CODE)show(activity,result);
    else if(manual)Ui.toast(activity,"You have the latest APK.");
   });
  },"APK update check").start();
 }
 static void show(Activity activity,JSONObject policy){
  if(!valid(policy)||policy.optInt("versionCode")<=BuildConfig.VERSION_CODE)return;
  new AlertDialog.Builder(activity).setTitle("APK update available")
   .setMessage("Version "+policy.optString("versionName")+" is available. Install it over the existing app. Do not uninstall or clear app data. This notice does not change the interaction backend.")
   .setNegativeButton("Later",null)
   .setPositiveButton("Download APK",(dialog,which)->{
    try{activity.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(APK)));}
    catch(Exception error){Ui.toast(activity,"No browser available to download the APK.");}
   }).show();
 }
}
