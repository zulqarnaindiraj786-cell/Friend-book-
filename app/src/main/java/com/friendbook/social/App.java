package com.friendbook.social;

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

import com.friendbook.social.core.Fb;
import com.friendbook.social.core.Prefs;
import com.friendbook.social.core.Presence;
import com.friendbook.social.core.Ui;

/** Application class: boots Firebase, presence, wellbeing timer and shake-to-report detector. */
public class App extends Application implements SensorEventListener {

    public static App instance;
    public static boolean isForeground() { return instance != null && instance.resumedCount > 0; }
    private ShakeListener shakeListener;
    private long lastShake = 0;
    private float lastX, lastY, lastZ;
    private boolean hasLast;

    private int startedCount = 0;
    private int resumedCount = 0;
    private final Handler timeHandler = new Handler(Looper.getMainLooper());
    private final Runnable deferredOffline = () -> {
        if (startedCount <= 0) {
            Presence.goOffline();
        }
    };
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

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
            com.friendbook.social.core.SoundEffects.release();
    }

    public interface ShakeListener { void onShake(); }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        com.friendbook.social.core.FastCache.init(this);
        Fb.init(this);
        // Ad queue preload (3 Feed + 3 Reels ad loads) is deferred to the idle handler below:
        // six ad requests at cold start compete with the feed's first page for bandwidth and
        // CPU, which is exactly when the user is watching the splash.
        // 🔐 App Check with Play Integrity — MONITOR mode first (won't block users)
        // TODO: Switch to enforce after verifying in Firebase Console (App Check > Apps > friendbook > Enforce)
        com.google.firebase.appcheck.FirebaseAppCheck.getInstance()
                .installAppCheckProviderFactory(
                        com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory.getInstance());
        com.friendbook.social.push.PushManager.init(this);
        com.friendbook.social.core.ConsentManager.ensureInitialized(this);
        installCrashReporter();
        // Defer non-critical audio decode, ad warmup, presence & me sync to idle handler so cold-launch frame #1 paints in 0ms
        Looper.myQueue().addIdleHandler(() -> {
            com.friendbook.social.core.SoundEffects.warmUp(this);
            com.friendbook.social.core.AdsConfig.warmUpPools(this);
            Presence.init();
            Fb.startMeListener();
            // A session whose profile row is missing from D1 - a lost signup write, or a build
            // installed over one that failed - would otherwise act as a user nobody can look up.
            com.friendbook.social.core.CloudflareStore.ensureOwnProfile();
            return false;
        });

        // Track real foreground time + apply friendbook zero-lag screen transitions
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity a) {
                if (Prefs.instantTransitions(App.this)) {
                    a.overridePendingTransition(0, 0);
                }
                if (resumedCount == 0) {
                    Prefs.addForegroundSeconds(App.this, 5);
                    timeHandler.removeCallbacks(timeTick);
                    timeHandler.postDelayed(timeTick, 30_000L);
                }
                resumedCount++;
                com.friendbook.social.core.SoundEffects.warmUp(App.this);
            }
            @Override public void onActivityPaused(Activity a) {
                if (Prefs.instantTransitions(App.this)) {
                    a.overridePendingTransition(0, 0);
                }
                resumedCount = Math.max(0, resumedCount - 1);
                if (resumedCount == 0) {
                    timeHandler.removeCallbacks(timeTick);
                    com.friendbook.social.core.SoundEffects.stopAll();
                }
            }
            @Override public void onActivityCreated(Activity a, Bundle b) {
                if (Prefs.instantTransitions(App.this)) {
                    a.overridePendingTransition(0, 0);
                }
            }
            @Override public void onActivityStarted(Activity a) {
                timeHandler.removeCallbacks(deferredOffline);
                if (startedCount == 0 && Fb.uid() != null) {
                    Presence.init();
                }
                startedCount++;
                if (android.os.Build.VERSION.SDK_INT >= 36) {
                    android.view.View content = a.findViewById(android.R.id.content);
                    if (content != null && content.getTag(com.friendbook.social.R.id.activityMainRoot) == null) {
                        content.setTag(com.friendbook.social.R.id.activityMainRoot, Boolean.TRUE);
                        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
                            androidx.core.graphics.Insets bars = insets.getInsets(
                                    androidx.core.view.WindowInsetsCompat.Type.systemBars()
                                            | androidx.core.view.WindowInsetsCompat.Type.ime());
                            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                            return insets;
                        });
                    }
                }
            }
            @Override public void onActivityStopped(Activity a) {
                startedCount = Math.max(0, startedCount - 1);
                if (startedCount == 0) {
                    timeHandler.removeCallbacks(deferredOffline);
                    timeHandler.postDelayed(deferredOffline, 500L);
                }
            }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            @Override public void onActivityDestroyed(Activity a) { }
        });

        // Secret feature: shake phone to send feedback (low-power SENSOR_DELAY_NORMAL for smooth UI).
        SensorManager sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sm != null) {
            Sensor acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (acc != null) sm.registerListener(this, acc, SensorManager.SENSOR_DELAY_NORMAL);
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
                c.put("appVersion", com.friendbook.social.core.Ui.appVersionCode(this));
                c.put("appVersionName", com.friendbook.social.core.Ui.appVersionName(this));
                // Java millis, and D1: a crash report sent to a switched-off store is a crash
                // nobody can diagnose. The handler still runs synchronously before the process
                // dies, so the write is started here rather than deferred.
                c.put("createdAt", System.currentTimeMillis());
                com.friendbook.social.core.CloudflareStore.put("crash_reports",
                        "crash_" + System.currentTimeMillis(), c);
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
