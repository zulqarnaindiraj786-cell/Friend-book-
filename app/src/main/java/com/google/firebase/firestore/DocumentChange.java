package com.google.firebase.firestore;

/** Represents a document change in a QuerySnapshot. */
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

    public Type getType() { return type; }

    public QueryDocumentSnapshot getDocument() { return document; }

    public int getOldIndex() { return oldIndex; }

    public int getNewIndex() { return newIndex; }
}
