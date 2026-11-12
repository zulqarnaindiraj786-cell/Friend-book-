package com.example.frienbook.core;

import android.content.Context;
import android.content.SharedPreferences;

import com.example.frienbook.BuildConfig;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.Query;

import java.util.Map;

/**
 * The update bridge: the moment a signed-in user opens a newer build, THEIR OWN existing data starts
 * living on Cloudflare - without touching a single byte of the old copy.
 *
 * The three product rules this class exists for:
 *  1. Firebase keeps authentication and nothing else that is new: every new write of this build goes
 *     to Cloudflare (see the mirror calls at each create site) as well as to the legacy store while
 *     old builds are still in the wild.
 *  2. A user who never updates stays on the old app and the old store: nothing here deletes, resets,
 *     prunes or rewrites Firestore, so their experience and their data are untouched forever.
 *  3. A user who does update gets their history carried over automatically: this one-time pass reads
 *     their own slices (own posts, reels, stories, comments, notifications, friend requests,
 *     friendships, chats and the last messages of each chat) and mirrors every document to D1.
 *
 * The pass is additive only (CloudflareDocs.mirror is a merge-upsert with no DELETE anywhere), it is
 * bounded (100 documents per slice, 50 chats / 50 messages per chat), it runs at most once per
 * versionCode (SharedPreferences flag, set before the work so parallel launches cannot double-run),
 * and it is silent: a failure simply means the next launch retries, because the flag only advances
 * on success-free idempotent work - re-mirroring the same document is harmless by design.
 */
public final class CloudMigration {

    private CloudMigration() {}

    private static final int SLICE_LIMIT = 100;
    private static final int CHAT_LIMIT = 50;

    /** Call once from the signed-in entry point; returns immediately, all work is asynchronous. */
    public static void runOnceAfterUpdate(Context ctx) {
        if (ctx == null || !CloudflareDocs.enabled()) return;
        final String me = Fb.uid();
        if (me == null || me.isEmpty()) return;
        final SharedPreferences sp = ctx.getApplicationContext()
                .getSharedPreferences("cf_migration", Context.MODE_PRIVATE);
        final int done = sp.getInt("version", 0);
        if (done >= BuildConfig.VERSION_CODE) return;
        sp.edit().putInt("version", BuildConfig.VERSION_CODE).apply();
        mirrorSelf(me);
    }

    private static void mirrorSelf(final String me) {
        // The user's own profile document first: every other slice hangs off this uid.
        Fb.db().collection("users").document(me).get()
                .addOnSuccessListener(d -> {
                    Map<String, Object> m = d.exists() ? d.getData() : null;
                    if (m != null) CloudflareDocs.mirror("users", me, m);
                });
        slice(Fb.db().collection("posts").whereEqualTo("authorId", me).limit(SLICE_LIMIT), "posts");
        slice(Fb.db().collection("reels").whereEqualTo("authorId", me).limit(SLICE_LIMIT), "reels");
        slice(Fb.db().collection("stories").whereEqualTo("authorId", me).limit(SLICE_LIMIT), "stories");
        slice(Fb.db().collection("comments").whereEqualTo("authorId", me).limit(SLICE_LIMIT), "comments");
        slice(Fb.db().collection("notifications").whereEqualTo("toUid", me).limit(SLICE_LIMIT), "notifications");
        slice(Fb.db().collection("friend_requests").whereEqualTo("fromUid", me).limit(SLICE_LIMIT), "friend_requests");
        slice(Fb.db().collection("friend_requests").whereEqualTo("toUid", me).limit(SLICE_LIMIT), "friend_requests");
        slice(Fb.db().collection("friends").whereArrayContains("participants", me).limit(SLICE_LIMIT), "friends");
        // Chats: the conversation documents plus a bounded tail of messages, keyed chatId~msgId
        // because D1 documents are flat while Firestore nests messages under each chat.
        Fb.db().collection("chats").whereArrayContains("participants", me).limit(CHAT_LIMIT).get()
                .addOnSuccessListener(snap -> {
                    if (snap == null) return;
                    for (DocumentSnapshot chat : snap) {
                        final String chatId = chat.getId();
                        Map<String, Object> cm = chat.getData();
                        if (cm != null) CloudflareDocs.mirror("chats", chatId, cm);
                        Fb.db().collection("chats").document(chatId).collection("messages")
                                .limit(CHAT_LIMIT).get()
                                .addOnSuccessListener(msgs -> {
                                    if (msgs == null) return;
                                    for (DocumentSnapshot msg : msgs) {
                                        Map<String, Object> mm = msg.getData();
                                        if (mm == null) continue;
                                        mm.put("chatId", chatId);
                                        CloudflareDocs.mirror("messages", chatId + "~" + msg.getId(), mm);
                                    }
                                });
                    }
                });
    }

    private static void slice(Query q, final String collection) {
        q.get().addOnSuccessListener(snap -> {
            if (snap == null) return;
            for (DocumentSnapshot d : snap) {
                Map<String, Object> m = d.getData();
                if (m != null) CloudflareDocs.mirror(collection, d.getId(), m);
            }
        });
    }
}
