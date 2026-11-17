package com.friendbook.social.push;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.*;
import com.friendbook.social.App;
import com.friendbook.social.core.Fb;
import com.friendbook.social.core.Format;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Durable recipient lookup: transient Firestore failures must not silently lose chat/post pushes. */
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
            if ("chat".equals(getInputData().getString("kind"))) {
                // The room is read from D1, the store the message was written to. Reading it from
                // Firestore returned nothing, so a sent message produced no push at all.
                Map<String, Object> chat = com.friendbook.social.core.D1Blocking.get("chats", id);
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
                Map<String, Object> post = com.friendbook.social.core.D1Blocking.get("posts", id);
                if (post == null || !uid.equals(Format.str(post, "authorId"))) return Result.failure();
                Set<String> recipients = new HashSet<>();
                String cursor = null;
                while (true) {
                    com.friendbook.social.core.DocQuery.Builder q =
                            com.friendbook.social.core.DocQuery.of("friends")
                                    .whereArrayContains("participants", uid).limit(100);
                    if (cursor != null && !cursor.isEmpty()) q.startAfter(cursor);
                    com.friendbook.social.core.CloudflareStore.Page page =
                            com.friendbook.social.core.D1Blocking.query(q.build());
                    if (page == null || page.documents.isEmpty()) break;
                    for (Map<String, Object> friend : page.documents) {
                        Object partners = friend.get("participants");
                        if (partners instanceof List) for (Object m : (List<?>) partners)
                            if (m instanceof String && !uid.equals(m)) recipients.add((String) m);
                    }
                    if (!page.hasMore || page.nextCursor == null || page.nextCursor.isEmpty()) break;
                    cursor = page.nextCursor;
                }
                for (String to : recipients) {
                    if (!uid.equals(Fb.uid())) return Result.success();
                    // Stable document id makes replay safe if a partial fan-out is retried: the
                    // row is rewritten rather than duplicated, and the user is not notified twice.
                    String noteId = "friend_post_" + id + "_" + to;
                    Map<String, Object> n = new HashMap<>();
                    n.put("toUserId", to); n.put("fromUserId", uid); n.put("fromUserName", Fb.myName());
                    n.put("fromUserPhotoUrl", Fb.myPhoto()); n.put("type", "friend_post");
                    n.put("text", "shared a new post"); n.put("postId", id);
                    n.put("isRead", false); n.put("createdAt", System.currentTimeMillis());
                    boolean first = com.friendbook.social.core.D1Blocking.get("notifications", noteId) == null;
                    com.friendbook.social.core.D1Blocking.put("notifications", noteId, n);
                    if (!uid.equals(Fb.uid())) return Result.success();
                    // A push for a row that already existed would buzz the same user twice for
                    // one post, which is exactly what the transaction was guarding against.
                    if (first) FCMPush.notification(noteId, n);
                }
            } else return Result.failure();
            return Result.success();
        } catch (Exception e) {
            android.util.Log.w("FriendBookPush", "Recipient lookup pending: " + e.getClass().getSimpleName());
            return getRunAttemptCount() < 8 ? Result.retry() : Result.failure();
        }
    }
}
