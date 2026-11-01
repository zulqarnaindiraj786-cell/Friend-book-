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
    public static final String CHANNEL = "friendbook_social_v1";
    public static void init(Context c) {
        channel(c);
        FCMTokenManager.init(c);
    }
    public static void sync(Context c) { FCMTokenManager.sync(c); }
    public static void save(Context c, String token) {
        FCMTokenManager.sync(c);
    }
    public static void unregister(Context c) {
        FCMTokenManager.unregister(c);
        NotificationManagerCompat.from(c).cancelAll();
        c.getSharedPreferences("push_target", 0).edit().clear().apply();
    }
    public static void channel(Context c) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel n = new NotificationChannel(CHANNEL, "friendbook notifications", NotificationManager.IMPORTANCE_HIGH);
            n.setDescription("Messages, requests, reactions, comments and friends' posts");
            n.enableVibration(true);
            n.setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI,
                    new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION).build());
            c.getSystemService(NotificationManager.class).createNotificationChannel(n);
        }
    }
    public static synchronized void show(Context c, Map<String, String> d) {
        String uid = Fb.uid();
        if (uid == null || !uid.equals(d.get("toUserId"))) return;
        String type = d.get("type");
        if (type == null) return;
        if ((type.contains("like") || type.equals("reaction")) && !Prefs.notifLikes(c)) return;
        if ((type.contains("comment") || type.equals("reply")) && !Prefs.notifComments(c)) return;
        if (type.startsWith("friend_") && !Prefs.notifRequests(c)) return;
        if (type.equals("message") && !Prefs.notifMessages(c)) return;
        String id = d.get("eventId");
        if (id == null || id.isEmpty()) return;
        android.content.SharedPreferences seen = c.getSharedPreferences("push_seen", 0);
        if (seen.contains(id)) return;
        if (!NotificationManagerCompat.from(c).areNotificationsEnabled()) return;
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
                .setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI)
                .setCategory(type.equals("message") ? NotificationCompat.CATEGORY_MESSAGE : NotificationCompat.CATEGORY_SOCIAL);
        if (Prefs.notifVibrate(c)) b.setVibrate(new long[]{0, 200, 100, 200});
        try {
            NotificationManagerCompat.from(c).notify(id, 0, b.build());
            if (seen.getAll().size() > 300) seen.edit().clear().apply();
            seen.edit().putBoolean(id, true).apply();
        } catch (SecurityException e) { android.util.Log.w("FriendBookPush", "Notification permission denied", e); }
    }
}
