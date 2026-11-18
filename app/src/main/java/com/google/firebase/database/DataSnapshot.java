package com.google.firebase.database;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class DataSnapshot {
    private final String key;
    private final Object value;

    public DataSnapshot(String key, @Nullable Object value) {
        this.key = key == null ? "" : key;
        this.value = value;
    }

    public boolean exists() {
        return value != null;
    }

    @Nullable
    public String getKey() {
        return key;
    }

    @Nullable
    public Object getValue() {
        return value;
    }

    @SuppressWarnings("unchecked")
    @Nullable
    public <T> T getValue(@NonNull Class<T> valueType) {
        if (value == null || valueType == null) return null;
        if (valueType.isInstance(value)) return (T) value;
        if (valueType == Long.class || valueType == long.class) {
            if (value instanceof Number) return (T) Long.valueOf(((Number) value).longValue());
            try { return (T) Long.valueOf(Long.parseLong(String.valueOf(value).trim())); }
            catch (NumberFormatException ignored) { return null; }
        }
        if (valueType == Integer.class || valueType == int.class) {
            if (value instanceof Number) return (T) Integer.valueOf(((Number) value).intValue());
            try { return (T) Integer.valueOf(Integer.parseInt(String.valueOf(value).trim())); }
            catch (NumberFormatException ignored) { return null; }
        }
        if (valueType == Double.class || valueType == double.class) {
            if (value instanceof Number) return (T) Double.valueOf(((Number) value).doubleValue());
            try { return (T) Double.valueOf(Double.parseDouble(String.valueOf(value).trim())); }
            catch (NumberFormatException ignored) { return null; }
        }
        if (valueType == Boolean.class || valueType == boolean.class) {
            if (value instanceof Boolean) return (T) value;
            if (value instanceof Number) return (T) Boolean.valueOf(((Number) value).intValue() != 0);
            String s = String.valueOf(value).trim();
            if ("true".equalsIgnoreCase(s) || "1".equals(s)) return (T) Boolean.TRUE;
            if ("false".equalsIgnoreCase(s) || "0".equals(s)) return (T) Boolean.FALSE;
            return null;
        }
        if (valueType == String.class) {
            return (T) String.valueOf(value);
        }
        return null;
    }

    @NonNull
    public DataSnapshot child(@NonNull String path) {
        if (path == null || path.isEmpty()) return this;
        Object cur = value;
        String lastKey = key;
        for (String seg : path.split("/")) {
            if (seg.isEmpty()) continue;
            lastKey = seg;
            if (cur instanceof Map) {
                cur = ((Map<?, ?>) cur).get(seg);
            } else {
                cur = null;
            }
        }
        return new DataSnapshot(lastKey, cur);
    }

    public boolean hasChild(@NonNull String path) {
        return child(path).exists();
    }

    public long getChildrenCount() {
        if (value instanceof Map) return ((Map<?, ?>) value).size();
        if (value instanceof List) return ((List<?>) value).size();
        return 0L;
    }

    @NonNull
    public Iterable<DataSnapshot> getChildren() {
        if (value instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) value;
            List<DataSnapshot> list = new ArrayList<>(m.size());
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() != null) {
                    list.add(new DataSnapshot(String.valueOf(e.getKey()), e.getValue()));
                }
            }
            return list;
        }
        if (value instanceof List) {
            List<?> l = (List<?>) value;
            List<DataSnapshot> list = new ArrayList<>(l.size());
            for (int i = 0; i < l.size(); i++) {
                list.add(new DataSnapshot(String.valueOf(i), l.get(i)));
            }
            return list;
        }
        return Collections.emptyList();
    }
}
