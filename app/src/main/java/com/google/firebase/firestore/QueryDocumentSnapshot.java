package com.google.firebase.firestore;

import java.util.Map;

/** A DocumentSnapshot guaranteed to exist in a QuerySnapshot. */
public class QueryDocumentSnapshot extends DocumentSnapshot {
    public QueryDocumentSnapshot(FirebaseFirestore firestore, String path, String id,
                                 Map<String, Object> data, SnapshotMetadata metadata,
                                 long createTime, long updateTime) {
        super(firestore, path, id, data, true, metadata, createTime, updateTime);
    }

    @Override
    public Map<String, Object> getData() {
        Map<String, Object> d = super.getData();
        return d != null ? d : new java.util.HashMap<>();
    }
}
