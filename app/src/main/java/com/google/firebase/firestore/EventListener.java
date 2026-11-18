package com.google.firebase.firestore;

import androidx.annotation.Nullable;

public interface EventListener<T> {
    void onEvent(@Nullable T value, @Nullable FirebaseFirestoreException error);
}
