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

    /**
     * Patches every D1 document matching a query, a page at a time.
     *
     * <p>The Firestore {@link #updateMatching} reads with {@code Source.SERVER}, which fails
     * outright with Firestore's network off - so "limit past posts" silently did nothing.
     * This is the D1-native twin: it pages the matches from D1 and patches each one, reporting
     * how many changed. Used for explicitly confirmed bulk actions, never at app start.
     */
    public static void updateMatchingD1(DocQuery query, java.util.Map<String, Object> patch,
                                        java.util.function.Consumer<Integer> done) {
        if (query == null || patch == null) {
            if (done != null) done.accept(0);
            return;
        }
        final String actor = Fb.uid();
        updateMatchingD1Page(query, patch, actor, 0, done);
    }

    private static void updateMatchingD1Page(DocQuery query, java.util.Map<String, Object> patch,
                                             String actor, int total,
                                             java.util.function.Consumer<Integer> done) {
        if (actor == null || !Objects.equals(actor, Fb.uid())) {
            if (done != null) done.accept(total);
            return;
        }
        CloudflareStore.query(
                query,
                page -> {
                    if (page == null || page.isEmpty()) {
                        if (done != null) done.accept(total);
                        return;
                    }
                    D1Batch batch = DataRouter.batch();
                    for (java.util.Map<String, Object> doc : page.documents) {
                        String id = Format.str(doc, "id");
                        if (!id.isEmpty()) batch.update(query.collection(), id, patch);
                    }
                    batch.commit((ok, error) -> {
                        int next = total + page.documents.size();
                        if (!ok || page.nextCursor == null) {
                            if (done != null) done.accept(next);
                            return;
                        }
                        updateMatchingD1Page(query.nextPage(page.nextCursor), patch, actor, next, done);
                    });
                },
                () -> { if (done != null) done.accept(total); });
    }

    /**
     * Deletes every D1 document in a collection whose field equals a value, a page at a time.
     *
     * <p>Used when one document owns others - a post owns its comments. Firestore could cascade
     * with a query delete; D1 has no cascade, so the owner is removed first and the children are
     * collected here. A failure leaves orphans rather than failing the owner's delete.
     */
    public static void deleteMatchingD1(String collection, String field, String value,
                                        Runnable done) {
        if (collection == null || field == null || value == null) {
            if (done != null) done.run();
            return;
        }
        CloudflareStore.query(
                DocQuery.of(collection).whereEqualTo(field, value).limit(50).build(),
                page -> {
                    D1Batch batch = DataRouter.batch();
                    for (java.util.Map<String, Object> doc : page.documents) {
                        String id = Format.str(doc, "id");
                        if (!id.isEmpty()) batch.delete(collection, id);
                    }
                    batch.commit((ok, error) -> {
                        // One page is enough for the cases this is used for (a post's comments);
                        // anything larger is left for a later sweep rather than looping here.
                        if (done != null) done.run();
                    });
                },
                () -> { if (done != null) done.run(); });
    }
}
