package com.google.firebase.firestore;

/** Where a read should come from. CACHE is served from the local snapshot cache. */
public enum Source {
    DEFAULT,
    SERVER,
    CACHE
}
