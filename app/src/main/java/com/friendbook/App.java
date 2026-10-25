package com.friendbook;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import com.friendbook.core.Fb;
import com.friendbook.core.Prefs;
import com.friendbook.core.Presence;
import com.friendbook.core.Ui;

/** Application class: boots Firebase, presence, wellbeing timer and shake-to-report detector. */
public class App extends Application implements SensorEventListener {

    public static App instance;
    private ShakeListener shakeListener;
    private long lastShake = 0;
    private float lastX, lastY, lastZ;
    private boolean hasLast;

    private int resumedCount = 0;
    private final Handler timeHandler = new Handler(Looper.getMainLooper());
    private final Runnable timeTick = new Runnable() {
        @Override
        public void run() {
            if (resumedCount > 0) {
                boolean hitLimit = Prefs.addForegroundSeconds(App.this, 30);
                if (hitLimit) {
                    Ui.toast(App.this, "⏰ Daily time reminder: you reached "
                            + Prefs.dailyLimitMinutes(App.this) + " min on friendbook today");
                }
                timeHandler.postDelayed(this, 30_000L);
            }
        }
    };

    public interface ShakeListener { void onShake(); }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Fb.init(this);
        installCrashReporter();
        Presence.init();
        Fb.startMeListener();

        // Track real foreground time for "Your time on friendbook"
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity a) {
                if (resumedCount == 0) {
                    Prefs.addForegroundSeconds(App.this, 5);
                    timeHandler.removeCallbacks(timeTick);
                    timeHandler.postDelayed(timeTick, 30_000L);
                }
                resumedCount++;
            }
            @Override public void onActivityPaused(Activity a) {
                resumedCount = Math.max(0, resumedCount - 1);
                if (resumedCount == 0) timeHandler.removeCallbacks(timeTick);
            }
            @Override public void onActivityCreated(Activity a, Bundle b) { }
            @Override public void onActivityStarted(Activity a) { }
            @Override public void onActivityStopped(Activity a) { }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            @Override public void onActivityDestroyed(Activity a) { }
        });

        // Secret feature: shake phone to send feedback (controlled via Settings).
        SensorManager sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sm != null) {
            Sensor acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (acc != null) sm.registerListener(this, acc, SensorManager.SENSOR_DELAY_UI);
        }
    }

    private void installCrashReporter() {
        final Thread.UncaughtExceptionHandler sys =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            try {
                java.io.StringWriter sw = new java.io.StringWriter();
                ex.printStackTrace(new java.io.PrintWriter(sw));
                String stack = sw.toString();
                if (stack.length() > 7000) stack = stack.substring(0, 7000);
                java.util.Map<String, Object> c = new java.util.HashMap<>();
                c.put("stack", stack);
                c.put("thread", thread.getName());
                c.put("uid", Fb.uid());
                c.put("device", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                        + " · Android " + android.os.Build.VERSION.RELEASE);
                c.put("appVersion", 7);
                c.put("createdAt",
                        com.google.firebase.firestore.FieldValue.serverTimestamp());
                Fb.db().collection("crashReports").add(c);
                Thread.sleep(500);
            } catch (Throwable ignored) { }
            if (sys != null) sys.uncaughtException(thread, ex);
            else { android.os.Process.killProcess(android.os.Process.myPid()); System.exit(10); }
        });
    }

    public void setShakeListener(ShakeListener l) { this.shakeListener = l; }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (!Prefs.shakeToReport(this)) return;
        float x = e.values[0], y = e.values[1], z = e.values[2];
        if (hasLast) {
            float delta = Math.abs(x + y + z - lastX - lastY - lastZ);
            long now = System.currentTimeMillis();
            if (delta > 26 && now - lastShake > 1500) {
                lastShake = now;
                if (shakeListener != null) shakeListener.onShake();
            }
        }
        lastX = x; lastY = y; lastZ = z; hasLast = true;
    }

    @Override public void onAccuracyChanged(Sensor s, int a) { }

    public static void openLockIfNeeded(Activity from) {
        if (from instanceof LockActivity) return;
        Intent i = new Intent(from, LockActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        from.startActivity(i);
    }
}
