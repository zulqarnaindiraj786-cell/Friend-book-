package com.google.firebase.firestore;

import androidx.annotation.NonNull;

public class DocumentChange {

    public enum Type {
        ADDED,
        MODIFIED,
        REMOVED
    }

    private final Type type;
    private final QueryDocumentSnapshot document;
    private final int oldIndex;
    private final int newIndex;

    public DocumentChange(Type type, QueryDocumentSnapshot document, int oldIndex, int newIndex) {
        this.type = type;
        this.document = document;
        this.oldIndex = oldIndex;
        this.newIndex = newIndex;
    }

    @NonNull
    public Type getType() {
        return type;
    }

    @NonNull
    public QueryDocumentSnapshot getDocument() {
        return document;
    }

    public int getOldIndex() {
        return oldIndex;
    }

    public int getNewIndex() {
        return newIndex;
    }
}
