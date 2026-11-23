package com.friendbook.social.core;
import com.google.android.gms.tasks.*;
import com.friendbook.social.d1.documents.*;
import java.util.Objects;
/** Exhaustive cursor batches for explicitly confirmed actions, never an app-start migration. */
public final class QueryActions {
    private QueryActions() {}
    public static Task<Void> deleteMatching(Query query) {
        TaskCompletionSource<Void> done=new TaskCompletionSource<>();
        deletePage(query,null,Fb.uid(),done);return done.getTask();
    }
    public static Task<Integer> updateMatching(Query query,java.util.Map<String,Object> patch) {
        TaskCompletionSource<Integer> done=new TaskCompletionSource<>();updatePage(query,null,Fb.uid(),patch,0,done);return done.getTask();
    }
    private static void updatePage(Query query,DocumentSnapshot cursor,String actor,java.util.Map<String,Object> patch,int total,TaskCompletionSource<Integer> done){
        if(actor==null || !Objects.equals(actor,Fb.uid())){done.trySetException(new IllegalStateException("Account changed"));return;}
        Query page=cursor==null?query:query.startAfter(cursor);
        page.limit(50).get(Source.SERVER).addOnSuccessListener(snap->{
            if(!Objects.equals(actor,Fb.uid())){done.trySetException(new IllegalStateException("Account changed"));return;}
            if(snap.isEmpty()){done.trySetResult(total);return;}
            DocumentSnapshot last=snap.getDocuments().get(snap.size()-1);WriteBatch batch=Fb.db().batch();
            for(DocumentSnapshot doc:snap)batch.update(doc.getReference(),patch);
            batch.commit().addOnSuccessListener(v->{if(snap.size()<50)done.trySetResult(total+snap.size());else updatePage(query,last,actor,patch,total+snap.size(),done);}).addOnFailureListener(done::trySetException);
        }).addOnFailureListener(done::trySetException);
    }
    public static Task<Boolean> anyRecent(Query query,long cutoff) {
        TaskCompletionSource<Boolean> done=new TaskCompletionSource<>();recentPage(query,null,Fb.uid(),cutoff,done);return done.getTask();
    }
    private static void recentPage(Query query,DocumentSnapshot cursor,String actor,long cutoff,TaskCompletionSource<Boolean> done){
        if(actor==null || !Objects.equals(actor,Fb.uid())){done.trySetException(new IllegalStateException("Account changed"));return;}
        Query page=cursor==null?query:query.startAfter(cursor);
        page.limit(50).get(Source.SERVER).addOnSuccessListener(snap->{
            if(!Objects.equals(actor,Fb.uid())){done.trySetException(new IllegalStateException("Account changed"));return;}
            for(DocumentSnapshot doc:snap)if(Format.ts(doc.get("at"))>=cutoff){done.trySetResult(true);return;}
            if(snap.size()<50){done.trySetResult(false);return;}
            recentPage(query,snap.getDocuments().get(snap.size()-1),actor,cutoff,done);
        }).addOnFailureListener(done::trySetException);
    }
    private static void deletePage(Query query,DocumentSnapshot cursor,String actor,TaskCompletionSource<Void> done) {
        if(actor==null || !Objects.equals(actor,Fb.uid())){done.trySetException(new IllegalStateException("Account changed"));return;}
        Query page=cursor==null?query:query.startAfter(cursor);
        page.limit(50).get(Source.SERVER).addOnSuccessListener(snap->{
            if(!Objects.equals(actor,Fb.uid())){done.trySetException(new IllegalStateException("Account changed"));return;}
            if(snap.isEmpty()){done.trySetResult(null);return;}
            DocumentSnapshot last=snap.getDocuments().get(snap.size()-1);
            WriteBatch batch=Fb.db().batch();for(DocumentSnapshot doc:snap)batch.delete(doc.getReference());
            batch.commit().addOnSuccessListener(v->{if(snap.size()<50)done.trySetResult(null);else deletePage(query,last,actor,done);})
                .addOnFailureListener(done::trySetException);
        }).addOnFailureListener(done::trySetException);
    }
}
