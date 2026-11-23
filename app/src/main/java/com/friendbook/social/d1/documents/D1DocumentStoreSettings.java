package com.friendbook.social.d1.documents;

/** Firestore settings stub (compatible with App.java). */
public class D1DocumentStoreSettings {
    public static final long CACHE_SIZE_UNLIMITED = -1L;

    public static class Builder {
        public Builder setLocalCacheSettings(Object settings) { return this; }
        public Builder setPersistenceEnabled(boolean enabled) { return this; }
        public Builder setCacheSizeBytes(long sizeBytes) { return this; }
        public D1DocumentStoreSettings build() { return new D1DocumentStoreSettings(); }
    }
}
