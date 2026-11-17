package com.friendbook.social.push;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.*;
import com.friendbook.social.App;
import com.friendbook.social.core.Fb;
import com.google.android.gms.tasks.Tasks;
import com.friendbook.social.d1.documents.*;
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
                DocumentSnapshot chat = Tasks.await(Fb.db().collection("chats").document(id)
                        .get(Source.SERVER), 20, TimeUnit.SECONDS);
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
                DocumentSnapshot post = Tasks.await(Fb.db().collection("posts").document(id)
                        .get(Source.SERVER), 20, TimeUnit.SECONDS);
                if (!post.exists() || !uid.equals(post.getString("authorId"))) return Result.failure();
                QuerySnapshot friends = Tasks.await(Fb.db().collection("friends")
                        .whereArrayContains("participants", uid).get(Source.SERVER), 20, TimeUnit.SECONDS);
                Set<String> recipients = new HashSet<>();
                for (DocumentSnapshot friend : friends) {
                    Object members = friend.get("participants");
                    if (members instanceof List) for (Object m : (List<?>) members)
                        if (m instanceof String && !uid.equals(m)) recipients.add((String) m);
                }
                for (String to : recipients) {
                    if (!uid.equals(Fb.uid())) return Result.success();
                    // Stable document/event ID makes replay safe if a partial fan-out is retried.
                    DocumentReference ref = Fb.db().collection("notifications")
                            .document("friend_post_" + id + "_" + to);
                    Map<String, Object> n = new HashMap<>();
                    n.put("toUserId", to); n.put("fromUserId", uid); n.put("fromUserName", Fb.myName());
                    n.put("fromUserPhotoUrl", Fb.myPhoto()); n.put("type", "friend_post");
                    n.put("text", "shared a new post"); n.put("postId", id);
                    n.put("isRead", false); n.put("createdAt", FieldValue.serverTimestamp());
                    // Transaction preserves read state and original timestamp when replaying a partial batch.
                    Tasks.await(Fb.db().runTransaction(tx -> {
                        if (!tx.get(ref).exists()) tx.set(ref, n);
                        return null;
                    }), 20, TimeUnit.SECONDS);
                    if (!uid.equals(Fb.uid())) return Result.success();
                    FCMPush.notification(ref.getId(), n);
                }
            } else return Result.failure();
            return Result.success();
        } catch (Exception e) {
            android.util.Log.w("FriendBookPush", "Recipient lookup pending: " + e.getClass().getSimpleName());
            return getRunAttemptCount() < 8 ? Result.retry() : Result.failure();
        }
    }
}
