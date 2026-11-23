package com.friendbook.social.d1.documents;

/** Callback for snapshot events. */
public interface EventListener<T> {
    void onEvent(T value, D1DocumentStoreException error);
}
