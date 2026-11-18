package com.google.firebase.firestore;

import androidx.annotation.NonNull;

public class AggregateQuerySnapshot {
    private final AggregateQuery query;
    private final long count;

    public AggregateQuerySnapshot(AggregateQuery query, long count) {
        this.query = query;
        this.count = count;
    }

    @NonNull
    public AggregateQuery getQuery() {
        return query;
    }

    public long getCount() {
        return count;
    }
}
