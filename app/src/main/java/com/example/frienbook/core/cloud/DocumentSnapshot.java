package com.example.frienbook.core.cloud;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * One document, as it came back from Cloudflare D1.
 *
 * The accessors are the ones the app has always called, and they answer in the same Java types, so a
 * screen that reads a post or a profile did not have to change a single line: whole numbers are Long,
 * fractions Double, text String, flags Boolean, arrays List and objects Map. A field that is not in
 * the document answers null - never a synthesised zero or an empty string, because a count that was
 * invented on the phone is a lie the user can see.
 */
public class DocumentSnapshot {

    final String collectionPath;
    final String id;
    final Map<String, Object> data;
    final boolean exists;
    final String cursor;
    final long updatedAt;

    DocumentSnapshot(String collectionPath, String id, Map<String, Object> data, boolean exists,
                     String cursor, long updatedAt) {
        this.collectionPath = collectionPath;
        this.id = id;
        this.data = data == null ? new java.util.LinkedHashMap<String, Object>() : data;
        this.exists = exists;
        this.cursor = cursor;
        this.updatedAt = updatedAt;
    }

    /** A snapshot for a document that is not there: exists() is false and every field answers null. */
    static DocumentSnapshot missing(String collectionPath, String id) {
        return new DocumentSnapshot(collectionPath, id, null, false, null, 0L);
    }

    public String getId() {
        return id;
    }

    public boolean exists() {
        return exists;
    }

    public Map<String, Object> getData() {
        return data;
    }

    public Object get(String field) {
        return data.get(field);
    }

    public boolean contains(String field) {
        return data.containsKey(field);
    }

    public String getString(String field) {
        Object value = data.get(field);
        if (value == null) return null;
        return value instanceof String ? (String) value : String.valueOf(value);
    }

    public Boolean getBoolean(String field) {
        Object value = data.get(field);
        if (value == null) return null;
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).longValue() != 0L;
        if (value instanceof String) return Boolean.parseBoolean((String) value);
        return null;
    }

    public Long getLong(String field) {
        return Cloud.asLong(data.get(field));
    }

    public Double getDouble(String field) {
        return Cloud.asDouble(data.get(field));
    }

    /** Milliseconds since the epoch, whichever shape the value was stored in. */
    public Timestamp getTimestamp(String field) {
        Long millis = Cloud.asLong(data.get(field));
        return millis == null ? null : new Timestamp(millis);
    }

    public Date getDate(String field) {
        Timestamp timestamp = getTimestamp(field);
        return timestamp == null ? null : timestamp.toDate();
    }

    @SuppressWarnings("unchecked")
    public List<String> getStringList(String field) {
        Object value = data.get(field);
        List<String> out = new java.util.ArrayList<>();
        if (value instanceof List) {
            for (Object item : (List<Object>) value) if (item != null) out.add(String.valueOf(item));
        }
        return out;
    }

    /** The reference this snapshot came from, so a row in a list can be updated or deleted in place. */
    public DocumentReference getReference() {
        return new DocumentReference(collectionPath, id);
    }

    /** Where the next page starts after this document, for startAfter(). */
    public String getCursor() {
        return cursor;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }
}
