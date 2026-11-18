package com.google.firebase.firestore;

import androidx.annotation.NonNull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

public class QuerySnapshot implements Iterable<QueryDocumentSnapshot> {
    private final Query query;
    private final List<QueryDocumentSnapshot> documents;
    private final List<DocumentChange> documentChanges;
    private final SnapshotMetadata metadata;

    public QuerySnapshot(
            Query query,
            List<QueryDocumentSnapshot> documents,
            List<DocumentChange> documentChanges,
            boolean isFromCache) {
        this.query = query;
        this.documents = documents == null ? Collections.emptyList() : new ArrayList<>(documents);
        if (documentChanges != null) {
            this.documentChanges = new ArrayList<>(documentChanges);
        } else {
            List<DocumentChange> added = new ArrayList<>(this.documents.size());
            for (int i = 0; i < this.documents.size(); i++) {
                added.add(new DocumentChange(DocumentChange.Type.ADDED, this.documents.get(i), -1, i));
            }
            this.documentChanges = added;
        }
        this.metadata = new SnapshotMetadata(false, isFromCache);
    }

    @NonNull
    public Query getQuery() {
        return query;
    }

    @NonNull
    public SnapshotMetadata getMetadata() {
        return metadata;
    }

    @NonNull
    public List<DocumentSnapshot> getDocuments() {
        return new ArrayList<>(documents);
    }

    @NonNull
    public List<DocumentChange> getDocumentChanges() {
        return new ArrayList<>(documentChanges);
    }

    @NonNull
    public List<DocumentChange> getDocumentChanges(@NonNull MetadataChanges metadataChanges) {
        return getDocumentChanges();
    }

    public boolean isEmpty() {
        return documents.isEmpty();
    }

    public int size() {
        return documents.size();
    }

    @NonNull
    @Override
    public Iterator<QueryDocumentSnapshot> iterator() {
        return documents.iterator();
    }

    @NonNull
    public <T> List<T> toObjects(@NonNull Class<T> clazz) {
        List<T> list = new ArrayList<>(documents.size());
        for (QueryDocumentSnapshot doc : documents) {
            T obj = doc.toObject(clazz);
            if (obj != null) list.add(obj);
        }
        return list;
    }

    List<QueryDocumentSnapshot> rawQueryDocs() {
        return documents;
    }
}
