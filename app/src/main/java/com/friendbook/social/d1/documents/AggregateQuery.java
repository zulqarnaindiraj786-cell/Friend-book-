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
        return query.firestore.runAsync(() -> {
            JSONArray filters = query.filtersJson();
            int c = D1DocumentTransport.count(query.parentPath, filters);
            return new AggregateQuerySnapshot(c);
        });
    }
}
