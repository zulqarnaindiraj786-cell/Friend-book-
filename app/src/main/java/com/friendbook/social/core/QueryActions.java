package com.friendbook.social.core;
import com.google.android.gms.tasks.*;
import com.google.firebase.firestore.*;
import java.util.Objects;
/** Exhaustive cursor batches for explicitly confirmed actions, never an app-start migration. */
public final class QueryActions {
    /** The collection a snapshot belongs to, from its own path. */
    private static String splitCollection(DocumentSnapshot doc){
        String path=doc.getReference().getPath();
        int slash=path.lastIndexOf('/',path.lastIndexOf('/')-1);
        return slash<0?"":path.substring(0,slash);
    }
    private static String splitId(DocumentSnapshot doc){String path=doc.getReference().getPath();return path.substring(path.lastIndexOf('/')+1);}

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
            DocumentSnapshot last=snap.getDocuments().get(snap.size()-1);com.friendbook.social.core.D1Batch batch=DataRouter.batch();
            // Every matching document is patched individually; a bulk update that wrote only the
            // first one would be a silent partial write.
            for(DocumentSnapshot doc:snap)batch.update(splitCollection(doc),splitId(doc),patch);
            batch.commit((success,error)->{
                if(!success){done.trySetException(new IllegalStateException(error));return;}
                if(snap.size()<50)done.trySetResult(total+snap.size());else updatePage(query,last,actor,patch,total+snap.size(),done);
            });
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
            com.friendbook.social.core.D1Batch batch=DataRouter.batch();
            for(DocumentSnapshot doc:snap)batch.delete(splitCollection(doc),splitId(doc));
            batch.commit((success,error)->{
                if(!success){done.trySetException(new IllegalStateException(error));return;}
                if(snap.size()<50)done.trySetResult(null);else deletePage(query,last,actor,done);
            });
        }).addOnFailureListener(done::trySetException);
    }
}
