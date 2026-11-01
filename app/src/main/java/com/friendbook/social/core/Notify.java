package com.friendbook.social.core;

import androidx.annotation.Nullable;

import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.firestore.WriteBatch;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Writes realtime notifications and official friendbook Team messages (with Crown-F emblem). */
public class Notify {

    public static void send(String toUid, String type, String text) {
        send(toUid, type, text, null);
    }

    /** Creates a notification doc. Silently skips self-notifications except security login alerts. */
    public static void send(String toUid, String type, String text, @Nullable String postId) {
        String me = Fb.uid();
        if (me == null || toUid == null || toUid.isEmpty()) return;
        if (toUid.equals(me) && !"security".equals(type)) return;
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
        Fb.db().collection("notifications").add(n).addOnSuccessListener(ref ->
                com.friendbook.social.push.FCMPush.notification(ref.getId(), n));
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

    public static void markAllRead(java.util.List<com.google.firebase.firestore.DocumentSnapshot> docs) {
        WriteBatch b = Fb.db().batch();
        for (com.google.firebase.firestore.DocumentSnapshot d : docs)
            b.update(d.getReference(), "isRead", true);
        b.commit();
    }
}
