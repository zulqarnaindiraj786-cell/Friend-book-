package com.friendbook.social.core;

import android.util.LruCache;
import com.google.firebase.firestore.*;
import java.util.*;

/** Account-scoped, bounded one-shot pages. Never advances the cursor on failed reads. */
public final class CursorPager {
    public interface Listener { void changed(List<DocumentSnapshot> docs,boolean more,boolean loading,String error); }
    private static final LruCache<String,List<DocumentSnapshot>> CACHE=new LruCache<>(24);
    private final Query query;
    private final int size;
    private final String actor,key;
    private final Listener listener;
    private final LinkedHashMap<String,DocumentSnapshot> records=new LinkedHashMap<>();
    private DocumentSnapshot cursor;
    private boolean more=true,loading,closed;
    public CursorPager(Query query,int size,String key,Listener listener) {
        this.query=query;this.size=size;this.actor=Fb.uid();this.key=actor+":"+key;this.listener=listener;
    }
    public void start() {
        List<DocumentSnapshot> cached=CACHE.get(key);
        if(cached!=null) {for(DocumentSnapshot doc:cached)records.put(doc.getId(),doc);listener.changed(new ArrayList<>(cached),true,false,"");}
        next();
    }
    public void next() {
        if(closed || loading || !more || !Objects.equals(actor,Fb.uid())) return;
        loading=true;emit("");Query page=query;if(cursor!=null)page=page.startAfter(cursor);
        page.limit(size).get().addOnSuccessListener(result -> {
            if(!active())return;loading=false;
            boolean offline=result.getMetadata().isFromCache();
            if(!offline)more=result.size()==size;
            if(cursor==null)records.clear();
            if(!offline && !result.isEmpty())cursor=result.getDocuments().get(result.size()-1);
            for(DocumentSnapshot doc:result)records.put(doc.getId(),doc);
            List<DocumentSnapshot> first=new ArrayList<>(records.values());
            if(first.size()>size) first=new ArrayList<>(first.subList(0,size));
            CACHE.put(key,first);emit(offline?"Showing cached results. Reconnect and tap to retry.":"");
        }).addOnFailureListener(error -> {if(active()){loading=false;emit("Couldn't load this page. Tap to retry.");}});
    }
    private boolean active(){return !closed && Objects.equals(actor,Fb.uid());}
    private void emit(String error){if(active())listener.changed(new ArrayList<>(records.values()),more,loading,error);}
    public boolean hasMore(){return more;}
    public void close(){closed=true;}
}
