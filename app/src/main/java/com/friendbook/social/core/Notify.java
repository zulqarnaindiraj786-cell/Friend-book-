package com.friendbook.social.core;

import androidx.annotation.Nullable;

import com.friendbook.social.d1.documents.DocumentReference;
import com.friendbook.social.d1.documents.FieldValue;
import com.friendbook.social.d1.documents.SetOptions;
import com.friendbook.social.d1.documents.WriteBatch;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Writes realtime notifications and official friendbook Team messages (with Crown-F emblem). */
public class Notify {
    // Pending operations only: failures and completed events are never retained.
    private static final java.util.Set<java.util.List<String>> PENDING_EVENTS =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    public static void send(String toUid, String type, String text) {
        send(toUid, type, text, null);
    }

    /** Commit both sides before creating a follow notification / calling the push gateway. */
    public static com.google.android.gms.tasks.Task<Void> follow(String toUid, boolean following) {
        String me = Fb.uid();
        if (me == null || toUid == null || toUid.isEmpty() || me.equals(toUid))
            return com.google.android.gms.tasks.Tasks.forException(new IllegalArgumentException("Invalid follow target"));
        WriteBatch batch = Fb.db().batch();
        batch.set(Fb.db().collection("users").document(toUid),
                Fb.map("followers", following ? FieldValue.arrayUnion(me) : FieldValue.arrayRemove(me)), SetOptions.merge());
        batch.set(Fb.db().collection("users").document(me),
                Fb.map("following", following ? FieldValue.arrayUnion(toUid) : FieldValue.arrayRemove(toUid)), SetOptions.merge());
        return batch.commit().addOnSuccessListener(v -> {
            if (following && me.equals(Fb.uid())) send(toUid, "follow", "started following you");
        }).addOnFailureListener(e -> android.util.Log.w("FriendBookPush", "Follow write failed; no push sent"));
    }

    /** Creates a notification doc. Silently skips self-notifications except security login alerts. */
    public static void send(String toUid, String type, String text, @Nullable String postId) {
        sendInternal(toUid,type,text,postId,null);
    }

    /** Stable saved-comment identity prevents replay callbacks creating new inbox rows. */
    public static void sendForComment(String toUid,String type,String text,String collection,String postId,String commentId) {
        String eventKey=NotificationEventKey.forComment(Fb.uid(),toUid,type,collection+"/"+postId,commentId);
        sendInternal(toUid,type,text,postId,eventKey);
    }

    private static void sendInternal(String toUid,String type,String text,@Nullable String postId,@Nullable String savedEventKey) {
        String me = Fb.uid();
        if (me == null || toUid == null || toUid.isEmpty()) {
            com.friendbook.social.push.PushTrace.stage("notification_skipped", "reason=missing_actor_or_receiver"); return;
        }
        if (toUid.equals(me) && !"security".equals(type)) {
            com.friendbook.social.push.PushTrace.stage("notification_skipped", "reason=self_notification"); return;
        }
        com.friendbook.social.push.PushTrace.stage("notification_write_start", "type="
                + com.friendbook.social.push.PushTrace.label(type) + " actor="
                + com.friendbook.social.push.PushTrace.id(me) + " receiver=" + com.friendbook.social.push.PushTrace.id(toUid));
        boolean asTeam = "team".equals(type) || "announcement".equals(type)
                || "tick".equals(type) || "ban".equals(type);
        Map<String, Object> n = new HashMap<>();
        n.put("toUserId", toUid);
        n.put("fromUserId", asTeam ? Ui.TEAM_USER_ID : me);
        n.put("fromUserName", asTeam ? "friendbook Team" : Fb.myName());
        n.put("fromUserPhotoUrl", asTeam ? Ui.TEAM_AVATAR_TOKEN : Fb.myPhoto());
        n.put("type", type);
        if (type != null && type.startsWith("reel_")) n.put("contentType", "reel");
        n.put("text", text);
        if (postId != null) n.put("postId", postId);
        n.put("isRead", false);
        n.put("createdAt", FieldValue.serverTimestamp());
        String eventKey=savedEventKey!=null?savedEventKey:NotificationEventKey.forAction(me,toUid,type,postId);
        if (eventKey != null) {
            java.util.List<String> pendingKey=java.util.Arrays.asList(me,eventKey);
            if(!PENDING_EVENTS.add(pendingKey))return;
            try {
            DocumentReference ref=Fb.db().collection("notifications").document(eventKey);
            ref.create(n).addOnSuccessListener(v -> {
                if (me.equals(Fb.uid())) com.friendbook.social.push.FCMPush.notification(ref.getId(), n);
            }).addOnFailureListener(e -> {
                if (!(e instanceof com.friendbook.social.d1.documents.D1DocumentStoreException)
                        || ((com.friendbook.social.d1.documents.D1DocumentStoreException)e).getCode()
                        != com.friendbook.social.d1.documents.D1DocumentStoreException.Code.ALREADY_EXISTS)
                    com.friendbook.social.push.PushTrace.failure("notification_write_failed", e);
            }).addOnCompleteListener(task -> PENDING_EVENTS.remove(pendingKey));
            } catch(RuntimeException error) {
                PENDING_EVENTS.remove(pendingKey);
                throw error;
            }
        } else Fb.db().collection("notifications").add(n).addOnSuccessListener(ref -> {
            com.friendbook.social.push.PushTrace.stage("notification_write_ok", "event="
                    + com.friendbook.social.push.PushTrace.id(ref.getId()));
            if (me.equals(Fb.uid())) com.friendbook.social.push.FCMPush.notification(ref.getId(), n);
        }).addOnFailureListener(e -> com.friendbook.social.push.PushTrace.failure("notification_write_failed", e));
    }

