package com.friendbook.social.d1.documents;

/** Where a read should come from. CACHE is served from the local snapshot cache. */
public enum Source {
    DEFAULT,
    SERVER,
    CACHE
}
