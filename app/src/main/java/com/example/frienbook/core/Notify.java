package com.example.frienbook.core;

import androidx.annotation.Nullable;

import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.WriteBatch;

import java.util.HashMap;
import java.util.Map;

/** Writes realtime notifications (same collection as the Flutter app). */
public class Notify {

    public static void send(String toUid, String type, String text) {
        send(toUid, type, text, null);
    }

    /** Creates a notification doc. Silently skips self-notifications. */
    public static void send(String toUid, String type, String text, @Nullable String postId) {
        String me = Fb.uid();
        if (me == null || toUid == null || toUid.isEmpty() || toUid.equals(me)) return;
        Map<String, Object> n = new HashMap<>();
        n.put("toUserId", toUid);
        n.put("fromUserId", me);
        n.put("fromUserName", Fb.myName());
        n.put("fromUserPhotoUrl", Fb.myPhoto());
        n.put("type", type);
        n.put("text", text);
        if (postId != null) n.put("postId", postId);
        n.put("isRead", false);
        n.put("createdAt", FieldValue.serverTimestamp());
        Fb.db().collection("notifications").add(n)
                .addOnSuccessListener(ref -> CloudflareDocs.mirror("notifications", ref.getId(), n));
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
