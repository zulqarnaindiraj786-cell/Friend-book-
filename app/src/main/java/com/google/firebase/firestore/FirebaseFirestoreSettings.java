package com.google.firebase.firestore;

/** Firestore settings stub (compatible with App.java). */
public class FirebaseFirestoreSettings {
    public static final long CACHE_SIZE_UNLIMITED = -1L;

    public static class Builder {
        public Builder setLocalCacheSettings(Object settings) { return this; }
        public Builder setPersistenceEnabled(boolean enabled) { return this; }
        public Builder setCacheSizeBytes(long sizeBytes) { return this; }
        public FirebaseFirestoreSettings build() { return new FirebaseFirestoreSettings(); }
    }
}
