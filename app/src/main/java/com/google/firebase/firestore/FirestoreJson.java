package com.google.firebase.firestore;

import com.google.firebase.Timestamp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** JSON codec between Java values and the Cloudflare D1 document API. */
final class FirestoreJson {
    private FirestoreJson() {}

    static Object encode(Object value) {
        if (value == null) return JSONObject.NULL;
        if (value instanceof FieldValue) return ((FieldValue) value).toJson();
        if (value instanceof Timestamp) {
            JSONObject o = new JSONObject();
            try {
                o.put("__type", "timestamp");
                o.put("millis", ((Timestamp) value).toDate().getTime());
            } catch (Exception ignored) { }
            return o;
        }
        if (value instanceof DocumentReference) {
            JSONObject o = new JSONObject();
            try {
                o.put("__type", "reference");
                o.put("path", ((DocumentReference) value).getPath());
            } catch (Exception ignored) { }
            return o;
        }
        if (value instanceof Map) {
            JSONObject o = new JSONObject();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                try { o.put(String.valueOf(e.getKey()), encode(e.getValue())); } catch (Exception ignored) { }
            }
            return o;
        }
        if (value instanceof List) {
            JSONArray a = new JSONArray();
            for (Object item : (List<?>) value) a.put(encode(item));
            return a;
        }
        if (value instanceof Boolean || value instanceof Number || value instanceof String) return value;
        return String.valueOf(value);
    }

    static JSONObject encodeMap(Map<String, Object> map) {
        JSONObject o = new JSONObject();
        if (map == null) return o;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            try { o.put(e.getKey(), encode(e.getValue())); } catch (Exception ignored) { }
        }
        return o;
    }

    static Object decode(Object value) {
        if (value == null || value == JSONObject.NULL) return null;
        if (value instanceof JSONObject) {
            JSONObject o = (JSONObject) value;
            String type = o.optString("__type", "");
            if ("timestamp".equals(type)) {
                return new Timestamp(new java.util.Date(o.optLong("millis", 0L)));
            }
            if ("reference".equals(type)) {
                return new DocumentReference(FirebaseFirestore.getInstance(), o.optString("path", ""));
            }
            Map<String, Object> out = new HashMap<>();
            Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                out.put(k, decode(o.opt(k)));
            }
            return out;
        }
        if (value instanceof JSONArray) {
            JSONArray a = (JSONArray) value;
            List<Object> out = new ArrayList<>(a.length());
            for (int i = 0; i < a.length(); i++) out.add(decode(a.opt(i)));
            return out;
        }
        if (value instanceof Number) {
            // org.json gives Integer/Long/Double; normalize to Long/Double like Firestore.
            if (value instanceof Double || value instanceof Float) return ((Number) value).doubleValue();
            return ((Number) value).longValue();
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> decodeMap(JSONObject o) {
        Map<String, Object> out = new HashMap<>();
        if (o == null) return out;
        Iterator<String> keys = o.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            out.put(k, decode(o.opt(k)));
        }
        return out;
    }
}
