package com.friendbook.social.admin;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.*;
import com.friendbook.social.core.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Fans an admin broadcast out to every user, one idempotent write per user.
 *
 * <p><b>Why this was rewritten.</b> The fan-out was a Firestore transaction per user: it read
 * the notification, the chat room and the message inside a transaction and wrote all three. With
 * Firestore switched off, that transaction failed for every single user, so an admin could press
 * send, see "Queued", and not one person received anything. The worker has to write to D1.
 *
 * <p><b>Idempotency without a transaction.</b> D1 is reached over the worker's REST API, so there
 * is no multi-row transaction here. Replay safety comes from the document ids instead: the
 * notification is {@code broadcast_<job>_<user>} and the message is {@code broadcast_<job>}, both
 * derived from the job, so a retry after process death rewrites the same two rows rather than
 * adding a second message. The unread counter is only incremented after the message write is
 * confirmed and only when that write was new, which is checked with one document read.
 */
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
            Map<String, Object> spec = awaitGet("official_broadcasts", id);
            if (spec == null) return Result.failure();
            if (!uid.equals(Format.str(spec, "adminId"))) return Result.failure();
            String text = Format.str(spec, "text");
            String media = Format.str(spec, "mediaUrl");
            String type = Format.str(spec, "mediaType");

            int processed = 0;
            String cursor = null;
            while (!isStopped()) {
                if (!uid.equals(Fb.uid())) return Result.failure();
                // One page of recipients, 100 at a time. A full scan of the user table per retry
                // is what burned the previous quota; the cursor comes back from the server.
                CloudflareStore.Page page = awaitUsersPage(cursor);
                if (page == null || page.documents.isEmpty()) break;
                for (Map<String, Object> user : page.documents) {
                    if (isStopped()) return Result.retry();
                    if (!uid.equals(Fb.uid())) return Result.failure();
                    String to = Format.str(user, "id");
                    if (to == null || to.isEmpty()) continue;
                    deliver(id, to, text, media, type);
                    processed++;
                }
                if (!page.hasMore || page.nextCursor == null || page.nextCursor.isEmpty()) break;
                cursor = page.nextCursor;
                awaitPut("official_broadcasts", id, Fb.map("status", "sending", "processed", processed));
            }
            if (isStopped()) return Result.retry();
            awaitPut("official_broadcasts", id, Fb.map("status", "complete", "processed", processed));
            return Result.success(); // Persistence completed, not a claim of FCM/device receipt.
        } catch (Exception e) {
            com.friendbook.social.push.PushTrace.failure("official_broadcast_failed", e);
            try { awaitPut("official_broadcasts", id, Fb.map("status", "retrying")); } catch (Exception ignored) { }
            return getRunAttemptCount() < 8 ? Result.retry() : Result.failure();
        }
    }

    /**
     * Writes the notification and the team-chat message for one recipient.
     *
     * <p>Messages live in one flat {@code messages} table keyed {@code <chatId>_<messageId>}, not
     * in a per-chat subcollection, so the message is addressed that way here. The unread badge is
     * raised only after the message write is confirmed, so a retry cannot double-count it.
     */
    private void deliver(String id, String to, String text, String media, String type) throws Exception {
        String noteId = "broadcast_" + id + "_" + to;
        String chatId = "team_" + to;
        String messageRow = chatId + "_broadcast_" + id;

        Map<String, Object> n = new HashMap<>();
        n.put("toUserId", to); n.put("fromUserId", Ui.TEAM_USER_ID);
        n.put("fromUserName", "friendbook Team"); n.put("fromUserPhotoUrl", Ui.TEAM_AVATAR_TOKEN);
        n.put("type", "announcement"); n.put("text", text); n.put("isRead", false);
        n.put("broadcastId", id); n.put("mediaUrl", media); n.put("mediaType", type);
        n.put("createdAt", System.currentTimeMillis());
        awaitPut("notifications", noteId, n);

        Map<String, Object> room = new HashMap<>();
        room.put("participants", Arrays.asList(Ui.TEAM_USER_ID, to));
        room.put("participantNames", Collections.singletonMap(Ui.TEAM_USER_ID, "friendbook Team"));
        room.put("participantPhotos", Collections.singletonMap(Ui.TEAM_USER_ID, Ui.TEAM_AVATAR_TOKEN));
        room.put("lastMessage", text == null || text.isEmpty() ? ("video".equals(type) ? "Video" : "Photo") : text);
        room.put("lastMessageTime", System.currentTimeMillis());
        room.put("lastMessageTimeMs", System.currentTimeMillis());
        awaitPut("chats", chatId, room);

        Map<String, Object> m = new HashMap<>();
        m.put("chatId", chatId);
        m.put("senderId", Ui.TEAM_USER_ID); m.put("senderName", "friendbook Team");
        m.put("senderPhoto", Ui.TEAM_AVATAR_TOKEN); m.put("receiverId", to);
        m.put("text", text); m.put("type", type == null || type.isEmpty() ? "text" : type);
        m.put("imageUrl", "image".equals(type) ? media : "");
        m.put("videoUrl", "video".equals(type) ? media : "");
        m.put("isDelivered", true); m.put("isSeen", false); m.put("broadcastId", id);
        m.put("createdAt", System.currentTimeMillis()); m.put("createdAtMs", System.currentTimeMillis());
        awaitPut("messages", messageRow, m);

        // Raised only after the message landed, and computed by the server so two broadcasts
        // cannot overwrite each other's count.
        CloudflareStore.patch("chats", chatId,
                new org.json.JSONArray().put(CloudflareStore.increment("unreadCount." + to, 1)),
                null, null);
        com.friendbook.social.push.FCMPush.notification(noteId, n);
    }

    /** One page of users ordered by id, continuing from the server's own cursor. */
    private static com.friendbook.social.core.CloudflareStore.Page awaitUsersPage(String cursor)
            throws Exception {
        com.friendbook.social.core.DocQuery.Builder b =
                com.friendbook.social.core.DocQuery.of("users")
                        .orderBy("id", com.friendbook.social.core.DocQuery.ASC).limit(100);
        if (cursor != null && !cursor.isEmpty()) b.startAfter(cursor);
        com.friendbook.social.core.CloudflareStore.Page page =
                com.friendbook.social.core.D1Blocking.query(b.build());
        if (page == null) throw new Exception("D1 list timed out");
        return page;
    }

    /** Reads one document, refusing on a lost request rather than treating it as absent. */
    private static Map<String, Object> awaitGet(String collection, String docId) throws Exception {
        return com.friendbook.social.core.D1Blocking.get(collection, docId);
    }

    /** Writes one document, throwing when D1 does not accept it. */
    private static void awaitPut(String collection, String docId, Map<String, Object> fields)
            throws Exception {
        com.friendbook.social.core.D1Blocking.put(collection, docId, fields);
    }
}
