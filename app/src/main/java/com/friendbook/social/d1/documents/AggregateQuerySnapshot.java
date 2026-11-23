package com.friendbook.social.d1.documents;

/** Result of an AggregateQuery (e.g. count()). */
public class AggregateQuerySnapshot {
    private final long count;

    public AggregateQuerySnapshot(long count) {
        this.count = count;
    }

    public long getCount() {
        return count;
    }
}
