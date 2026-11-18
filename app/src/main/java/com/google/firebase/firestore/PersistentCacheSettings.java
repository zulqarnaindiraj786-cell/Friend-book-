package com.google.firebase.firestore;

public class PersistentCacheSettings {
    private final long sizeBytes;

    private PersistentCacheSettings(long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public static class Builder {
        private long sizeBytes = 100L * 1024L * 1024L;

        public Builder setSizeBytes(long sizeBytes) {
            this.sizeBytes = sizeBytes;
            return this;
        }

        public PersistentCacheSettings build() {
            return new PersistentCacheSettings(sizeBytes);
        }
    }
}
