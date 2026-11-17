package com.google.firebase.firestore;

import com.google.firebase.Timestamp;

import java.util.HashMap;
import java.util.Map;

/** Immutable document snapshot backed by Cloudflare D1. */
public class DocumentSnapshot {
    public enum ServerTimestampBehavior {
        NONE,
        ESTIMATE,
        PREVIOUS
    }

    private final FirebaseFirestore firestore;
    private final String path;
    private final String id;
    private final Map<String, Object> data;
    private final boolean exists;
    private final SnapshotMetadata metadata;
    private final long createTime;
    private final long updateTime;

    public DocumentSnapshot(FirebaseFirestore firestore, String path, String id,
                            Map<String, Object> data, boolean exists,
                            SnapshotMetadata metadata, long createTime, long updateTime) {
        this.firestore = firestore;
        this.path = path;
        this.id = id;
        this.data = data == null ? null : new HashMap<>(data);
        this.exists = exists;
        this.metadata = metadata == null ? new SnapshotMetadata(false, false) : metadata;
        this.createTime = createTime;
        this.updateTime = updateTime;
    }

    public boolean exists() { return exists; }

    public String getId() { return id; }

    public String getPath() { return path; }

    public DocumentReference getReference() {
        return new DocumentReference(firestore, path);
    }

    public Map<String, Object> getData() {
        return data == null ? null : new HashMap<>(data);
    }

    public Map<String, Object> getData(ServerTimestampBehavior behavior) {
        return getData();
    }

    public Object get(String field) {
        if (data == null) return null;
        if ("__name__".equals(field)) return id;
        Object cur = data;
        for (String part : field.split("\\.")) {
            if (!(cur instanceof Map)) return null;
            cur = ((Map<?, ?>) cur).get(part);
            if (cur == null) return null;
        }
        return cur;
    }

    public Object get(FieldPath fieldPath) {
        if (fieldPath == null) return null;
        if (fieldPath.isDocumentId()) return id;
        return get(fieldPath.getFieldPathString());
    }

    public String getString(String field) {
        Object v = get(field);
        return v == null ? null : String.valueOf(v);
    }

    public Long getLong(String field) {
        Object v = get(field);
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof Timestamp) return ((Timestamp) v).toDate().getTime();
        return null;
    }

    public Double getDouble(String field) {
        Object v = get(field);
        return v instanceof Number ? ((Number) v).doubleValue() : null;
    }

    public Boolean getBoolean(String field) {
        Object v = get(field);
        return v instanceof Boolean ? (Boolean) v : null;
    }

    public Timestamp getTimestamp(String field) {
        Object v = get(field);
        if (v instanceof Timestamp) return (Timestamp) v;
        if (v instanceof Number) return new Timestamp(new java.util.Date(((Number) v).longValue()));
        return null;
    }

    public SnapshotMetadata getMetadata() { return metadata; }

    public Timestamp getCreateTime() {
        return createTime > 0 ? new Timestamp(new java.util.Date(createTime)) : null;
    }

    public Timestamp getUpdateTime() {
        return updateTime > 0 ? new Timestamp(new java.util.Date(updateTime)) : null;
    }

    public FirebaseFirestore getFirestore() { return firestore; }
}
