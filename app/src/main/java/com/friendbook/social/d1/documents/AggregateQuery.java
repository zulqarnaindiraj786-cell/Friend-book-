package com.friendbook.social.d1.documents;

import com.google.android.gms.tasks.Task;
import org.json.JSONArray;

/** Count aggregate query over a Cloudflare D1 collection. */
public class AggregateQuery {
    private final Query query;
    AggregateQuery(Query query) { this.query = query; }

    public Task<AggregateQuerySnapshot> get(AggregateSource source) {
        return start(D1DocumentStore.authenticatedUid(),query.filtersJson().toString(),false);
    }

    /** Screen-owned aggregate: wait for foreground, while preserving explicit counts. */
    public Task<AggregateQuerySnapshot> getAutomatic(ForegroundReadScope scope) {
        final String actor=D1DocumentStore.authenticatedUid();
        final String filters=query.filtersJson().toString();
        return scope.read(() -> start(actor,filters,true));
    }

    private Task<AggregateQuerySnapshot> start(String actor,String filters,boolean automatic) {
        if(!actor.equals(D1DocumentStore.authenticatedUid())) return com.google.android.gms.tasks.Tasks.forException(
                new D1DocumentStoreException(D1DocumentStoreException.Code.CANCELLED,"Account changed before count"));
        // Count transport accepts parent + filters only (not page cursors/limit).
        final String key=new JSONArray().put(query.parentPath).put(filters).toString();
        DocumentReadFlights.Access<AggregateQuerySnapshot> access=query.firestore.aggregateReadFlights.readPromotable(
                actor,key,true,!automatic,allowBackground->query.firestore.runAsync(() -> {
            if(!actor.equals(D1DocumentStore.authenticatedUid()))
                throw new D1DocumentStoreException(D1DocumentStoreException.Code.CANCELLED,"Account changed before count");
            if(!allowBackground.get() && !com.friendbook.social.App.isForeground())
                throw new D1DocumentStore.BackgroundQueryDeferred();
            int count=D1DocumentTransport.count(query.parentPath,new JSONArray(filters));
            if(!actor.equals(D1DocumentStore.authenticatedUid()))
                throw new D1DocumentStoreException(D1DocumentStoreException.Code.CANCELLED,"Account changed during count");
            return new AggregateQuerySnapshot(count);
        }));
        if(!automatic && access.promotedAutomatic) return access.task.continueWithTask(Runnable::run,completed ->
                completed.getException() instanceof D1DocumentStore.BackgroundQueryDeferred
                        ? start(actor,filters,false) : completed);
        return access.task;
    }
}
