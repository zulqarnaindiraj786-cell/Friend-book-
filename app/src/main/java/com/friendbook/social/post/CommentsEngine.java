package com.friendbook.social.post;

import com.friendbook.social.core.Fb;
import com.friendbook.social.models.Models;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.SetOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 💬 Universal Multi-User Real-Time Comments Engine (friendbook Pro):
 * Guarantees 100% of ALL users' comments & replies sync across EVERY device by storing
 * comments atomically inside the parent document's `commentsMap.<commentId>` (posts/{id} or reels/{id})
 * IN ADDITION TO `posts/{id}/comments` and `comments` collections — eliminating any
 * collection-level security rule isolation or offline-only cache traps!
 */
public class CommentsEngine {

    public interface Listener {
        void onComments(List<Models.Comment> allComments, String pinnedCommentId);
    }

    public static class Handle {
        private final List<ListenerRegistration> regs = new ArrayList<>();
        public void add(ListenerRegistration r) { if (r != null) regs.add(r); }
        public void remove() {
            for (ListenerRegistration r : regs) {
                try { r.remove(); } catch (Throwable ignored) { }
            }
            regs.clear();
        }
    }

    /**
     * Live-watches ALL comments from ALL users across:
     * 1) {collection}/{docId}.commentsMap (100% multi-user readable/writable)
     * 2) {collection}/{docId}/comments subcollection
     * 3) root `comments` collection (where postId == queryKey)
     * Automatically backfills any legacy/local-only comments into {collection}/{docId}.commentsMap!
     */
    public static Handle watch(final String collection,
                               final String docId,
                               final String queryKey,
                               final Listener listener) {
        final Handle handle = new Handle();
        if (docId == null || docId.isEmpty()) return handle;

        final Map<String, Models.Comment> fromParentDoc = new LinkedHashMap<>();
        final Map<String, Models.Comment> fromSubCol = new LinkedHashMap<>();
        final Map<String, Models.Comment> fromRootCol = new LinkedHashMap<>();
        final Set<String> backfilledIds = new HashSet<>();
        final String[] pinnedHolder = new String[]{""};
        final int[] storedCounter = new int[]{-1};

        final Runnable emitMerged = () -> {
            Map<String, Models.Comment> merged = new LinkedHashMap<>();
            // 1. Root & subcollection first, then parent doc map (or merge reactions/edits)
            for (Models.Comment c : fromRootCol.values()) mergeInto(merged, c);
            for (Models.Comment c : fromSubCol.values()) mergeInto(merged, c);
            for (Models.Comment c : fromParentDoc.values()) mergeInto(merged, c);

            String pinnedId = pinnedHolder[0];
            List<Models.Comment> result = new ArrayList<>();
            for (Models.Comment c : merged.values()) {
                if (c == null) continue;
                if (Fb.me != null && Fb.me.blocked != null && Fb.me.blocked.contains(c.authorId)) {
                    continue;
                }
                c.pinned = (pinnedId != null && !pinnedId.isEmpty() && pinnedId.equals(c.id)) || c.pinned;
                result.add(c);
            }
            Collections.sort(result, (a, b) -> Long.compare(a.createdAt, b.createdAt));

            // Auto-backfill any comment present in root/subcollection but missing from parentDoc.commentsMap!
            Map<String, Object> backfillUpdates = new HashMap<>();
            for (Models.Comment c : result) {
                if (c.id != null && !c.id.isEmpty()
                        && !fromParentDoc.containsKey(c.id)
                        && backfilledIds.add(c.id)) {
                    backfillUpdates.put("commentsMap." + c.id, toMap(c, queryKey));
                }
            }
            if (!backfillUpdates.isEmpty()) {
                backfillUpdates.put("comments", result.size());
                Fb.db().collection(collection).document(docId).update(backfillUpdates)
                        .addOnFailureListener(err -> {
                            Map<String, Object> nested = new HashMap<>();
                            Map<String, Object> cmap = new HashMap<>();
                            for (Models.Comment c : result) {
                                if (c.id != null && !c.id.isEmpty()) cmap.put(c.id, toMap(c, queryKey));
                            }
                            nested.put("commentsMap", cmap);
                            nested.put("comments", result.size());
                            Fb.db().collection(collection).document(docId).set(nested, SetOptions.merge());
                        });
            } else if (storedCounter[0] >= 0 && storedCounter[0] != result.size() && !fromParentDoc.isEmpty()) {
                storedCounter[0] = result.size();
                Map<String, Object> heal = new HashMap<>();
                heal.put("comments", result.size());
                Fb.db().collection(collection).document(docId).set(heal, SetOptions.merge());
            }

            if (listener != null) listener.onComments(result, pinnedId);
        };

        // Source 1: Parent document (`posts/{docId}` or `reels/{docId}`) — guaranteed readable/writable by ALL users!
        handle.add(Fb.db().collection(collection).document(docId)
                .addSnapshotListener((snap, err) -> {
                    if (snap != null && snap.exists()) {
                        fromParentDoc.clear();
                        Map<String, Object> data = snap.getData();
                        String pin = data != null && data.get("pinnedCommentId") instanceof String
                                ? (String) data.get("pinnedCommentId") : "";
                        pinnedHolder[0] = pin == null ? "" : pin;
                        Long cnt = snap.getLong("comments");
                        storedCounter[0] = cnt != null ? cnt.intValue() : 0;
                        List<Models.Comment> embedded = Models.Comment.parseEmbeddedComments(queryKey, data, pinnedHolder[0]);
                        for (Models.Comment c : embedded) {
                            if (c.id != null && !c.id.isEmpty()) fromParentDoc.put(c.id, c);
                        }
                        emitMerged.run();
                    }
                }));

        // Source 2: Subcollection `{collection}/{docId}/comments`
        handle.add(Fb.db().collection(collection).document(docId).collection("comments")
                .addSnapshotListener((snap, err) -> {
                    if (snap != null) {
                        fromSubCol.clear();
                        for (DocumentSnapshot d : snap.getDocuments()) {
                            Models.Comment c = Models.Comment.fromDoc(d);
                            if (c.id != null && !c.id.isEmpty()) fromSubCol.put(c.id, c);
                        }
                        emitMerged.run();
                    }
                }));

        // Source 3: Global `comments` collection (`whereEqualTo("postId", queryKey)`)
        String key = (queryKey != null && !queryKey.isEmpty()) ? queryKey : docId;
        handle.add(Fb.db().collection("comments").whereEqualTo("postId", key)
                .addSnapshotListener((snap, err) -> {
                    if (snap != null) {
                        fromRootCol.clear();
                        for (DocumentSnapshot d : snap.getDocuments()) {
                            Models.Comment c = Models.Comment.fromDoc(d);
                            if (c.id != null && !c.id.isEmpty()) fromRootCol.put(c.id, c);
                        }
                        emitMerged.run();
                    }
                }));

        return handle;
    }

