package com.friendbook.social.post;

import com.friendbook.social.core.Fb;
import com.friendbook.social.models.Models;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.SetOptions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Paged canonical comment history; legacy parent maps are read-only compatibility data. */
public class CommentsEngine {
    public static void showD1Drafts(android.app.Activity activity,String collection,String id){
        D1Drafts.show(activity,collection,id,data->{d1Changed(collection,id,data);
            String me=Fb.uid(),owner=data.optString("owner"),reply=data.optString("replyTo");org.json.JSONObject row=data.optJSONObject("comment");if(me==null||row==null)return;
            if(!owner.isEmpty()&&!owner.equals(me))com.friendbook.social.core.Notify.send(owner,"posts".equals(collection)?"comment":"reel_comment",Fb.myName()+" commented",id);
            if(!reply.isEmpty()&&!reply.equals(me))com.friendbook.social.core.Notify.send(reply,"reply",Fb.myName()+" replied to your comment",id);
            Models.Comment comment=fromD1(row,id);if("posts".equals(collection)&&com.friendbook.social.core.Mentions.hasBulk(comment.mentions))com.friendbook.social.push.FCMPush.bulkMention(id,comment.id);
        });
    }
    private static final List<Handle> D1_HANDLES=new ArrayList<>();
    private static boolean d1(String collection){return com.friendbook.social.core.D1Client.enabled(collection);}
    private static Models.Comment fromD1(org.json.JSONObject row,String key) {
        try{Models.Comment c=Models.Comment.fromMap(row.optString("id"),com.friendbook.social.core.D1Client.map(row));c.postId=key;c.canonicalStored=true;return c;}
        catch(org.json.JSONException e){throw new IllegalArgumentException("Invalid D1 comment",e);}
    }
    private static void d1Changed(String collection,String id,org.json.JSONObject data){
        for(Handle h:new ArrayList<>(D1_HANDLES))if(h.active() && collection.equals(h.collection) && id.equals(h.docId)){
            h.error="";if(data.has("pinned"))h.pinned=data.optString("pinned");
            if(data.has("removed")){String removed=data.optString("removed");h.deleted.add(removed);h.latest.remove(removed);h.older.remove(removed);}
            org.json.JSONObject row=data.optJSONObject("comment");if(row!=null){Models.Comment c=fromD1(row,h.key);h.deleted.remove(c.id);if(h.older.containsKey(c.id))h.older.put(c.id,c);else h.latest.put(c.id,c);}
            h.emit();
        }
        D1Reactions.invalidate(collection,id);
        com.friendbook.social.core.D1Client.changed(com.friendbook.social.core.D1Client.key(collection,id));
    }
    private static void d1Mutation(String collection,String id,org.json.JSONObject body){
        final String actor=Fb.uid();com.friendbook.social.core.D1Client.request(collection,id,"comments","PUT",body,null)
            .addOnSuccessListener(data->{if(java.util.Objects.equals(actor,Fb.uid()))d1Changed(collection,id,data);})
            .addOnFailureListener(error->{if(java.util.Objects.equals(actor,Fb.uid()))for(Handle h:new ArrayList<>(D1_HANDLES))if(h.active() && collection.equals(h.collection)&&id.equals(h.docId)){h.error="D1 change failed. Reopen comments to restore server state; no Firebase write was made.";h.emit();}});
    }


    public interface Listener {
        void onComments(List<Models.Comment> allComments, String pinnedCommentId);
    }

