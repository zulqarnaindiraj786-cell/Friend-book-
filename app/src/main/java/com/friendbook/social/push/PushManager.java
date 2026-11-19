package com.friendbook.social.push;

import android.app.*;
import android.content.*;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import com.friendbook.social.R;
import com.friendbook.social.core.*;
import java.util.*;

/** Private, multi-device registrations; account-scoped data-only pushes in every app state. */
public final class PushManager {
    public static final String CHANNEL = "friendbook_chime_v2";
    public static void init(Context c) {
        channel(c);
        FCMTokenManager.init(c);
    }
    public static void sync(Context c) { FCMTokenManager.sync(c); }
    public static void save(Context c, String token) {
        FCMTokenManager.onTokenChanged(c);
    }
    public static void unregister(Context c) {
        FCMTokenManager.unregister(c);
        NotificationManagerCompat.from(c).cancelAll();
        c.getSharedPreferences("push_target", 0).edit().clear().apply();
    }
    public static android.net.Uri sound(Context c) {
        return android.net.Uri.parse("android.resource://" + c.getPackageName() + "/raw/friendbook_chime");
    }
    public static void channel(Context c) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = c.getSystemService(NotificationManager.class);
            if (manager.getNotificationChannel(CHANNEL) != null) return; // Never override the user's channel settings.
            NotificationChannel old = manager.getNotificationChannel("friendbook_social_v1");
            int importance = old == null ? NotificationManager.IMPORTANCE_HIGH : old.getImportance();
            NotificationChannel n = new NotificationChannel(CHANNEL, "friendbook notifications", importance);
            n.setDescription("Messages, requests, reactions, comments and friends' posts");
            n.enableVibration(old == null || old.shouldVibrate());
            n.setSound(old != null && old.getSound() == null ? null : sound(c),
                    new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION).build());
            manager.createNotificationChannel(n);
        }
    }
    public static void testThisPhone(Context context, android.widget.TextView result) {
        channel(context);
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            result.setText("Android notifications are off. Open Android notification settings above.");
            return;
        }
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel n = context.getSystemService(NotificationManager.class).getNotificationChannel(CHANNEL);
            if (n != null && n.getImportance() < NotificationManager.IMPORTANCE_HIGH) {
                result.setText("Enable banners for FriendBook in Android notification settings, then retry.");
                return;
            }
        }
        String actor = Fb.uid();
        if (actor == null || !result.isEnabled()) return;
        result.setEnabled(false);
        result.setText("Registering and sending a real FCM test…");
        new Thread(() -> {
            String report;
            try {
                String token = com.google.android.gms.tasks.Tasks.await(com.google.firebase.messaging.FirebaseMessaging.getInstance().getToken(), 15, java.util.concurrent.TimeUnit.SECONDS);
                String bearer = PushGateway.idToken();
                if (!actor.equals(Fb.uid())) throw new IllegalStateException("Account changed");
                PushGateway.Response registration = PushGateway.call(new org.json.JSONObject().put("action", "register")
                        .put("device_id", FCMTokenManager.deviceId(context)).put("token", token), bearer);
                if (registration.status != 200 || !registration.data.optBoolean("registered")) {
                    report = "Registration failed (HTTP " + registration.status + ")";
                } else {
                    if (!actor.equals(Fb.uid())) throw new IllegalStateException("Account changed");
                    PushGateway.Response test = PushGateway.call(new org.json.JSONObject().put("action", "self_test")
                            .put("device_id", FCMTokenManager.deviceId(context)), bearer);
                    org.json.JSONArray reasons = test.data.optJSONArray("reasons");
                    report = test.data.optInt("accepted") > 0 ? "FCM accepted the test. Check your notification shade."
                            : test.data.optInt("duplicate") > 0 ? "Already sent this minute. Retry after a minute."
                            : "Push test failed: " + test.data.optString("error", "HTTP " + test.status)
                            + (reasons != null && reasons.length() > 0 ? " (" + reasons.optString(0) + ")" : "");
                }
            } catch (Exception error) { report = "Couldn't reach the push service. Tap to retry."; }
            final String text = report;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                if (actor.equals(Fb.uid())) { result.setText(text); result.setEnabled(true); }
            });
        }, "FriendBook push test").start();
    }

    /** Fallback for a fresh server-authenticated inbox event observed while Main is visible. */
    public static void showForegroundInbox(Context c, com.friendbook.social.d1.documents.DocumentSnapshot doc, long since) {
        if (doc.getMetadata().isFromCache() || doc.getMetadata().hasPendingWrites() || Prefs.quiet(c)) return;
        long at = com.friendbook.social.core.Format.ts(doc.get("createdAt"));
        long now = System.currentTimeMillis();
        if (at < Math.max(since, now - 30_000L) || at > now + 5_000L) return;
        String type = doc.getString("type");
        if (type == null || "announcement".equals(type) || "message".equals(type)) return;
        java.util.HashMap<String,String> payload = new java.util.HashMap<>();
        for (String key : new String[]{"type","toUserId","fromUserId","postId","contentType","groupId"}) {
            String value = doc.getString(key);
            if (value != null) payload.put(key, value);
        }
        payload.put("eventId", "notification_" + doc.getId());
        payload.put("notificationId", doc.getId());
        String title = doc.getString("fromUserName");
        String body = doc.getString("text");
        if (title != null && !title.isEmpty()) payload.put("title", title);
        if (body != null && !body.isEmpty()) payload.put("body", body);
        show(c, payload);
    }

    public static synchronized void show(Context c, Map<String, String> d) {
        String uid = Fb.uid();
        if (uid == null || !uid.equals(d.get("toUserId"))) {
            PushTrace.stage("display_skipped", "reason=wrong_account actor=" + PushTrace.id(uid)); return;
        }
        String type = d.get("type");
        if (type == null) return;
        // The live chat snapshot provides the in-conversation ding; avoid a second notification-channel sound.
        if ("message".equals(type) && com.friendbook.social.core.SoundEffects.isVisibleChat(d.get("chatId"))) {
            PushTrace.stage("display_skipped", "reason=chat_already_visible"); return;
        }
        if ((type.contains("like") || type.equals("reaction")) && !Prefs.notifLikes(c)) { PushTrace.stage("display_skipped", "reason=likes_disabled"); return; }
        if ((type.contains("comment") || type.equals("reply")) && !Prefs.notifComments(c)) { PushTrace.stage("display_skipped", "reason=comments_disabled"); return; }
        if (type.startsWith("friend_") && !Prefs.notifRequests(c)) { PushTrace.stage("display_skipped", "reason=friend_notifications_disabled"); return; }
        if (type.equals("message") && !Prefs.notifMessages(c)) { PushTrace.stage("display_skipped", "reason=messages_disabled"); return; }
        String id = d.get("eventId");
        if (id == null || id.isEmpty()) return;
        android.content.SharedPreferences seen = c.getSharedPreferences("push_seen", 0);
        if (seen.contains(id)) { PushTrace.stage("display_skipped", "reason=duplicate event=" + PushTrace.id(id)); return; }
        if (!NotificationManagerCompat.from(c).areNotificationsEnabled()) {
            PushTrace.stage("display_skipped", "reason=os_notifications_disabled"); return;
        }
        channel(c);
        Intent i = new Intent(c, PushOpenActivity.class);
        for (Map.Entry<String, String> e : d.entrySet()) i.putExtra(e.getKey(), e.getValue());
        i.setData(android.net.Uri.parse("friendbook://push/" + android.net.Uri.encode(id)));
        PendingIntent pi = PendingIntent.getActivity(c, id.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_friendbook).setColor(Ui.BLUE)
                .setContentTitle(d.getOrDefault("title", "friendbook"))
                .setContentText(d.getOrDefault("body", "New notification"))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(d.getOrDefault("body", "New notification")))
                .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).setContentIntent(pi)
                .setSound(sound(c))
                .setCategory(type.equals("message") ? NotificationCompat.CATEGORY_MESSAGE : NotificationCompat.CATEGORY_SOCIAL);
        if (Prefs.notifVibrate(c)) b.setVibrate(new long[]{0, 200, 100, 200});
        try {
            NotificationManagerCompat.from(c).notify(id, 0, b.build());
            PushTrace.stage("display_submitted", "event=" + PushTrace.id(id)); // Not proof the user saw it.
            if (seen.getAll().size() > 300) seen.edit().clear().apply();
            seen.edit().putBoolean(id, true).apply();
        } catch (SecurityException e) { android.util.Log.w("FriendBookPush", "Notification permission denied", e); }
    }
}