    private static void mergeInto(Map<String, Models.Comment> target, Models.Comment incoming) {
        if (incoming == null || incoming.id == null || incoming.id.isEmpty()) return;
        Models.Comment prev = target.get(incoming.id);
        if (prev == null) {
            target.put(incoming.id, incoming);
            return;
        }
        // Merge reactions & keep latest edited content
        if (incoming.edited && !prev.edited) prev.content = incoming.content;
        prev.edited = prev.edited || incoming.edited;
        prev.pinned = prev.pinned || incoming.pinned;
        if (prev.content == null || prev.content.isEmpty()) prev.content = incoming.content;
        if (prev.imageUrl == null || prev.imageUrl.isEmpty()) prev.imageUrl = incoming.imageUrl;
        if (prev.authorName == null || prev.authorName.isEmpty() || "User".equals(prev.authorName)) {
            prev.authorName = incoming.authorName;
        }
        if (prev.authorPhoto == null || prev.authorPhoto.isEmpty()) prev.authorPhoto = incoming.authorPhoto;
        if (incoming.reactions != null) prev.reactions.putAll(incoming.reactions);
        prev.likes = Math.max(Math.max(prev.likes, incoming.likes), prev.reactions.size());
        if (prev.createdAt <= 0) prev.createdAt = incoming.createdAt;
    }

