package com.google.firebase.firestore;

/** Local cache settings stub (compatible with App.java). */
public class PersistentCacheSettings {
    public static Builder newBuilder() {
        return new Builder();
    }

    public static class Builder {
        public Builder setSizeBytes(long sizeBytes) { return this; }
        public PersistentCacheSettings build() { return new PersistentCacheSettings(); }
    }
}
