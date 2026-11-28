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
        long friendPostWindow = FriendPostNotificationId.windowBucket(System.currentTimeMillis());
        OneTimeWorkRequest.Builder builder = new OneTimeWorkRequest.Builder(PushFanoutWorker.class)
                .setInputData(new Data.Builder().putString("uid", uid).putString("kind", kind)
                        .putString("document", documentId).putString("message", messageId)
                        .putLong("friend_post_window", friendPostWindow).build())
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
        if(id==null || id.isEmpty())return Result.failure();
        FanoutCheckpoint checkpoint=null;
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
                checkpoint=new FanoutCheckpoint(getApplicationContext(),uid,id,System.currentTimeMillis());
                DocumentSnapshot post = Tasks.await(Fb.db().collection("posts").document(id)
                        .get(Source.SERVER), 20, TimeUnit.SECONDS);
                if (!post.exists() || !uid.equals(post.getString("authorId"))) {checkpoint.clear();return Result.failure();}
                // Worker returns canonical friends ranked by private last-active time,
                // capped in SQL before the client creates any D1 notification rows.
                List<String> recipients=Tasks.await(Fb.db().friendPostRecipients(),20,TimeUnit.SECONDS);
                // Retries keep the bucket assigned when WorkManager accepted this event,
                // including retries that happen after a 12-hour boundary.
                long windowBucket = getInputData().getLong("friend_post_window",
                        FriendPostNotificationId.windowBucket(System.currentTimeMillis()));
                for (String to : recipients) {
                    if (!uid.equals(Fb.uid())) {checkpoint.clear();return Result.success();}
                    if(!checkpoint.select(to))continue;
                    if(checkpoint.completed(to))continue;
                    // One inbox row per author/friend per 12-hour bucket. Even on
                    // ALREADY_EXISTS, enqueue the push: the gateway's durable per-device
                    // claim key includes this notification ID and suppresses duplicate sends.
                    DocumentReference ref = Fb.db().collection("notifications")
                            .document(FriendPostNotificationId.of(uid, to, windowBucket));
                    Map<String, Object> n = new HashMap<>();
                    n.put("toUserId", to); n.put("fromUserId", uid); n.put("fromUserName", Fb.myName());
                    n.put("fromUserPhotoUrl", Fb.myPhoto()); n.put("type", "friend_post");
                    n.put("text", "shared a new post"); n.put("postId", id);
                    n.put("isRead", false); n.put("createdAt", FieldValue.serverTimestamp());
                    // The sender cannot read another user's inbox. Atomic create-only
                    // preserves existing read state without reading private notifications.
                    try { Tasks.await(ref.create(n), 20, TimeUnit.SECONDS); }
                    catch (java.util.concurrent.ExecutionException e) {
                        Throwable cause=e.getCause();
                        if (!(cause instanceof D1DocumentStoreException)
                                || ((D1DocumentStoreException) cause).getCode()!=D1DocumentStoreException.Code.ALREADY_EXISTS) throw e;
                    }
                    if (!uid.equals(Fb.uid())) {checkpoint.clear();return Result.success();}
                    Operation enqueue=FCMPush.enqueueNotificationDurable(uid,ref.getId(),n);
                    enqueue.getResult().get(20,TimeUnit.SECONDS);
                    if (!uid.equals(Fb.uid())) {checkpoint.clear();return Result.success();}
                    checkpoint.markQueued(to);
                }
                checkpoint.clear();checkpoint=null;
            } else return Result.failure();
            return Result.success();
        } catch (Exception e) {
            android.util.Log.w("FriendBookPush", "Recipient lookup pending: " + e.getClass().getSimpleName());
            if(!uid.equals(Fb.uid())){if(checkpoint!=null)checkpoint.clear();return Result.success();}
            boolean retry=FanoutRetryPolicy.shouldRetry(e,getRunAttemptCount());
            if(checkpoint!=null){if(retry)checkpoint.flush();else checkpoint.clear();}
            return retry ? Result.retry() : Result.failure();
        }
    }
}
