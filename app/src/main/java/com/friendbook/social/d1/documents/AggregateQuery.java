package com.friendbook.social.d1.documents;

import com.google.android.gms.tasks.Task;

import org.json.JSONArray;

/** Count aggregate query over a Cloudflare D1 collection. */
public class AggregateQuery {
    private final Query query;

    AggregateQuery(Query query) {
        this.query = query;
    }

    public Task<AggregateQuerySnapshot> get(AggregateSource source) {
        final String actor=D1DocumentStore.authenticatedUid();
        // Freeze the exact count input before joining or entering the executor.
        // Count transport accepts parent + filters only (not page cursors/limit).
        final String filters=query.filtersJson().toString();
        final String key=new JSONArray().put(query.parentPath).put(filters).toString();
        return query.firestore.aggregateReadFlights.read(actor,key,true,()->query.firestore.runAsync(() -> {
            if(!actor.equals(D1DocumentStore.authenticatedUid()))
                throw new D1DocumentStoreException(D1DocumentStoreException.Code.CANCELLED,"Account changed before count");
            int count=D1DocumentTransport.count(query.parentPath,new JSONArray(filters));
            if(!actor.equals(D1DocumentStore.authenticatedUid()))
                throw new D1DocumentStoreException(D1DocumentStoreException.Code.CANCELLED,"Account changed during count");
            return new AggregateQuerySnapshot(count);
        }));
    }
}
