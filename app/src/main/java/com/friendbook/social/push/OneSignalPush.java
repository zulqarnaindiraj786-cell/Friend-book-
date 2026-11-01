package com.friendbook.social.push;

import android.content.Context;
import android.content.Intent;
import com.onesignal.OneSignal;
import com.friendbook.social.core.Fb;
import com.friendbook.social.core.Prefs;
import com.friendbook.social.BuildConfig;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.*;

/** Public App ID only. Provider REST credentials exist ONLY in the Edge Function secret store. */
public final class OneSignalPush {
    private static final String APP_ID = "6921b7fa-7c0a-40e3-8dd9-e698429a038a";
    private static String identity;
    private static final ExecutorService IO = new ThreadPoolExecutor(1, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128), new ThreadPoolExecutor.DiscardPolicy());
    public static void init(Context c) {
        OneSignal.initWithContext(c, APP_ID);
        OneSignal.getNotifications().addClickListener(event -> {
            JSONObject d = event.getNotification().getAdditionalData();
            if (d == null || !allowed(c, d)) return;
            Intent i = new Intent(c, PushOpenActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            java.util.Iterator<String> it = d.keys();
            while (it.hasNext()) { String k = it.next(); i.putExtra(k, d.optString(k, "")); }
            c.startActivity(i);
        });
        OneSignal.getNotifications().addForegroundLifecycleListener(event -> {
            JSONObject d = event.getNotification().getAdditionalData();
            if (d == null || !allowed(c, d)) event.preventDefault();
        });
        Fb.auth().addAuthStateListener(a -> sync());
    }
    private static boolean allowed(Context c, JSONObject d) {
        if (Fb.uid() == null || !Fb.uid().equals(d.optString("toUserId"))) return false;
        String type = d.optString("type");
        if ((type.contains("like") || type.equals("reaction")) && !Prefs.notifLikes(c)) return false;
        if ((type.contains("comment") || type.equals("reply")) && !Prefs.notifComments(c)) return false;
        if (type.startsWith("friend_") && !Prefs.notifRequests(c)) return false;
        return !type.equals("message") || Prefs.notifMessages(c);
    }
    public static synchronized void sync() {
        String uid = Fb.uid();
        if (uid != null && !uid.equals(identity)) { OneSignal.login(uid); identity = uid; }
        else if (uid == null && identity != null) { OneSignal.logout(); identity = null; }
    }
    public static synchronized void logout() { OneSignal.logout(); identity = null; }
    public static void notification(String id, Map<String, Object> n) {
        Object target = n.get("toUserId");
        if (target == null) return;
        JSONObject data = new JSONObject();
        try { data.put("notificationId", id); data.put("type", n.get("type")); }
        catch (Exception e) { return; }
        invoke(target.toString(), String.valueOf(n.get("fromUserName")), String.valueOf(n.get("text")), data);
    }
    public static void friendPost(String postId) {
        String uid = Fb.uid();
        if (uid == null) return;
        Fb.db().collection("friends").whereArrayContains("participants", uid).get().addOnSuccessListener(friends -> {
            java.util.Set<String> recipients = new java.util.HashSet<>();
            for (com.google.firebase.firestore.DocumentSnapshot friend : friends) {
                Object list = friend.get("participants");
                if (list instanceof java.util.List) for (Object item : (java.util.List<?>) list)
                    if (item instanceof String && !uid.equals(item)) recipients.add((String) item);
            }
            for (String to : recipients) com.friendbook.social.core.Notify.send(to, "friend_post", "shared a new post", postId);
        });
    }
    public static void message(String chatId, String messageId) {
        String uid = Fb.uid();
        if (uid == null) return;
        Fb.db().collection("chats").document(chatId).get().addOnSuccessListener(chat -> {
            Object members = chat.get("participants");
            if (!(members instanceof java.util.List)) return;
            for (Object member : (java.util.List<?>) members) {
                if (!(member instanceof String) || uid.equals(member)) continue;
                try {
                    JSONObject data = new JSONObject().put("type", "message").put("chatId", chatId).put("messageId", messageId);
                    invoke((String) member, Fb.myName(), "New message", data);
                } catch (Exception e) { android.util.Log.w("FriendBookPush", "Invalid chat push event"); }
            }
        });
    }
    /** Native Java equivalent of supabase.functions.invoke, using Firebase ID token authentication. */
    private static void invoke(String target, String title, String message, JSONObject data) {
        if (Fb.auth().getCurrentUser() == null) return;
        if (!BuildConfig.SUPABASE_URL.matches("https://[a-z0-9-]+\\.supabase\\.co")) {
            android.util.Log.e("FriendBookPush", "SUPABASE_URL not configured; remote push unavailable"); return;
        }
        Fb.auth().getCurrentUser().getIdToken(false).addOnSuccessListener(result -> IO.execute(() -> {
            HttpURLConnection connection = null;
            try {
                JSONObject body = new JSONObject().put("target_user_id", target).put("title", title)
                        .put("message", message).put("data", data);
                connection = (HttpURLConnection) new URL(BuildConfig.SUPABASE_URL + "/functions/v1/send-onesignal-notification").openConnection();
                connection.setRequestMethod("POST"); connection.setDoOutput(true);
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(10000); connection.setReadTimeout(20000);
                connection.setRequestProperty("Authorization", "Bearer " + result.getToken());
                connection.setRequestProperty("Content-Type", "application/json");
                try (java.io.OutputStream out = connection.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
                int status = connection.getResponseCode();
                android.util.Log.i("FriendBookPush", "Edge push HTTP " + status);
            } catch (Exception e) { android.util.Log.w("FriendBookPush", "Edge push request failed", e); }
            finally { if (connection != null) connection.disconnect(); }
        }));
    }
}
