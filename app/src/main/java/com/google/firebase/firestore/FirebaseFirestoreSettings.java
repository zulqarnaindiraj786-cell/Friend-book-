package com.google.firebase.firestore;

public class FirebaseFirestoreSettings {
    public static final long CACHE_SIZE_UNLIMITED = -1L;

    private final PersistentCacheSettings cacheSettings;

    private FirebaseFirestoreSettings(PersistentCacheSettings cacheSettings) {
        this.cacheSettings = cacheSettings;
    }

    public PersistentCacheSettings getCacheSettings() {
        return cacheSettings;
    }

    public static class Builder {
        private PersistentCacheSettings cacheSettings;

        public Builder() {}

        public Builder(FirebaseFirestoreSettings other) {
            if (other != null) this.cacheSettings = other.cacheSettings;
        }

        public Builder setLocalCacheSettings(PersistentCacheSettings settings) {
            this.cacheSettings = settings;
            return this;
        }

        public Builder setPersistenceEnabled(boolean enabled) {
            return this;
        }

        public Builder setCacheSizeBytes(long value) {
            return this;
        }

        public FirebaseFirestoreSettings build() {
            return new FirebaseFirestoreSettings(cacheSettings);
        }
    }
}