    /** One latest-50 listener; older pages are explicit one-shot cursor reads. */
    public static class Handle {
        private final List<ListenerRegistration> regs=new ArrayList<>();
        private final Map<String,Models.Comment> latest=new LinkedHashMap<>(), older=new LinkedHashMap<>();
        private final List<Models.Comment> legacy=new ArrayList<>();
        private com.google.firebase.firestore.Query query;
        private DocumentSnapshot cursor;
        private Listener listener;
        private String pinned="", actor=Fb.uid();
        private final Set<String> deleted=new HashSet<>();
        private boolean closed, loading, more=true, cursorReady;
        private int legacyLoaded=50, visible=50;
        private boolean d1Mode;private String collection,docId,key,nextD1;
        private void d1Page(boolean first){
            if(!active()||loading)return;loading=true;error="";emit();
            String query=first||nextD1==null?null:"?before="+android.net.Uri.encode(nextD1);
            com.friendbook.social.core.D1Client.request(collection,docId,"comments","GET",null,query).addOnSuccessListener(data->{
                if(!active())return;loading=false;
                org.json.JSONArray rows=data.optJSONArray("comments");if(rows==null){error="Invalid D1 response";emit();return;}
                try{for(int i=0;i<rows.length();i++){Models.Comment c=fromD1(rows.getJSONObject(i),key);if(first)latest.put(c.id,c);else older.put(c.id,c);
}
                    if(!first)visible+=50;nextD1=data.isNull("next")?null:data.optString("next",null);more=nextD1!=null;pinned=data.optString("pinned");emit();
                }catch(Exception e){error="Invalid D1 response";emit();}
            }).addOnFailureListener(e->{if(active()){loading=false;error="D1 page unavailable. Tap to retry.";emit();}});
        }
        public String error="";
        public void add(ListenerRegistration r) { if(r!=null) regs.add(r); }
        private boolean active() { return !closed && java.util.Objects.equals(actor,Fb.uid()); }
        public boolean hasMore() {
            if(d1Mode)return more || !error.isEmpty();
            Set<String> loaded=new HashSet<>(older.keySet()); loaded.addAll(latest.keySet()); loaded.removeAll(deleted);
            return more || legacyLoaded<legacy.size() || loaded.size()>visible;
        }
        public boolean isLoading() { return loading; }
        private void emit() {
            if(!active()) return;
            Map<String,Models.Comment> all=new LinkedHashMap<>();
            for(int i=0;i<Math.min(legacyLoaded,legacy.size());i++) all.put(legacy.get(i).id,legacy.get(i));
            all.putAll(older); all.putAll(latest);
            List<Models.Comment> result=new ArrayList<>();
            for(Models.Comment c:all.values()) if(!deleted.contains(c.id) && (Fb.me==null || Fb.me.blocked==null || !Fb.me.blocked.contains(c.authorId))) {
                c.pinned=c.id.equals(pinned); result.add(c);
            }
            result=com.friendbook.social.core.PageWindow.latest(result,
                    java.util.Comparator.comparingLong((Models.Comment c)->c.createdAt).thenComparing(c->c.id),visible);
            if(listener!=null) listener.onComments(result,pinned);
        }
        public void loadMore() {
            if(!active() || loading || !hasMore()) return;
            if(d1Mode){d1Page(nextD1==null);return;}
            if(!more) { legacyLoaded+=50; visible+=50; emit(); return; }
            if(!cursorReady || cursor==null) return;
            loading=true; error=""; emit();
            query.startAfter(cursor).limit(50).get().addOnSuccessListener(page -> {
                if(!active()) return; loading=false; visible+=50; legacyLoaded+=50;
                more=page.size()==50;
                if(!page.isEmpty()) cursor=page.getDocuments().get(page.size()-1);
                for(DocumentSnapshot doc:page) { Models.Comment c=Models.Comment.fromDoc(doc); older.put(c.id,c); }
                emit();
            }).addOnFailureListener(e -> { if(active()) { loading=false; error="Couldn't load older comments. Tap again to retry."; emit(); } });
        }
        public void remove() { closed=true; for(ListenerRegistration reg:regs) reg.remove(); regs.clear(); }
    }

