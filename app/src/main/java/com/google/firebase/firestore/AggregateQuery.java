package com.google.firebase.firestore;

import androidx.annotation.NonNull;
import com.google.android.gms.tasks.Task;

public class AggregateQuery {
    private final Query query;

    public AggregateQuery(Query query) {
        this.query = query;
    }

    @NonNull
    public Query getQuery() {
        return query;
    }

    @NonNull
    public Task<AggregateQuerySnapshot> get(@NonNull AggregateSource source) {
        return CfFirestoreTransport.executeCount(this, query);
    }
}
