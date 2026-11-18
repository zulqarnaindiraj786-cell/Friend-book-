package com.google.firebase.firestore;

public class SnapshotMetadata {
    private final boolean hasPendingWrites;
    private final boolean isFromCache;

    public SnapshotMetadata(boolean hasPendingWrites, boolean isFromCache) {
        this.hasPendingWrites = hasPendingWrites;
        this.isFromCache = isFromCache;
    }

    public boolean hasPendingWrites() {
        return hasPendingWrites;
    }

    public boolean isFromCache() {
        return isFromCache;
    }
}
