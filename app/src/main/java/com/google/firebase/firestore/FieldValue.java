package com.google.firebase.firestore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;

/** Server-side field transforms, resolved by the Cloudflare Worker. */
public class FieldValue {
    private final String kind;
    private final Object value;
    private final Object[] values;

    private FieldValue(String kind, Object value, Object[] values) {
        this.kind = kind;
        this.value = value;
        this.values = values;
    }

    public static FieldValue serverTimestamp() {
        return new FieldValue("serverTimestamp", null, null);
    }

    public static FieldValue increment(long operand) {
        return new FieldValue("increment", operand, null);
    }

    public static FieldValue increment(double operand) {
        return new FieldValue("increment", operand, null);
    }

    public static FieldValue arrayUnion(Object... elements) {
        return new FieldValue("arrayUnion", null, elements == null ? new Object[0] : Arrays.copyOf(elements, elements.length));
    }

    public static FieldValue arrayRemove(Object... elements) {
        return new FieldValue("arrayRemove", null, elements == null ? new Object[0] : Arrays.copyOf(elements, elements.length));
    }

    public static FieldValue delete() {
        return new FieldValue("delete", null, null);
    }

    JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("__fieldValue", kind);
            if ("increment".equals(kind)) o.put("value", value);
            if (values != null) {
                JSONArray a = new JSONArray();
                for (Object v : values) a.put(FirestoreJson.encode(v));
                o.put("values", a);
            }
        } catch (Exception ignored) { }
        return o;
    }
}