    /**
     * Sends an official "friendbook Team" message that delivers BOTH:
     * 1. A notification with the dark Crown-F emblem (`Ui.TEAM_AVATAR_TOKEN`)
     * 2. An official chat message in Messenger (`chats/team_<toUid>`) showing the Crown-F icon!
     */
    public static void sendOfficialTeamMessage(String toUid, String type, String text, boolean alsoInChat) {
        if (toUid == null || toUid.isEmpty() || text == null || text.isEmpty()) return;
        Map<String, Object> n = new HashMap<>();
        n.put("toUserId", toUid);
        n.put("fromUserId", Ui.TEAM_USER_ID);
        n.put("fromUserName", "friendbook Team");
        n.put("fromUserPhotoUrl", Ui.TEAM_AVATAR_TOKEN);
        n.put("type", type == null ? "team" : type);
        n.put("text", text);
        n.put("isRead", false);
        n.put("createdAt", FieldValue.serverTimestamp());
        Fb.db().collection("notifications").add(n).addOnSuccessListener(ref ->
                com.friendbook.social.push.FCMPush.notification(ref.getId(), n));

        if (!alsoInChat) return;
        String chatId = "team_" + toUid;
        long now = System.currentTimeMillis();
        DocumentReference chatRef = Fb.db().collection("chats").document(chatId);

        Map<String, String> names = new HashMap<>();
        names.put(Ui.TEAM_USER_ID, "friendbook Team");
        Map<String, String> photos = new HashMap<>();
        photos.put(Ui.TEAM_USER_ID, Ui.TEAM_AVATAR_TOKEN);
        Map<String, Object> unread = new HashMap<>();
        unread.put(toUid, FieldValue.increment(1));

        Map<String, Object> room = new HashMap<>();
        room.put("participants", Arrays.asList(Ui.TEAM_USER_ID, toUid));
        room.put("participantNames", names);
        room.put("participantPhotos", photos);
        room.put("lastMessage", text);
        room.put("lastMessageTime", FieldValue.serverTimestamp());
        room.put("lastMessageTimeMs", now);
        room.put("unreadCount", unread);
        chatRef.set(room, SetOptions.merge());

        Map<String, Object> msg = new HashMap<>();
        msg.put("senderId", Ui.TEAM_USER_ID);
        msg.put("senderName", "friendbook Team");
        msg.put("senderPhoto", Ui.TEAM_AVATAR_TOKEN);
        msg.put("receiverId", toUid);
        msg.put("text", text);
        msg.put("type", "text");
        msg.put("isDelivered", true);
        msg.put("isSeen", false);
        msg.put("createdAt", FieldValue.serverTimestamp());
        msg.put("createdAtMs", now);
        chatRef.collection("messages").add(msg);
    }

    /** Sends a Group invitation notification opening directly into GroupDetailActivity. */
    public static void sendGroupInvite(String toUid, String groupId, String groupName) {
        String me = Fb.uid();
        if (me == null || toUid == null || toUid.isEmpty() || toUid.equals(me)) return;
        Map<String, Object> n = new HashMap<>();
        n.put("toUserId", toUid);
        n.put("fromUserId", me);
        n.put("fromUserName", Fb.myName());
        n.put("fromUserPhotoUrl", Fb.myPhoto());
        n.put("type", "group_invite");
        n.put("groupId", groupId);
        n.put("text", "invited you to join group \"" + groupName + "\" 👥");
        n.put("isRead", false);
        n.put("createdAt", FieldValue.serverTimestamp());
        Fb.db().collection("notifications").add(n).addOnSuccessListener(ref ->
                com.friendbook.social.push.FCMPush.notification(ref.getId(), n));
    }

    /** Like/love/etc. on a post (respects local preference of the sender device). */
    public static void like(android.content.Context c, String toUid, String postId) {
        if (Prefs.notifLikes(c)) send(toUid, "like", "reacted to your post", postId);
    }

    public static void comment(android.content.Context c, String toUid, String postId) {
        if (Prefs.notifComments(c)) send(toUid, "comment", "commented on your post", postId);
    }

    public static void markAllRead(java.util.List<com.friendbook.social.d1.documents.DocumentSnapshot> docs) {
        WriteBatch b = Fb.db().batch();
        for (com.friendbook.social.d1.documents.DocumentSnapshot d : docs)
            b.update(d.getReference(), "isRead", true);
        b.commit();
    }
}
