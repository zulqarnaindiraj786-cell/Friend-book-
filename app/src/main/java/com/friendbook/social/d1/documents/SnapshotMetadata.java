package com.friendbook.social.d1.documents;

/** Snapshot metadata: cache vs server, pending writes. */
public class SnapshotMetadata {
    private final boolean fromCache;
    private final boolean hasPendingWrites;

    public SnapshotMetadata(boolean fromCache, boolean hasPendingWrites) {
        this.fromCache = fromCache;
        this.hasPendingWrites = hasPendingWrites;
    }

    public boolean isFromCache() { return fromCache; }

    public boolean hasPendingWrites() { return hasPendingWrites; }
}
