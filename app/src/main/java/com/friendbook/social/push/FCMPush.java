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
        String name = String.valueOf(n.get("fromUserName"));
        String text = String.valueOf(n.get("text"));
        String title = text.startsWith(name + " ") ? text : name + " " + text;
        // Title/body are previews only: the gateway verifies and derives the final payload from Firestore.
        invoke(target.toString(), title, text, data);
    }
    public static void friendPost(String postId) {
        PushFanoutWorker.enqueue("friend_post", postId, "");
    }
    public static void message(String chatId, String messageId) {
        PushFanoutWorker.enqueue("chat", chatId, messageId);
    }
    static void invoke(String target, String title, String message, JSONObject data) {
        try {
            PushTrace.stage("send_enqueue", "actor=" + PushTrace.id(Fb.uid()) + " receiver="
                    + PushTrace.id(target) + " event=" + PushTrace.id(data.optString("notificationId", data.optString("messageId"))));
            PushDeliveryWorker.enqueue(new JSONObject().put("action", "send")
                    .put("target_user_id", target).put("receiverId", target)
                    .put("title", title).put("body", message).put("data", data));
        } catch (Exception e) { android.util.Log.w("FriendBookPush", "Invalid push event"); }
    }
}
