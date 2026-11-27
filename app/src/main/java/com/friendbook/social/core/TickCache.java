package com.friendbook.social.core;

import android.widget.ImageView;
import com.friendbook.social.models.Models;
import com.friendbook.social.d1.documents.DocumentReference;
import com.friendbook.social.d1.documents.DocumentSnapshot;
import com.friendbook.social.d1.documents.Source;

/** Account-scoped cosmetic badges: cache first, one flight per unknown author. */
public final class TickCache {
    private TickCache() { }
    private static final BadgeLookupState<ImageView> STATE = new BadgeLookupState<>();

    /** Bind only locally known badge data; batch-oriented callers hydrate profiles separately. */
    public static void attachCached(ImageView iv,String uid) {
        if(iv==null)return;
        String actor=Fb.uid();
        if(uid!=null && !uid.isEmpty() && STATE.cached(actor,uid)==null){
            Models.User user=FastCache.getUser(uid);
            if(user!=null)STATE.put(actor,uid,user.tickLevel==null?"none":user.tickLevel);
        }
        Icons.setTickBadge(iv,STATE.cached(actor,uid)==null?"none":STATE.cached(actor,uid),false);
    }

    public static void attach(ImageView iv, String uid) {
        if (iv == null) return;
        String actor=Fb.uid();
        if (uid!=null && !uid.isEmpty() && STATE.cached(actor,uid)==null) {
            Models.User user=FastCache.getUser(uid);
            if (user!=null) STATE.put(actor,uid,user.tickLevel==null?"none":user.tickLevel);
        }
        BadgeLookupState.Flight<ImageView> flight=STATE.bind(actor,uid,iv,android.os.SystemClock.elapsedRealtime());
        String level=STATE.cached(actor,uid);
        Icons.setTickBadge(iv,level==null?"none":level,false);
        if (flight==null) return;
        DocumentReference author=Fb.db().collection("users").document(uid);
        author.get(Source.CACHE).<DocumentSnapshot>continueWithTask(cached->{
            if (!STATE.current(Fb.uid(),flight))
                return com.google.android.gms.tasks.Tasks.forException(new java.io.IOException("Account changed"));
            if (cached.isSuccessful() && cached.getResult()!=null && cached.getResult().exists())
                return com.google.android.gms.tasks.Tasks.forResult(cached.getResult());
            return author.get();
        }).addOnSuccessListener(doc->{
            String value=doc!=null && doc.exists()?doc.getString("tickLevel"):null;
            complete(flight,value==null?"none":value);
        }).addOnFailureListener(error->complete(flight,null));
    }

    private static void complete(BadgeLookupState.Flight<ImageView> flight,String level) {
        String actor=Fb.uid();
        for (ImageView view:STATE.finish(actor,flight,level,android.os.SystemClock.elapsedRealtime()))
            Icons.setTickBadge(view,STATE.cached(actor,flight.uid),false);
    }

    public static void put(String uid,String level) {
        if (uid!=null) STATE.put(Fb.uid(),uid,level==null?"none":level);
    }
    public static String peek(String uid) {
        if (uid==null || uid.isEmpty()) return "none";
        String level=STATE.cached(Fb.uid(),uid);
        if (level!=null) return level;
        Models.User user=FastCache.getUser(uid);
        return user!=null && user.tickLevel!=null?user.tickLevel:"none";
    }
    public static void clear() {STATE.clear();}
}