    public static Handle watch(String collection,String docId,String queryKey,Listener listener) {
        Handle handle=new Handle(); handle.listener=listener;
        if(docId==null || docId.isEmpty()) return handle;
        if(d1(collection)){
            handle.d1Mode=true;handle.collection=collection;handle.docId=docId;handle.key=queryKey;
            D1_HANDLES.add(handle);handle.add(()->D1_HANDLES.remove(handle));handle.d1Page(true);return handle;
        }
        // D1 FIRST for comments written by this build. They are read from the document store and
        // merged into the same maps the pre-migration listener fills, by comment id.
        if(com.friendbook.social.core.CloudflareDocs.enabled()){
            handle.collection=collection;handle.docId=docId;handle.key=queryKey;
            com.friendbook.social.core.CloudflareDocs.read("comments",50,
                    com.friendbook.social.core.CloudflareDocs.Query.orderDesc("createdAt").where("postId", queryKey==null||queryKey.isEmpty()?docId:queryKey),
                    cloudPage -> {
                if(!handle.active() || cloudPage==null || cloudPage.documents.isEmpty()) return;
                for(java.util.Map<String,Object> row:cloudPage.documents){
                    Object id=row.get("id");
                    if(id==null) continue;
                    Models.Comment c=Models.Comment.fromMap(String.valueOf(id),row);
                    if(c==null || c.id==null) continue;
                    c.postId=handle.key;
                    handle.latest.put(c.id,c);
                    handle.deleted.remove(c.id);
                }
                handle.emit();
            });
        }
        DocumentReference parent=Fb.db().collection(collection).document(docId);
        handle.query=parent.collection("comments").orderBy("createdAt",com.google.firebase.firestore.Query.Direction.DESCENDING);
        // Compatibility only: one parent read. Never rebuild or expand its old commentsMap.
        parent.get().addOnSuccessListener(doc -> {
            if(!handle.active() || !doc.exists()) return;
            handle.pinned=com.friendbook.social.core.Format.str(doc.getData(),"pinnedCommentId");
            handle.legacy.addAll(Models.Comment.parseEmbeddedComments(queryKey,doc.getData(),handle.pinned));
            handle.legacy.sort((x,y)->Long.compare(y.createdAt,x.createdAt)); handle.emit();
        });
        handle.add(handle.query.limit(50).addSnapshotListener((page,error) -> {
            if(!handle.active()) return;
            if(page==null) { handle.error="Comments unavailable. Reopen to retry."; handle.emit(); return; }
            handle.older.putAll(handle.latest);
            for(com.google.firebase.firestore.DocumentChange change:page.getDocumentChanges()) {
                if(change.getType()==com.google.firebase.firestore.DocumentChange.Type.REMOVED) {
                    final String id=change.getDocument().getId();
                    change.getDocument().getReference().get().addOnSuccessListener(current -> {
                        if(!handle.active()) return;
                        if(!current.exists()) { handle.deleted.add(id); handle.older.remove(id); handle.latest.remove(id); }
                        handle.emit();
                    });
                }
            }
            handle.latest.clear();
            for(DocumentSnapshot doc:page) { Models.Comment c=Models.Comment.fromDoc(doc); handle.latest.put(c.id,c); handle.deleted.remove(c.id); }
            if(!handle.cursorReady && !page.getMetadata().isFromCache() && !page.getMetadata().hasPendingWrites()) {
                handle.cursorReady=true; handle.more=page.size()==50;
                if(!page.isEmpty()) handle.cursor=page.getDocuments().get(page.size()-1);
            }
            handle.emit();
        }));
        return handle;
    }

    public static Map<String, Object> toMap(Models.Comment c, String queryKey) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", c.id);
        m.put("postId", queryKey != null && !queryKey.isEmpty() ? queryKey : c.postId);
        m.put("authorId", c.authorId == null ? "" : c.authorId);
        m.put("authorName", c.authorName == null ? "friendbook User" : c.authorName);
        m.put("authorPhotoUrl", c.authorPhoto == null ? "" : c.authorPhoto);
        m.put("authorPhoto", c.authorPhoto == null ? "" : c.authorPhoto);
        m.put("mentions", c.mentions);
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
     * Writes a canonical comment + moderation mirror + one bounded parent preview atomically.
     * Frozen legacy maps are never expanded.
     */
    public static Models.Comment sendComment(final String collection,
                                             final String docId,
                                             final String queryKey,
                                             final String text,
                                             final String mediaUrl,
                                             final String parentId,
                                             final PostCallback cb) {
        return sendComment(collection, docId, queryKey, text, mediaUrl, parentId, new ArrayList<>(), cb);
    }

