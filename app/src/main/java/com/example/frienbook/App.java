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
        Presence.init();
        Fb.startMeListener();

        // Secret feature: shake phone to send feedback.
        SensorManager sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sm != null) {
            Sensor acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (acc != null) sm.registerListener(this, acc, SensorManager.SENSOR_DELAY_UI);
        }
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
