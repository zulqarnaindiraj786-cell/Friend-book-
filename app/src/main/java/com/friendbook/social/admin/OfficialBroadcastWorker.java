package com.friendbook.social.admin;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.*;
import com.friendbook.social.core.*;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.firestore.*;
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
        try {
            // The job row lives in Cloudflare D1, read with the same blocking transport the Worker
            // already uses: no Firestore query, only the ID token as the credential.
            Map<String, Object> spec = CloudflareDocs.readSync("officialBroadcasts", id);
            if (spec == null || !uid.equals(String.valueOf(spec.get("adminId")))) return Result.failure();
            String text = Format.str(spec, "text"), media = Format.str(spec, "mediaUrl"), type = Format.str(spec, "mediaType");
            int processed = 0;
            String cursor = "";
            while (!isStopped()) {
                if (!uid.equals(Fb.uid())) return Result.failure();
                // One page of accounts, walked by id so a retried job resumes from the same place.
                List<Map<String, Object>> page = CloudflareDocs.readSyncPage("users", 100, "uid", cursor);
                if (page.isEmpty()) break;
                for (Map<String, Object> user : page) {
                    if (isStopped()) return Result.retry();
                    if (!uid.equals(Fb.uid())) return Result.failure();
                    String to = String.valueOf(user.get("id"));
                    cursor = to;
                    if ("null".equals(to) || to.isEmpty() || Ui.TEAM_USER_ID.equals(to)) continue;
                    deliver(id, to, text, media, type);   // idempotent: one notification per account
                    processed++;
                }
                writeJobStatus(id, "sending", processed);
                if (page.size() < 100) break;
            }
            if (isStopped()) return Result.retry();
            writeJobStatus(id, "complete", processed);
            return Result.success(); // Persistence completed, not a claim of FCM/device receipt.
        } catch (Exception e) {
            com.friendbook.social.push.PushTrace.failure("official_broadcast_failed", e);
            Throwable cause = e;
            while (cause.getCause() != null) cause = cause.getCause();
            boolean denied = cause instanceof FirebaseFirestoreException
                    && ((FirebaseFirestoreException) cause).getCode() == FirebaseFirestoreException.Code.PERMISSION_DENIED;
            try { writeJobStatus(id, denied ? "permission_denied" : "retrying", -1); }
            catch (Exception ignored) {}
            return denied ? Result.failure() : Result.retry();
        }
    }

    /**
     * Delivers one announcement to one account, exactly once: the notification row has a deterministic
     * id, so a retried job overwrites the same row instead of notifying the user twice, and the chat
     * message id is deterministic for the same reason. Both writes go to Cloudflare D1.
     */
    private static boolean deliver(String jobId, String to, String text, String media, String type) {
        String noteId = "broadcast_" + jobId + "_" + to;
        if (CloudflareDocs.readSync("notifications", noteId) != null) return false;   // already delivered
        long now = System.currentTimeMillis();
        String body = text == null || text.isEmpty() ? ("video".equals(type) ? "Video" : "Photo") : text;
        Map<String, Object> note = new HashMap<>();
        note.put("toUserId", to); note.put("fromUserId", Ui.TEAM_USER_ID);
        note.put("fromUserName", "friendbook Team"); note.put("fromUserPhotoUrl", Ui.TEAM_AVATAR_TOKEN);
        note.put("type", "announcement"); note.put("text", text); note.put("isRead", false);
        note.put("broadcastId", jobId); note.put("mediaUrl", media); note.put("mediaType", type);
        note.put("createdAt", now);
        CloudflareDocs.writeSync("notifications", noteId, note);

        String chatId = "team_" + to;
        Map<String, Object> names = new HashMap<>();
        names.put(Ui.TEAM_USER_ID, "friendbook Team");
        Map<String, Object> photos = new HashMap<>();
        photos.put(Ui.TEAM_USER_ID, Ui.TEAM_AVATAR_TOKEN);
        Map<String, Object> unread = new HashMap<>();
        unread.put(to, 1);
        Map<String, Object> room = new HashMap<>();
        room.put("participants", Arrays.asList(Ui.TEAM_USER_ID, to));
        room.put("participantNames", names);
        room.put("participantPhotos", photos);
        room.put("lastMessage", body);
        room.put("lastMessageTimeMs", now);
        room.put("unreadCount", unread);
        CloudflareDocs.writeSync("chats", chatId, room);

        Map<String, Object> m = new HashMap<>();
        m.put("chatId", chatId); m.put("senderId", Ui.TEAM_USER_ID); m.put("senderName", "friendbook Team");
        m.put("senderPhoto", Ui.TEAM_AVATAR_TOKEN); m.put("receiverId", to);
        m.put("text", text); m.put("type", type == null || type.isEmpty() ? "text" : type);
        m.put("imageUrl", "image".equals(type) ? media : "");
        m.put("videoUrl", "video".equals(type) ? media : "");
        m.put("isDelivered", true); m.put("isSeen", false); m.put("broadcastId", jobId);
        m.put("createdAt", now); m.put("createdAtMs", now);
        CloudflareDocs.writeSync("messages", chatId + "_broadcast_" + jobId, m);
        return true;
    }

    /** Progress of one job, written to its D1 row (the screen watches that row). */
    private static void writeJobStatus(String id, String status, int processed) {
        Map<String, Object> patch = new HashMap<>();
        patch.put("status", status);
        if (processed >= 0) patch.put("processed", processed);
        CloudflareDocs.writeSync("officialBroadcasts", id, patch);
    }
}
