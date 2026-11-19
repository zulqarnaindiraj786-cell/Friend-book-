package com.friendbook.social.core;

import android.util.LruCache;
import com.friendbook.social.d1.documents.*;
import java.util.*;

/** Account-scoped, bounded one-shot pages. Never advances the cursor on failed reads. */
public final class CursorPager {
    public interface Listener { void changed(List<DocumentSnapshot> docs,boolean more,boolean loading,String error); }
    private static final class CachedPage {
        final List<DocumentSnapshot> docs;final boolean more;
        CachedPage(List<DocumentSnapshot> docs,boolean more){this.docs=docs;this.more=more;}
    }
    private static final LruCache<String,CachedPage> CACHE=new LruCache<>(24);
    private List<DocumentSnapshot> lastDelivered=new ArrayList<>();
    private boolean delivered,lastLoading,lastMore;private String lastError="";
    private final Query query;
    private final int size;
    private final String actor,key;
    private final Listener listener;
    private final LinkedHashMap<String,DocumentSnapshot> records=new LinkedHashMap<>();
    private DocumentSnapshot cursor;
    private boolean more=true,loading,closed,serverReceived;
    private int requestGeneration;
    private final android.os.Handler timeoutHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable timeout;
    public CursorPager(Query query,int size,String key,Listener listener) {
        this.query=query;this.size=size;this.actor=Fb.uid();this.key=actor+":"+key;this.listener=listener;
    }
    public void start() {
        CachedPage cached=CACHE.get(key);
        if(cached!=null){for(DocumentSnapshot doc:cached.docs)records.put(doc.getId(),doc);more=cached.more;emit("");}
        if (cached==null) query.limit(size).get(Source.CACHE).addOnSuccessListener(page -> {
            if (!active() || serverReceived || !records.isEmpty()) return;
            for (DocumentSnapshot doc:page) records.put(doc.getId(),doc);
            if (!records.isEmpty()) { boolean wasLoading=loading;loading=false;emit("");loading=wasLoading; }
        });
        more=true;next();
    }
    public void next() {
        if(closed || loading || !more || !Objects.equals(actor,Fb.uid())) return;
        loading=true;if(cursor!=null || records.isEmpty())emit("");Query page=query;if(cursor!=null)page=page.startAfter(cursor);
        final int request=++requestGeneration;
        timeout=()->{if(active() && loading && request==requestGeneration){requestGeneration++;loading=false;emit("Loading is taking too long. Tap to retry.");}};
        timeoutHandler.postDelayed(timeout,20000L);
        page.limit(size).get().addOnSuccessListener(result -> {
            if(!active() || request!=requestGeneration)return;timeoutHandler.removeCallbacks(timeout);loading=false;serverReceived=true;
            boolean offline=result.getMetadata().isFromCache();
            if(!offline)more=result.size()==size;
            // An offline cache can be incomplete or empty; it cannot prove that
            // previously confirmed rows disappeared. Only replace on a server read.
            if(cursor==null && !offline)records.clear();
            if(!offline && !result.isEmpty())cursor=result.getDocuments().get(result.size()-1);
            for(DocumentSnapshot doc:result)if(!offline || !records.containsKey(doc.getId()))records.put(doc.getId(),doc);
            if(!offline){
                List<DocumentSnapshot> first=new ArrayList<>(records.values());
                if(first.size()>size) first=new ArrayList<>(first.subList(0,size));
                CACHE.put(key,new CachedPage(first,records.size()>size || more));
            }
            emit(offline?"Showing cached results. Reconnect and tap to retry.":"");
        }).addOnFailureListener(error -> {if(active() && request==requestGeneration){timeoutHandler.removeCallbacks(timeout);loading=false;emit("Couldn't load this page. Tap to retry.");}});
    }
    private boolean active(){return !closed && Objects.equals(actor,Fb.uid());}
    private void emit(String error){
        if(!active())return;List<DocumentSnapshot> docs=new ArrayList<>(records.values());
        if(delivered && !loading && !lastLoading && more==lastMore && error.isEmpty() && lastError.isEmpty() && sameData(lastDelivered,docs))return;
        delivered=true;lastDelivered=docs;lastMore=more;lastLoading=loading;lastError=error;
        listener.changed(docs,more,loading,error);
    }
    private static boolean sameData(List<DocumentSnapshot> a,List<DocumentSnapshot> b){
        if(a.size()!=b.size())return false;
        for(int i=0;i<a.size();i++)if(!a.get(i).getId().equals(b.get(i).getId()) || !Objects.equals(a.get(i).getData(),b.get(i).getData()))return false;
        return true;
    }
    public void forget(String id){records.remove(id);CACHE.remove(key);}
    public boolean hasMore(){return more;}
    public void close(){closed=true;requestGeneration++;if(timeout!=null)timeoutHandler.removeCallbacks(timeout);}
}
