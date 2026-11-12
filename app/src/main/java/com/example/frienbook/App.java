package com.example.frienbook;

import android.app.Application;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import com.example.frienbook.core.Fb;
import com.example.frienbook.core.Presence;

/** Application class: boots Firebase, presence and the shake-to-report detector. */
public class App extends Application implements SensorEventListener {

    public static App instance;
    private ShakeListener shakeListener;
    private long lastShake = 0;
    private float lastX, lastY, lastZ;
    private boolean hasLast;

    public interface ShakeListener { void onShake(); }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        // Firestore offline persistence is enabled by default on Android,
        // which gives Facebook-Lite style offline reading/writing.
        Fb.init(this);
        installCrashReporter();
        Presence.init();
        Fb.startMeListener();

        // Secret feature: shake phone to send feedback.
        SensorManager sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sm != null) {
            Sensor acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (acc != null) sm.registerListener(this, acc, SensorManager.SENSOR_DELAY_UI);
        }
    }

    /**
     * 📱 Device crash reporter — writes the uncaught exception stack into Firestore
     * `crashReports` (admin-read) so the failing line can be found without logcat,
     * then re-throws to the system handler so the OS still records the crash.
     */
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
                c.put("appVersion", 3);
                c.put("createdAt",
                        com.example.frienbook.core.cloud.FieldValue.serverTimestamp());
                Fb.db().collection("crashReports").add(c);
                Thread.sleep(500); // give the write a moment to hit the offline queue
            } catch (Throwable ignored) { }
            if (sys != null) sys.uncaughtException(thread, ex);
            else { android.os.Process.killProcess(android.os.Process.myPid()); System.exit(10); }
        });
    }

    public void setShakeListener(ShakeListener l) { this.shakeListener = l; }

    @Override
    public void onSensorChanged(SensorEvent e) {
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

    public static void openLockIfNeeded(android.app.Activity from) {
        if (from instanceof LockActivity) return;
        Intent i = new Intent(from, LockActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        from.startActivity(i);
    }
}
