package com.friendbook.social.admin;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.*;
import com.friendbook.social.core.*;
import com.google.android.gms.tasks.Tasks;
import com.friendbook.social.d1.documents.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Idempotent per-user transactions, paginated beyond 400 users. No Cloud Functions required. */
public final class OfficialBroadcastWorker extends Worker {
    public OfficialBroadcastWorker(@NonNull Context c, @NonNull WorkerParameters p) { super(c, p); }
    public static void enqueue(Context context, String job, String uid) {
        WorkManager.getInstance(context).enqueueUniqueWork("official_broadcast_" + job, ExistingWorkPolicy.KEEP,
                new OneTimeWorkRequest.Builder(OfficialBroadcastWorker.class)
                        .setInputData(new Data.Builder().putString("job", job).putString("uid", uid).build())
                        .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build());
    }
    @NonNull @Override public Result doWork() {
        String uid = getInputData().getString("uid"), id = getInputData().getString("job");
        if (uid == null || !uid.equals(Fb.uid()) || id == null) return Result.failure();
        DocumentReference job = Fb.db().collection("officialBroadcasts").document(id);
        try {
            DocumentSnapshot spec = Tasks.await(job.get(Source.SERVER), 20, TimeUnit.SECONDS);
            if (!uid.equals(spec.getString("adminId"))) return Result.failure();
            String text = spec.getString("text"), media = spec.getString("mediaUrl"), type = spec.getString("mediaType");
            DocumentSnapshot cursor = null;
            int processed = 0;
            while (!isStopped()) {
                if (!uid.equals(Fb.uid())) return Result.failure();
                Query query = Fb.db().collection("users").orderBy(FieldPath.documentId()).limit(100);
                if (cursor != null) query = query.startAfter(cursor);
                QuerySnapshot page = Tasks.await(query.get(Source.SERVER), 30, TimeUnit.SECONDS);
                if (page.isEmpty()) break;
                for (DocumentSnapshot user : page) {
                    if (isStopped()) return Result.retry();
                    if (!uid.equals(Fb.uid())) return Result.failure();
                    String to = user.getId();
                    DocumentReference note = Fb.db().collection("notifications").document("broadcast_" + id + "_" + to);
                    DocumentReference chat = Fb.db().collection("chats").document("team_" + to);
                    DocumentReference message = chat.collection("messages").document("broadcast_" + id);
                    Tasks.await(Fb.db().runTransaction(tx -> {
                        // Replaying after process death/retry must not add another message or unread increment.
                        if (tx.get(note).exists() || tx.get(message).exists()) return null;
                        Map<String, Object> n = new HashMap<>();
                        n.put("toUserId", to); n.put("fromUserId", Ui.TEAM_USER_ID);
                        n.put("fromUserName", "friendbook Team"); n.put("fromUserPhotoUrl", Ui.TEAM_AVATAR_TOKEN);
                        n.put("type", "announcement"); n.put("text", text); n.put("isRead", false);
                        n.put("broadcastId", id); n.put("mediaUrl", media); n.put("mediaType", type);
                        n.put("createdAt", FieldValue.serverTimestamp());
                        tx.set(note, n);
                        Map<String, Object> room = new HashMap<>();
                        room.put("participants", Arrays.asList(Ui.TEAM_USER_ID, to));
                        room.put("participantNames", Collections.singletonMap(Ui.TEAM_USER_ID, "friendbook Team"));
                        room.put("participantPhotos", Collections.singletonMap(Ui.TEAM_USER_ID, Ui.TEAM_AVATAR_TOKEN));
                        room.put("lastMessage", text == null || text.isEmpty() ? ("video".equals(type) ? "Video" : "Photo") : text);
                        room.put("lastMessageTime", FieldValue.serverTimestamp());
                        room.put("lastMessageTimeMs", System.currentTimeMillis());
                        room.put("unreadCount", Collections.singletonMap(to, FieldValue.increment(1)));
                        tx.set(chat, room, SetOptions.merge());
                        Map<String, Object> m = new HashMap<>();
                        m.put("senderId", Ui.TEAM_USER_ID); m.put("senderName", "friendbook Team");
                        m.put("senderPhoto", Ui.TEAM_AVATAR_TOKEN); m.put("receiverId", to);
                        m.put("text", text); m.put("type", type == null || type.isEmpty() ? "text" : type);
                        m.put("imageUrl", "image".equals(type) ? media : "");
                        m.put("videoUrl", "video".equals(type) ? media : "");
                        m.put("isDelivered", true); m.put("isSeen", false); m.put("broadcastId", id);
                        m.put("createdAt", FieldValue.serverTimestamp()); m.put("createdAtMs", System.currentTimeMillis());
                        tx.set(message, m);
                        return null;
                    }), 30, TimeUnit.SECONDS);
                    processed++;
                }
                cursor = page.getDocuments().get(page.size() - 1);
                Tasks.await(job.update("status", "sending", "processed", processed), 20, TimeUnit.SECONDS);
            }
            if (isStopped()) return Result.retry();
            Tasks.await(job.update("status", "complete", "processed", processed), 20, TimeUnit.SECONDS);
            return Result.success(); // Persistence completed, not a claim of FCM/device receipt.
        } catch (Exception e) {
            com.friendbook.social.push.PushTrace.failure("official_broadcast_failed", e);
            Throwable cause = e;
            while (cause.getCause() != null) cause = cause.getCause();
            boolean denied = cause instanceof D1DocumentStoreException
                    && ((D1DocumentStoreException) cause).getCode() == D1DocumentStoreException.Code.PERMISSION_DENIED;
            try { Tasks.await(job.update("status", denied ? "permission_denied" : "retrying"), 10, TimeUnit.SECONDS); }
            catch (Exception ignored) {}
            return denied ? Result.failure() : Result.retry();
        }
    }
}
