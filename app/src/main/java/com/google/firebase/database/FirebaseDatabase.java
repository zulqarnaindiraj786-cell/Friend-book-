package com.google.firebase.database;

import androidx.annotation.NonNull;

public class FirebaseDatabase {
    private static final FirebaseDatabase INSTANCE = new FirebaseDatabase();

    public FirebaseDatabase() {}

    @NonNull
    public static FirebaseDatabase getInstance() {
        return INSTANCE;
    }

    @NonNull
    public static FirebaseDatabase getInstance(@NonNull String url) {
        return INSTANCE;
    }

    public void setPersistenceEnabled(boolean isEnabled) {
        // Persistence handled by Cloudflare Worker + local cache
    }

    @NonNull
    public DatabaseReference getReference() {
        return new DatabaseReference("");
    }

    @NonNull
    public DatabaseReference getReference(@NonNull String path) {
        return new DatabaseReference(path);
    }
}
