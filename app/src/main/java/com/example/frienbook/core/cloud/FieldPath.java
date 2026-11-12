package com.example.frienbook.core.cloud;

/**
 * The only FieldPath the app uses: documentId(), for the "fetch exactly these ten profiles" query
 * that the friends picker and the mention list run. It becomes inId= on the wire, which the Worker
 * answers with a bounded id set.
 */
public final class FieldPath {

    private final boolean documentId;

    private FieldPath(boolean documentId) {
        this.documentId = documentId;
    }

    public static FieldPath documentId() {
        return new FieldPath(true);
    }

    boolean isDocumentId() {
        return documentId;
    }
}
