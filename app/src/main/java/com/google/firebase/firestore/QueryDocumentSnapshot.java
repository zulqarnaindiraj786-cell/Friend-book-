package com.google.firebase.firestore;

import androidx.annotation.NonNull;
import java.util.LinkedHashMap;
import java.util.Map;

public class QueryDocumentSnapshot extends DocumentSnapshot {

    public QueryDocumentSnapshot(
            FirebaseFirestore firestore,
            String path,
            @NonNull Map<String, Object> data,
            boolean isFromCache) {
        super(firestore, path, data, true, isFromCache);
    }

    @NonNull
    @Override
    public Map<String, Object> getData() {
        Map<String, Object> d = super.getData();
        return d == null ? new LinkedHashMap<>() : d;
    }

    @NonNull
    @Override
    public Map<String, Object> getData(@NonNull ServerTimestampBehavior behavior) {
        return getData();
    }
}
