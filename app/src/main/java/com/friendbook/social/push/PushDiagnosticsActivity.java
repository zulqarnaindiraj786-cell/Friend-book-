package com.friendbook.social.push;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.friendbook.social.core.Ui;
import com.friendbook.social.core.Fb;
import org.json.JSONObject;

/** Gives users actionable registration/gateway diagnostics, not an unverified delivery promise. */
public class PushDiagnosticsActivity extends Activity {
    private final java.util.concurrent.ExecutorService io = java.util.concurrent.Executors.newSingleThreadExecutor();
    private TextView status;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = Ui.col(this); Ui.pad(root, 18, 20, 18, 20);
        TextView back = Ui.btn(this, "← Back"); back.setOnClickListener(v -> finish()); root.addView(back);
        root.addView(Ui.tvBold(this, "Push notification diagnostics", 20, Ui.txt(this)));
        status = Ui.tv(this, "", 14, Ui.txt(this)); Ui.pad(status, 0, 20, 0, 20); root.addView(status);
        TextView settings = Ui.btn(this, "Open Android notification settings"); root.addView(settings);
        settings.setOnClickListener(v -> startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName())));
        TextView register = Ui.btn(this, "Refresh FCM registration"); root.addView(register);
        register.setOnClickListener(v -> { FCMTokenManager.sync(this); status.setText("Registration queued. Wait a moment, then check server status."); });
        TextView check = Ui.btn(this, "Check server status"); root.addView(check);
        check.setOnClickListener(v -> request("status"));
        TextView test = Ui.btn(this, "Send a test notification to myself"); root.addView(test);
        test.setOnClickListener(v -> request("self_test"));
        TextView preview = Ui.btn(this, "Preview friendbook notification sound (local)"); root.addView(preview);
        preview.setOnClickListener(v -> {
            if (Fb.uid() == null) { status.setText("Sign in first."); return; }
            java.util.Map<String, String> d = new java.util.HashMap<>();
            d.put("toUserId", Fb.uid()); d.put("fromUserId", Fb.uid()); d.put("type", "push_test");
            d.put("eventId", "sound_preview_" + java.util.UUID.randomUUID());
            d.put("title", "friendbook"); d.put("body", "Your new notification chime — local preview only.");
            PushManager.show(this, d);
            status.setText("Local notification requested. Android permission, channel mute and DND still apply. This does not test remote push.");
        });
        root.addView(Ui.tv(this, "A successful server response means FCM accepted the request, not that the phone displayed it. "
                + "Check permission, channel sound, Google Play Services and battery restrictions. Android Force stop blocks push until reopening.", 13, Ui.sub(this)));
        setContentView(root); localStatus();
    }
    @Override protected void onResume() { super.onResume(); if (status != null) localStatus(); }
    private void localStatus() {
        android.content.SharedPreferences p = getSharedPreferences("push_diagnostics", 0);
        status.setText("Provider: Direct FCM\nSigned in: " + (Fb.uid() != null)
                + "\nAndroid notifications enabled: " + androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()
                + "\nRegistration: " + p.getString("registration", "Not confirmed yet")
                + "\nLast send: " + p.getString("last_send", "No result yet")
                + "\n" + p.getString("last_error", ""));
    }
    private void request(String action) {
        status.setText("Checking " + action + "…");
        io.execute(() -> {
            String message;
            try {
                PushGateway.Response r = PushGateway.call(new JSONObject().put("action", action), PushGateway.idToken());
                message = "HTTP " + r.status + "\n" + r.data.toString(2);
            } catch (Exception e) { message = "Server/session unavailable. Check connection and sign in again."; }
            final String outcome = message;
            runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) status.setText(outcome); });
        });
    }
    @Override protected void onDestroy() { io.shutdownNow(); super.onDestroy(); }
}
