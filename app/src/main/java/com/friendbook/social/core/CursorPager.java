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
    private DocumentSnapshot cursor,observedTail;
    private List<DocumentSnapshot> observedHead=Collections.emptyList();
    private boolean receivedObserved;
    private boolean more=true,loading,closed,serverReceived;
    private boolean reuseExactPages;
    private ForegroundReadScope initialForegroundScope;
    private int requestGeneration;
    private final android.os.Handler timeoutHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable timeout;
    public CursorPager(Query query,int size,String key,Listener listener) {
        this.query=query;this.size=size;this.actor=Fb.uid();this.key=actor+":"+key;this.listener=listener;
    }
    public void start() { start(false); }
    public void startDelta() { startLocal(0,false); }
    public void startFullRefresh() { startLocal(0,true); }
    /** Cache-first explicit list: no background revalidation; writes invalidate exact query pages. */
    public void startOnDemand() { reuseExactPages=true; startLocal(Long.MAX_VALUE,true); }
    public void refreshOnDemand() { reuseExactPages=true; startFullRefresh(); }
    public void startCachedDay() { startLocal(24*60*60*1000L,true); }
    /** Short exact-query reuse across search terms over the same recent posts. */
    public void startCachedFor(long maxAgeMs) { startLocal(Math.max(0L,maxAgeMs),true); }
    /** Screen-owned initial page; scrolling and explicit retries remain user reads. */
    public void startCachedForForeground(long maxAgeMs) {
        if (initialForegroundScope==null) initialForegroundScope=new ForegroundReadScope();
        startLocal(Math.max(0L,maxAgeMs),true);
    }
    private void startLocal(long maxAgeMs,boolean full) {
        if(!active() || loading)return;
        loading=true;
        final int request=++requestGeneration;
        final ForegroundReadScope automaticScope=maxAgeMs>0 ? initialForegroundScope : null;
        timeout=()->{
            if(!active() || !loading || request!=requestGeneration)return;
            if(automaticScope!=null && !com.friendbook.social.App.isForeground()) {
                // Do not time out a read that is deliberately waiting for resume.
                automaticScope.whenForeground().addOnSuccessListener(ignored->{
                    if(active() && loading && request==requestGeneration)timeoutHandler.postDelayed(timeout,20000L);
                });
                return;
            }
            requestGeneration++;loading=false;
            if(automaticScope!=null)automaticScope.close();
            emit("Loading is taking too long. Tap to retry.");
        };
        timeoutHandler.postDelayed(timeout,20000L);
        Query head=query.limit(size);
        head.get(Source.CACHE).addOnCompleteListener(cache -> {
            if(!active() || request!=requestGeneration)return;
            QuerySnapshot cached=cache.isSuccessful()?cache.getResult():null;
            if(cached!=null && maxAgeMs>0 && head.hasFreshLocalPage(maxAgeMs)) {
                timeoutHandler.removeCallbacks(timeout);loading=false;
                for(DocumentSnapshot doc:cached)records.put(doc.getId(),doc);
                if(!cached.isEmpty())cursor=cached.getDocuments().get(cached.size()-1);
                more=cached.size()==size;
                emit("");
                return;
            }
            if(cached!=null && !cached.isEmpty()) {
                for(DocumentSnapshot doc:cached)records.put(doc.getId(),doc);
                cursor=cached.getDocuments().get(cached.size()-1);
                more=cached.size()==size;
                loading=false;emit("");loading=true;
            } else emit("");
            // Rendering cached rows may close/replace this pager or change the
            // account. Do not start a stale network request after that callback.
            if(!active() || request!=requestGeneration)return;
            // Initial cache-first loads still require a network response on a
            // miss, but may share that exact in-flight operation. Only an
            // explicit refresh (zero max age) forces an independent read.
            (full ? (automaticScope!=null ? head.getAutomatic(automaticScope)
                    : head.get(maxAgeMs>0 ? Source.DEFAULT : Source.SERVER))
                    : head.refreshCachedHead(cached)).addOnSuccessListener(page -> {
                if(!active() || request!=requestGeneration)return;
                timeoutHandler.removeCallbacks(timeout);loading=false;
                acceptObservedFirstPage(page);
            }).addOnFailureListener(error -> {
                if(!active() || request!=requestGeneration)return;
                timeoutHandler.removeCallbacks(timeout);loading=false;emit("Couldn't refresh. Pull down to retry.");
            });
        });
    }
    public void startObserved() { start(true); }
    private void start(boolean observed) {
        if (!active()) return;
        CachedPage cached=CACHE.get(key);
        if(cached!=null){for(DocumentSnapshot doc:cached.docs)records.put(doc.getId(),doc);more=cached.more;emit("");}
        if (!active()) return;
        if (cached==null) query.limit(size).get(Source.CACHE).addOnSuccessListener(page -> {
            if (!active() || serverReceived || !records.isEmpty()) return;
            for (DocumentSnapshot doc:page) records.put(doc.getId(),doc);
            if (!records.isEmpty()) { boolean wasLoading=loading;loading=false;emit("");loading=wasLoading; }
        });
        more=true;
        if (!observed) next();
    }
    /** Reuse the verified first page already fetched by the section listener. */
    public void acceptObservedFirstPage(QuerySnapshot page) {
        if (!active() || page == null || page.getMetadata().isFromCache()) return;
        List<DocumentSnapshot> docs=page.getDocuments();
        if (receivedObserved && sameData(observedHead,docs)) return;
        boolean hasOlderPages=cursor!=null && observedTail!=null && (loading || !cursor.getId().equals(observedTail.getId()));
        receivedObserved=true;observedHead=new ArrayList<>(docs);
        observedTail=docs.isEmpty()?null:docs.get(docs.size()-1);
        if (page.size()<size || !hasOlderPages) {
            if (loading) { requestGeneration++; if (timeout!=null) timeoutHandler.removeCallbacks(timeout); loading=false; }
            records.clear();cursor=observedTail;more=page.size()==size;
        } else {
            // Refresh the live head without discarding older pages or invalidating their in-flight read.
            Set<String> headIds=new HashSet<>();for(DocumentSnapshot doc:docs)headIds.add(doc.getId());
            com.google.firebase.Timestamp boundary=observedTail==null?null:observedTail.getTimestamp("createdAt");
            records.values().removeIf(doc->{
                com.google.firebase.Timestamp time=doc.getTimestamp("createdAt");
                return headIds.contains(doc.getId()) || (boundary!=null && time!=null && (time.compareTo(boundary)>0 || (time.equals(boundary) && doc.getId().compareTo(observedTail.getId())>0)));
            });
        }
        LinkedHashMap<String,DocumentSnapshot> merged=new LinkedHashMap<>();
        for(DocumentSnapshot doc:docs)merged.put(doc.getId(),doc);
        merged.putAll(records);records.clear();records.putAll(merged);
        serverReceived=true;
        CACHE.put(key,new CachedPage(new ArrayList<>(docs),page.size()==size));
        emit("");
    }
    public void observedError() {
        if (active() && !serverReceived && !loading) emit("Couldn't load this page. Tap to retry.");
    }
    public void next() {
        if(!active() || loading || !more) return;
        loading=true;
        final int request=++requestGeneration;
        if(cursor!=null || records.isEmpty())emit("");
        // A listener can replace or close this pager during rendering.
        if(!active() || request!=requestGeneration)return;
        Query page=query;if(cursor!=null)page=page.startAfter(cursor);
        timeout=()->{if(active() && loading && request==requestGeneration){requestGeneration++;loading=false;emit("Loading is taking too long. Tap to retry.");}};
        timeoutHandler.postDelayed(timeout,20000L);
        Query bounded=page.limit(size);
        (reuseExactPages ? bounded.getCachedOrFetch(false) : bounded.get()).addOnSuccessListener(result -> {
            if(!active() || request!=requestGeneration)return;timeoutHandler.removeCallbacks(timeout);loading=false;
            boolean offline=result.getMetadata().isFromCache() && !reuseExactPages;
            if(!offline)serverReceived=true;
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
    private boolean active(){return !closed && actor!=null && Objects.equals(actor,Fb.uid());}
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
    public boolean isLoading(){return loading;}
    public void close(){closed=true;requestGeneration++;if(timeout!=null)timeoutHandler.removeCallbacks(timeout);
        if(initialForegroundScope!=null){initialForegroundScope.close();initialForegroundScope=null;}}
}