    public static Models.Comment sendComment(final String collection,
                                             final String docId,
                                             final String queryKey,
                                             final String text,
                                             final String mediaUrl,
                                             final String parentId,
                                             final List<Map<String,Object>> mentions,
                                             final PostCallback cb) {
        if (com.friendbook.social.core.Admin.isCurrentUserSuspended()) {
            if (cb != null) cb.onComplete(false, com.friendbook.social.core.Admin.getSuspensionMessage());
            return null;
        }
        String me = Fb.uid();
        if (me == null || docId == null || docId.isEmpty()) {
            if (cb != null) cb.onComplete(false, "Not signed in");
            return null;
        }
        final String newId = d1(collection)?java.util.UUID.randomUUID().toString():Fb.db().collection("comments").document().getId();
        final long now = System.currentTimeMillis();

        Models.Comment local = new Models.Comment();
        local.id = newId;
        local.postId = queryKey != null && !queryKey.isEmpty() ? queryKey : docId;
        local.authorId = me;
        local.authorName = Fb.myName();
        local.authorPhoto = Fb.myPhoto();
        local.content = text == null ? "" : text;
        local.mentions = mentions == null ? new ArrayList<>() : new ArrayList<>(mentions);
        local.imageUrl = mediaUrl == null ? "" : mediaUrl;
        local.parentId = parentId == null ? "" : parentId;
        local.createdAt = now;

        if(d1(collection)){
            org.json.JSONObject body=com.friendbook.social.core.D1Client.object("id",newId,"text",local.content,"mediaUrl",local.imageUrl,"parentId",local.parentId.isEmpty()?null:local.parentId,"mentions",new org.json.JSONArray(local.mentions));
            if(!D1Drafts.keep(com.friendbook.social.App.instance,me,collection,docId,body)){if(cb!=null)cb.onComplete(false,"Couldn't save draft. Nothing sent.");return null;}
            com.friendbook.social.core.D1Client.request(collection,docId,"comments","POST",body,null).addOnSuccessListener(data->{
                if(!java.util.Objects.equals(me,Fb.uid()))return;D1Drafts.ack(com.friendbook.social.App.instance,me,collection,docId,newId);d1Changed(collection,docId,data);if("posts".equals(collection)&&com.friendbook.social.core.Mentions.hasBulk(local.mentions))com.friendbook.social.push.FCMPush.bulkMention(docId,newId);if(cb!=null)cb.onComplete(true,null);
            }).addOnFailureListener(e->{if(!java.util.Objects.equals(me,Fb.uid()))return;
                d1Changed(collection,docId,com.friendbook.social.core.D1Client.object("removed",newId));if(cb!=null)cb.onComplete(false,"Comment not confirmed. Use Unsent comments to retry the same draft safely.");});
            return local;
        }
        final Map<String, Object> commentMap = toMap(local, local.postId);
        final DocumentReference parentRef = Fb.db().collection(collection).document(docId);

        // WRITE = Cloudflare. A comment written by this build is a D1 row under its parent, plus the
        // parent's counter bump - both in D1. The pre-migration batch below only runs when Cloudflare
        // is switched off, so nothing new is ever written back to the old source.
        if (com.friendbook.social.core.CloudflareDocs.enabled()) {
            // The parent's counter is a stored Counter field, incremented by the Worker on write - the
            // client never recomputes a total, so two devices can never disagree about it.
            commentMap.put("commentsDelta", 1L);
            com.friendbook.social.core.CloudflareDocs.write("comments", newId, commentMap, () -> {
                if (me.equals(Fb.uid()) && "posts".equals(collection) && com.friendbook.social.core.Mentions.hasBulk(local.mentions))
                    com.friendbook.social.push.FCMPush.bulkMention(docId, newId);
                if (cb != null) cb.onComplete(true, null);
            }, reason -> {
                com.friendbook.social.core.CloudflareOutbox.enqueue(com.friendbook.social.App.instance,
                        "comments", newId, com.friendbook.social.core.CloudflareDocs.sanitize(commentMap));
                if (cb != null) cb.onComplete(false, "Comment saved on this phone — it will post when you are online.");
            });
            return local;
        }

        // Offline-capable atomic batch: canonical comment, moderation mirror, bounded ONE-item preview + counter.
        // Legacy commentsMap is never expanded; administrator migration safely removes it after copying.
        com.google.firebase.firestore.WriteBatch batch=Fb.db().batch();
        batch.set(parentRef.collection("comments").document(newId),commentMap);
        local.canonicalStored=true;
        batch.set(Fb.db().collection("comments").document(newId),commentMap);
        Map<String,Object> patch=new HashMap<>();
        patch.put("lastComment",commentMap); patch.put("comments",FieldValue.increment(1));
        patch.put("commentsStorageVersion",2);
        batch.update(parentRef,patch);
        batch.commit().addOnSuccessListener(v -> {
            if(me.equals(Fb.uid()) && "posts".equals(collection) && com.friendbook.social.core.Mentions.hasBulk(local.mentions)) com.friendbook.social.push.FCMPush.bulkMention(docId,newId);
            if(cb!=null) cb.onComplete(true,null);
        }).addOnFailureListener(error -> { if(cb!=null) cb.onComplete(false,error.getMessage()); });

        return local;
    }

