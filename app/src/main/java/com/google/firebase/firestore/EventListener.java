package com.google.firebase.firestore;

/** Callback for snapshot events. */
public interface EventListener<T> {
    void onEvent(T value, FirebaseFirestoreException error);
}
