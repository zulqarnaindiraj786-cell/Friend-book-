package com.friendbook.social.push;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.*;
import com.friendbook.social.App;
import com.friendbook.social.core.CloudflareDocs;
import com.friendbook.social.core.Fb;
import com.google.firebase.firestore.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Durable recipient lookup on Cloudflare D1: a transient failure must not silently lose a push. */
public final class PushFanoutWorker extends Worker {
    public PushFanoutWorker(@NonNull Context c, @NonNull WorkerParameters p) { super(c, p); }
    public static void enqueue(String kind, String documentId, String messageId) {
        String uid = Fb.uid();
        if (uid == null || documentId == null) return;
        OneTimeWorkRequest.Builder builder = new OneTimeWorkRequest.Builder(PushFanoutWorker.class)
                .setInputData(new Data.Builder().putString("uid", uid).putString("kind", kind)
                        .putString("document", documentId).putString("message", messageId).build())
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .addTag("fcm_user_" + uid);
        // Pre-Android 12 expedited workers require a visible foreground service.
        // Use normal durable work there rather than creating a notification just to send a push.
        if (android.os.Build.VERSION.SDK_INT >= 31)
            builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST);
        OneTimeWorkRequest work = builder.build();
        WorkManager.getInstance(App.instance).enqueueUniqueWork("fcm_fanout_" + uid + "_" + kind
                + "_" + documentId + "_" + messageId, ExistingWorkPolicy.KEEP, work);
    }
    @NonNull @Override public Result doWork() {
        String uid = getInputData().getString("uid");
        if (uid == null || !uid.equals(Fb.uid())) return Result.success();
        String id = getInputData().getString("document");
        try {
            // Every lookup below reads Cloudflare D1 with the blocking transport: the app writes there
            // now, so a recipient list must be resolved from the same store or it would be empty.
            if ("chat".equals(getInputData().getString("kind"))) {
                Map<String, Object> chat = CloudflareDocs.readSync("chats", id);
                if (chat == null) return Result.failure();
                Object members = chat.get("participants");
                if (!(members instanceof List) || !((List<?>) members).contains(uid)) return Result.failure();
                for (Object member : (List<?>) members) {
                    if (!uid.equals(Fb.uid())) return Result.success();
                    if (!(member instanceof String) || uid.equals(member)) continue;
                    JSONObject data = new JSONObject().put("type", "message").put("chatId", id)
                            .put("messageId", getInputData().getString("message"));
                    FCMPush.invoke((String) member, Fb.myName(), "New message", data);
                }
            } else if ("friend_post".equals(getInputData().getString("kind"))) {
                Map<String, Object> post = CloudflareDocs.readSync("posts", id);
                if (post == null || !uid.equals(String.valueOf(post.get("authorId")))) return Result.failure();
                Set<String> recipients = new HashSet<>();
                String cursor = "";
                while (true) {
                    List<Map<String, Object>> page = CloudflareDocs.readSyncPage("friends", 50, "createdAt", cursor);
                    if (page.isEmpty()) break;
                    for (Map<String, Object> friend : page) {
                        cursor = String.valueOf(friend.get("id"));
                        Object members = friend.get("participants");
                        if (members instanceof List) for (Object m : (List<?>) members)
                            if (m instanceof String && !uid.equals(m)) recipients.add((String) m);
                        // A `friends/<a>_<b>` row carries no participants array: the id is the pair.
                        String[] pair = cursor.split("_");
                        for (String part : pair)
                            if (part.length() > 10 && !part.equals(uid)) recipients.add(part);
                    }
                    if (page.size() < 50) break;
                }
                for (String to : recipients) {
                    if (!uid.equals(Fb.uid())) return Result.success();
                    // A stable id makes a retried fan-out safe, and the same id means the same
                    // notification row is overwritten instead of duplicated.
                    String noteId = "friend_post_" + id + "_" + to;
                    if (CloudflareDocs.readSync("notifications", noteId) != null) continue;
                    Map<String, Object> n = new HashMap<>();
                    n.put("toUserId", to); n.put("fromUserId", uid); n.put("fromUserName", Fb.myName());
                    n.put("fromUserPhotoUrl", Fb.myPhoto()); n.put("type", "friend_post");
                    n.put("text", "shared a new post"); n.put("postId", id);
                    n.put("isRead", false); n.put("createdAt", System.currentTimeMillis());
                    CloudflareDocs.writeSync("notifications", noteId, n);
                    if (!uid.equals(Fb.uid())) return Result.success();
                    FCMPush.notification(noteId, n);
                }
            } else return Result.failure();
            return Result.success();
        } catch (Exception e) {
            android.util.Log.w("FriendBookPush", "Recipient lookup pending: " + e.getClass().getSimpleName());
            return getRunAttemptCount() < 8 ? Result.retry() : Result.failure();
        }
    }
}
