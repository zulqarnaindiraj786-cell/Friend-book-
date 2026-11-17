package com.friendbook.social.d1.documents;

import java.util.Map;

/** A DocumentSnapshot guaranteed to exist in a QuerySnapshot. */
public class QueryDocumentSnapshot extends DocumentSnapshot {
    public QueryDocumentSnapshot(D1DocumentStore firestore, String path, String id,
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
