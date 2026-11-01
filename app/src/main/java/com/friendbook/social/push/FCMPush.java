package com.friendbook.social.push;

import com.friendbook.social.core.Fb;
import org.json.JSONObject;
import java.util.Map;

/** Events are authenticated and delivered by the secure direct-FCM gateway. */
public final class FCMPush {
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
    private static void invoke(String target, String title, String message, JSONObject data) {
        try {
            PushDeliveryWorker.enqueue(new JSONObject().put("action", "send")
                    .put("target_user_id", target).put("data", data));
        } catch (Exception e) { android.util.Log.w("FriendBookPush", "Invalid push event"); }
    }
}
