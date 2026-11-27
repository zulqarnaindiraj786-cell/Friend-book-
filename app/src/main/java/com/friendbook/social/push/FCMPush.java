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
    /** Queue a persisted inbox event and expose WorkManager's durable-enqueue acknowledgement. */
    static androidx.work.Operation enqueueNotificationDurable(String actor,String id,Map<String,Object> n) throws Exception {
        Object target=n.get("toUserId");if(target==null)throw new IllegalArgumentException("Missing notification recipient");
        String name=String.valueOf(n.get("fromUserName")),text=String.valueOf(n.get("text"));
        String title=text.startsWith(name+" ")?text:name+" "+text;
        JSONObject data=new JSONObject().put("notificationId",id).put("type",n.get("type"));
        JSONObject body=new JSONObject().put("action","send").put("target_user_id",target.toString())
                .put("receiverId",target.toString()).put("title",title).put("body",text).put("data",data);
        androidx.work.Operation operation=PushDeliveryWorker.enqueueAcknowledged(body,false,actor);
        if(operation==null)throw new IllegalStateException("Account changed before enqueue");
        return operation;
    }
    public static void bulkMention(String postId,String commentId) {
        try { PushDeliveryWorker.enqueue(new JSONObject().put("action","bulk_mention")
                .put("post_id",postId).put("comment_id",commentId==null?"":commentId)
                .put("comment_backend",com.friendbook.social.core.D1Client.enabled("posts")?"d1":"firebase")); }
        catch(Exception ignored) { }
    }
    public static void friendPost(String postId) {
        PushFanoutWorker.enqueue("friend_post", postId, "");
    }
    public static void message(String chatId, String messageId, String directPeer) {
        if (directPeer == null || directPeer.isEmpty()) {
            PushFanoutWorker.enqueue("chat", chatId, messageId);
            return;
        }
        // The Worker still verifies the stored message and both room members.
        try {
            invoke(directPeer, "friendbook", "New message", new JSONObject().put("type", "message")
                    .put("chatId", chatId).put("messageId", messageId));
        } catch (org.json.JSONException ignored) { }
    }
    // Keep the bounded foreground attempt off both the UI and D1 send lanes.
    private static final java.util.concurrent.ExecutorService CHAT_PUSH = new java.util.concurrent.ThreadPoolExecutor(
            2, 2, 30, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(8));

    private static void dispatch(JSONObject body, boolean directMessage) {
        String actor = Fb.uid();
        if (actor == null) return;
        if (directMessage && com.friendbook.social.App.isForeground()) {
            try {
                CHAT_PUSH.execute(() -> {
                    if (!actor.equals(Fb.uid())) return;
                    try {
                        String bearer = PushGateway.idToken();
                        if (!actor.equals(Fb.uid())) return;
                        PushGateway.Response response = PushGateway.call(body, bearer);
                        if (response.status >= 200 && response.status < 300 && response.data.optInt("failed") == 0) return;
                        if (!PushRetryPolicy.shouldRetry(false, response.status, 0, response.data.optString("error"))
                                && response.status != 401 && response.data.optInt("failed") == 0) return;
                    } catch (Exception e) { PushTrace.failure("chat_fast_push_fallback", e); }
                    if (actor.equals(Fb.uid())) PushDeliveryWorker.enqueue(body);
                });
                return;
            } catch (java.util.concurrent.RejectedExecutionException busy) { /* Use the durable queue. */ }
        }
        if (actor.equals(Fb.uid())) PushDeliveryWorker.enqueue(body);
    }

    static void invoke(String target, String title, String message, JSONObject data) {
        try {
            PushTrace.stage("send_enqueue", "actor=" + PushTrace.id(Fb.uid()) + " receiver="
                    + PushTrace.id(target) + " event=" + PushTrace.id(data.optString("notificationId", data.optString("messageId"))));
            dispatch(new JSONObject().put("action", "send")
                    .put("target_user_id", target).put("receiverId", target)
                    .put("title", title).put("body", message).put("data", data), "message".equals(data.optString("type")));
        } catch (Exception e) { android.util.Log.w("FriendBookPush", "Invalid push event"); }
    }
}
