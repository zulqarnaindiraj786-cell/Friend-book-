package com.google.firebase.firestore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.firebase.Timestamp;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

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

    public DocumentSnapshot(
            FirebaseFirestore firestore,
            String path,
            @Nullable Map<String, Object> data,
            boolean exists,
            boolean isFromCache) {
        this.firestore = firestore;
        this.path = path == null ? "" : path;
        int slash = this.path.lastIndexOf('/');
        this.id = slash >= 0 ? this.path.substring(slash + 1) : this.path;
        this.exists = exists && data != null;
        this.data = this.exists ? new LinkedHashMap<>(data) : null;
        this.metadata = new SnapshotMetadata(false, isFromCache);
    }

    @NonNull
    public String getId() {
        return id;
    }

    @NonNull
    public DocumentReference getReference() {
        FirebaseFirestore db = firestore != null ? firestore : FirebaseFirestore.getInstance();
        return new DocumentReference(db, path);
    }

    @NonNull
    public SnapshotMetadata getMetadata() {
        return metadata;
    }

    public boolean exists() {
        return exists;
    }

    @Nullable
    public Map<String, Object> getData() {
        return data == null ? null : new LinkedHashMap<>(data);
    }

    @Nullable
    public Map<String, Object> getData(@NonNull ServerTimestampBehavior behavior) {
        return getData();
    }

    public boolean contains(@NonNull String field) {
        if (!exists || data == null || field == null) return false;
        if (data.containsKey(field)) return true;
        if (field.contains(".")) {
            return resolveDot(field) != UNRESOLVED;
        }
        return false;
    }

    public boolean contains(@NonNull FieldPath fieldPath) {
        return fieldPath != null && contains(fieldPath.getCanonicalString());
    }

    private static final Object UNRESOLVED = new Object();

    private Object resolveDot(String field) {
        if (data == null || field == null) return UNRESOLVED;
        if ("__name__".equals(field)) return id;
        if (data.containsKey(field)) return data.get(field);
        if (field.contains(".")) {
            Object cur = data;
            for (String part : field.split("\\.")) {
                if (!(cur instanceof Map)) return UNRESOLVED;
                Map<?, ?> m = (Map<?, ?>) cur;
                if (!m.containsKey(part)) return UNRESOLVED;
                cur = m.get(part);
            }
            return cur;
        }
        return UNRESOLVED;
    }

    @Nullable
    public Object get(@NonNull String field) {
        if (!exists || data == null || field == null) return null;
        Object val = resolveDot(field);
        return val == UNRESOLVED ? null : val;
    }

    @Nullable
    public Object get(@NonNull String field, @NonNull ServerTimestampBehavior behavior) {
        return get(field);
    }

    @Nullable
    public Object get(@NonNull FieldPath fieldPath) {
        return fieldPath == null ? null : get(fieldPath.getCanonicalString());
    }

    @Nullable
    public Object get(@NonNull FieldPath fieldPath, @NonNull ServerTimestampBehavior behavior) {
        return get(fieldPath);
    }

    @Nullable
    public String getString(@NonNull String field) {
        Object v = get(field);
        if (v == null) return null;
        return v instanceof String ? (String) v : String.valueOf(v);
    }

    @Nullable
    public Boolean getBoolean(@NonNull String field) {
        Object v = get(field);
        if (v == null) return null;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).intValue() != 0;
        if (v instanceof String) {
            String s = ((String) v).trim();
            if ("true".equalsIgnoreCase(s) || "1".equals(s)) return Boolean.TRUE;
            if ("false".equalsIgnoreCase(s) || "0".equals(s)) return Boolean.FALSE;
        }
        return null;
    }

    @Nullable
    public Long getLong(@NonNull String field) {
        Object v = get(field);
        if (v == null) return null;
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof Timestamp) return ((Timestamp) v).toDate().getTime();
        if (v instanceof Date) return ((Date) v).getTime();
        if (v instanceof String) {
            try { return Long.parseLong(((String) v).trim()); }
            catch (NumberFormatException ignored) {
                try { return (long) Double.parseDouble(((String) v).trim()); }
                catch (NumberFormatException ignored2) { return null; }
            }
        }
        return null;
    }

    @Nullable
    public Double getDouble(@NonNull String field) {
        Object v = get(field);
        if (v == null) return null;
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) {
            try { return Double.parseDouble(((String) v).trim()); }
            catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    @Nullable
    public Timestamp getTimestamp(@NonNull String field) {
        Object v = get(field);
        if (v == null) return null;
        if (v instanceof Timestamp) return (Timestamp) v;
        if (v instanceof Date) return new Timestamp((Date) v);
        if (v instanceof Number) return new Timestamp(new Date(((Number) v).longValue()));
        return null;
    }

    @Nullable
    public Timestamp getTimestamp(@NonNull String field, @NonNull ServerTimestampBehavior behavior) {
        return getTimestamp(field);
    }

    @Nullable
    public Date getDate(@NonNull String field) {
        Timestamp ts = getTimestamp(field);
        return ts == null ? null : ts.toDate();
    }

    @Nullable
    public Date getDate(@NonNull String field, @NonNull ServerTimestampBehavior behavior) {
        return getDate(field);
    }

    @Nullable
    public <T> T toObject(@NonNull Class<T> valueType) {
        if (!exists || data == null || valueType == null) return null;
        try {
            T instance = valueType.getDeclaredConstructor().newInstance();
            for (Field f : valueType.getFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                Object val = data.get(f.getName());
                if (val == null) continue;
                Class<?> ft = f.getType();
                if (ft.isAssignableFrom(val.getClass())) {
                    f.set(instance, val);
                } else if ((ft == long.class || ft == Long.class) && val instanceof Number) {
                    f.set(instance, ((Number) val).longValue());
                } else if ((ft == int.class || ft == Integer.class) && val instanceof Number) {
                    f.set(instance, ((Number) val).intValue());
                } else if ((ft == double.class || ft == Double.class) && val instanceof Number) {
                    f.set(instance, ((Number) val).doubleValue());
                } else if ((ft == boolean.class || ft == Boolean.class) && val instanceof Boolean) {
                    f.set(instance, val);
                } else if (ft == String.class) {
                    f.set(instance, String.valueOf(val));
                }
            }
            return instance;
        } catch (Throwable ignored) {
            return null;
        }
    }

    Map<String, Object> rawDataOrEmpty() {
        return data == null ? Collections.emptyMap() : data;
    }
}