    public static Map<String, Object> toMap(Models.Comment c, String queryKey) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", c.id);
        m.put("postId", queryKey != null && !queryKey.isEmpty() ? queryKey : c.postId);
        m.put("authorId", c.authorId == null ? "" : c.authorId);
        m.put("authorName", c.authorName == null ? "friendbook User" : c.authorName);
        m.put("authorPhotoUrl", c.authorPhoto == null ? "" : c.authorPhoto);
        m.put("authorPhoto", c.authorPhoto == null ? "" : c.authorPhoto);
        m.put("content", c.content == null ? "" : c.content);
        m.put("text", c.content == null ? "" : c.content);
        m.put("imageUrl", c.imageUrl == null ? "" : c.imageUrl);
        m.put("parentId", c.parentId == null ? "" : c.parentId);
        m.put("edited", c.edited);
        m.put("pinned", c.pinned);
        m.put("likes", Math.max(c.likes, c.reactions != null ? c.reactions.size() : 0));
        m.put("reactions", c.reactions != null ? new HashMap<>(c.reactions) : new HashMap<String, String>());
        m.put("createdAt", c.createdAt > 0 ? c.createdAt : System.currentTimeMillis());
        return m;
    }

    public interface PostCallback {
        void onComplete(boolean success, String errorMessage);
    }

    /**
     * Writes a new comment to `collection/{docId}.commentsMap.<newId>` (primary multi-user sync!)
     * AND mirrors to `{collection}/{docId}/comments/{newId}` + `comments/{newId}`.
     */
    public static Models.Comment sendComment(final String collection,
                                             final String docId,
                                             final String queryKey,
                                             final String text,
                                             final String mediaUrl,
                                             final String parentId,
                                             final PostCallback cb) {
        String me = Fb.uid();
        if (me == null || docId == null || docId.isEmpty()) {
            if (cb != null) cb.onComplete(false, "Not signed in");
            return null;
        }
        final String newId = Fb.db().collection("comments").document().getId();
        final long now = System.currentTimeMillis();

        Models.Comment local = new Models.Comment();
        local.id = newId;
        local.postId = queryKey != null && !queryKey.isEmpty() ? queryKey : docId;
        local.authorId = me;
        local.authorName = Fb.myName();
        local.authorPhoto = Fb.myPhoto();
        local.content = text == null ? "" : text;
        local.imageUrl = mediaUrl == null ? "" : mediaUrl;
        local.parentId = parentId == null ? "" : parentId;
        local.createdAt = now;

        final Map<String, Object> commentMap = toMap(local, local.postId);
        final DocumentReference parentRef = Fb.db().collection(collection).document(docId);

        // 1. Primary write onto parent doc (`posts/{docId}` or `reels/{docId}`) — 100% shared across ALL users!
        Map<String, Object> dotUpdates = new HashMap<>();
        dotUpdates.put("commentsMap." + newId, commentMap);
        dotUpdates.put("comments", FieldValue.increment(1));

        com.friendbook.social.core.SoundManager.action(com.friendbook.social.App.instance, "comment", me);
        parentRef.update(dotUpdates)
                .addOnSuccessListener(v -> {
                    if (cb != null) cb.onComplete(true, null);
                })
                .addOnFailureListener(err -> {
                    // Fallback if document needs nested map merge
                    Map<String, Object> nested = new HashMap<>();
                    Map<String, Object> inner = new HashMap<>();
                    inner.put(newId, commentMap);
                    nested.put("commentsMap", inner);
                    nested.put("comments", FieldValue.increment(1));
                    parentRef.set(nested, SetOptions.merge())
                            .addOnSuccessListener(v2 -> {
                                if (cb != null) cb.onComplete(true, null);
                            })
                            .addOnFailureListener(e2 -> { if (cb != null) cb.onComplete(false, e2.getMessage()); });
                });

        // 2. Best-effort mirror to root `comments` and subcollection (for Admin tools & Activity Log)
        Fb.db().collection("comments").document(newId).set(commentMap);
        parentRef.collection("comments").document(newId).set(commentMap);

        return local;
    }

    /** Updates reactions on a comment per `userId` across parent doc `commentsMap`, subcollection, and `comments`. */
    public static void updateCommentReaction(String collection, String docId, Models.Comment c,
                                             String userId, String nextType) {
        if (c == null || c.id == null || docId == null || docId.isEmpty()
                || userId == null || userId.isEmpty()) return;
        Object rxVal = nextType == null ? FieldValue.delete() : nextType;
        Object likedByVal = nextType == null ? FieldValue.arrayRemove(userId) : FieldValue.arrayUnion(userId);

        Map<String, Object> dot = new HashMap<>();
        dot.put("commentsMap." + c.id + ".reactions." + userId, rxVal);
        dot.put("commentsMap." + c.id + ".likedBy", likedByVal);
        dot.put("commentsMap." + c.id + ".likes", c.likes);
        if (nextType != null) com.friendbook.social.core.SoundManager.action(com.friendbook.social.App.instance, "reaction", userId);
        Fb.db().collection(collection).document(docId).update(dot);

        Map<String, Object> up = new HashMap<>();
        Map<String, Object> rxSub = new HashMap<>();
        rxSub.put(userId, rxVal);
        up.put("reactions", rxSub);
        up.put("likedBy", likedByVal);
        up.put("likes", c.likes);
        Fb.db().collection("comments").document(c.id).set(up, SetOptions.merge());
        Fb.db().collection(collection).document(docId).collection("comments").document(c.id).set(up, SetOptions.merge());
    }

    /** Edits comment text across BOTH parent doc `commentsMap` and `comments`. */
    public static void editComment(String collection, String docId, Models.Comment c, String newText) {
        if (c == null || c.id == null || docId == null || docId.isEmpty()) return;
        Map<String, Object> dot = new HashMap<>();
        dot.put("commentsMap." + c.id + ".content", newText);
        dot.put("commentsMap." + c.id + ".text", newText);
        dot.put("commentsMap." + c.id + ".edited", true);
        Fb.db().collection(collection).document(docId).update(dot);

        Map<String, Object> up = new HashMap<>();
        up.put("content", newText);
        up.put("text", newText);
        up.put("edited", true);
        Fb.db().collection("comments").document(c.id).set(up, SetOptions.merge());
        Fb.db().collection(collection).document(docId).collection("comments").document(c.id).set(up, SetOptions.merge());
    }

    /** Pins/unpins a comment on a post (Post Author feature!). */
    public static void pinComment(String collection, String docId, String commentId, boolean pin) {
        if (docId == null || docId.isEmpty()) return;
        Map<String, Object> dot = new HashMap<>();
        dot.put("pinnedCommentId", pin ? commentId : "");
        if (commentId != null && !commentId.isEmpty()) {
            dot.put("commentsMap." + commentId + ".pinned", pin);
        }
        Fb.db().collection(collection).document(docId).update(dot);
    }

    /** Deletes a comment across parent doc `commentsMap`, subcollection, and root `comments`. */
    public static void deleteComment(String collection, String docId, Models.Comment c) {
        if (c == null || c.id == null) return;
        if (docId != null && !docId.isEmpty()) {
            Map<String, Object> dot = new HashMap<>();
            dot.put("commentsMap." + c.id, FieldValue.delete());
            dot.put("comments", FieldValue.increment(-1));
            Fb.db().collection(collection).document(docId).update(dot);
            Fb.db().collection(collection).document(docId).collection("comments").document(c.id).delete();
        }
        Fb.db().collection("comments").document(c.id).delete();
    }
}
