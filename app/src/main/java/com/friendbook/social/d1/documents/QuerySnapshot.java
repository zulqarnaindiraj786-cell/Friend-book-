package com.friendbook.social.d1.documents;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Immutable list of query results from Cloudflare D1. */
public class QuerySnapshot implements Iterable<QueryDocumentSnapshot> {
    private final List<QueryDocumentSnapshot> docs;
    private final List<DocumentChange> changes;
    private final SnapshotMetadata metadata;

    public QuerySnapshot(List<QueryDocumentSnapshot> docs, List<DocumentChange> changes, SnapshotMetadata metadata) {
        this.docs = docs == null ? new ArrayList<>() : docs;
        this.changes = changes;
        this.metadata = metadata == null ? new SnapshotMetadata(false, false) : metadata;
    }

    public List<DocumentSnapshot> getDocuments() {
        return new ArrayList<>(docs);
    }

    public List<DocumentChange> getDocumentChanges() {
        if (changes != null) return changes;
        List<DocumentChange> out = new ArrayList<>(docs.size());
        for (int i = 0; i < docs.size(); i++) {
            out.add(new DocumentChange(DocumentChange.Type.ADDED, docs.get(i), -1, i));
        }
        return out;
    }

    public List<DocumentChange> getDocumentChanges(MetadataChanges metadataChanges) {
        return getDocumentChanges();
    }

    public boolean isEmpty() { return docs.isEmpty(); }

    public int size() { return docs.size(); }

    public SnapshotMetadata getMetadata() { return metadata; }

    @Override
    public Iterator<QueryDocumentSnapshot> iterator() {
        return docs.iterator();
    }
}