    /** Updates only the acting user reaction in canonical + moderation records. */
    public static void updateCommentReaction(String collection, String docId, Models.Comment c,
                                             String userId, String nextType) {
        if (c == null || c.id == null || docId == null || docId.isEmpty()
                || userId == null || userId.isEmpty()) return;
        if(d1(collection)){if(java.util.Objects.equals(userId,Fb.uid()))d1Mutation(collection,docId,com.friendbook.social.core.D1Client.object("op","react","id",c.id,"type",nextType));return;}
        Object rxVal = nextType == null ? FieldValue.delete() : nextType;
        Object likedByVal = nextType == null ? FieldValue.arrayRemove(userId) : FieldValue.arrayUnion(userId);

        Map<String, Object> up = new HashMap<>();
        Map<String, Object> rxSub = new HashMap<>();
        rxSub.put(userId, rxVal);
        up.put("reactions", rxSub);
        up.put("likedBy", likedByVal);
        up.put("likes", c.likes);
        mutateComment(collection,docId,c,up);
    }

    /** Edits canonical text/mentions and moderation mirror without growing parent maps. */
    public static void editComment(String collection, String docId, Models.Comment c, String newText) {
        if (c == null || c.id == null || docId == null || docId.isEmpty()) return;
        if(d1(collection)){d1Mutation(collection,docId,com.friendbook.social.core.D1Client.object("op","edit","id",c.id,"text",newText,"mentions",new org.json.JSONArray(c.mentions)));return;}
        Map<String, Object> up = new HashMap<>();
        up.put("mentions", c.mentions);
        up.put("content", newText);
        up.put("text", newText);
        up.put("edited", true);
        mutateComment(collection,docId,c,up);
    }

    /** Existing canonical records keep offline queued edits/reactions. Legacy promotion is transactional
     * to avoid overwriting a newer canonical edit with a frozen legacy copy. */
    private static void mutateComment(String collection,String docId,Models.Comment c,Map<String,Object> patch) {
        DocumentReference canonical=Fb.db().collection(collection).document(docId).collection("comments").document(c.id);
        DocumentReference mirror=Fb.db().collection("comments").document(c.id);
        if(c.canonicalStored) {
            com.google.firebase.firestore.WriteBatch batch=Fb.db().batch();
            batch.set(canonical,patch,SetOptions.merge()); batch.set(mirror,patch,SetOptions.merge()); batch.commit();
        } else {
            Fb.db().runTransaction(tx -> {
                if(!tx.get(canonical).exists()) tx.set(canonical,toMap(c,c.postId));
                tx.set(canonical,patch,SetOptions.merge()); tx.set(mirror,patch,SetOptions.merge()); return null;
            }).addOnSuccessListener(v -> c.canonicalStored=true);
        }
    }

    /** Pins/unpins a comment on a post (Post Author feature!). */
    public static void pinComment(String collection, String docId, String commentId, boolean pin) {
        if (docId == null || docId.isEmpty()) return;
        if(d1(collection)){d1Mutation(collection,docId,com.friendbook.social.core.D1Client.object("op","pin","id",commentId,"pin",pin));return;}
        Map<String, Object> dot = new HashMap<>();
        dot.put("pinnedCommentId", pin ? commentId : "");
        Fb.db().collection(collection).document(docId).update(dot);
    }

    /** Deletes a comment across parent doc `commentsMap`, subcollection, and root `comments`. */
    public static void deleteComment(String collection, String docId, Models.Comment c) {
        if (c == null || c.id == null) return;
        if(d1(collection)){d1Mutation(collection,docId,com.friendbook.social.core.D1Client.object("op","remove","id",c.id));return;}
        if (docId != null && !docId.isEmpty()) {
            Map<String, Object> dot = new HashMap<>();
            dot.put("commentsMap." + c.id, FieldValue.delete());
            dot.put("comments", FieldValue.increment(-1));
            dot.put("lastComment", FieldValue.delete());
            Fb.db().collection(collection).document(docId).update(dot);
            Fb.db().collection(collection).document(docId).collection("comments").document(c.id).delete();
        }
        Fb.db().collection("comments").document(c.id).delete();
    }
}
