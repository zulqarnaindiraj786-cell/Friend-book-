package com.google.firebase.firestore;

public class FieldPath {
    private static final FieldPath DOCUMENT_ID = new FieldPath("__name__");
    private final String canonical;

    private FieldPath(String canonical) {
        this.canonical = canonical;
    }

    public static FieldPath documentId() {
        return DOCUMENT_ID;
    }

    public static FieldPath of(String... fieldNames) {
        if (fieldNames == null || fieldNames.length == 0) {
            throw new IllegalArgumentException("FieldPath must contain at least 1 segment");
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fieldNames.length; i++) {
            if (i > 0) sb.append('.');
            sb.append(fieldNames[i]);
        }
        return new FieldPath(sb.toString());
    }

    public String getCanonicalString() {
        return canonical;
    }

    @Override
    public String toString() {
        return canonical;
    }
}
