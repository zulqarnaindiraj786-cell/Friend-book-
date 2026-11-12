package com.example.frienbook.core.cloud;

import android.os.Handler;
import android.os.Looper;

import com.example.frienbook.core.CloudTransport;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The transport and codec behind the Cloudflare document API - the only place in the app that knows
 * the Worker's URL shapes, its JSON, and how a Firebase ID token is attached to a request.
 *
 * Firebase is authentication here and nowhere else: the token proves who is holding the phone, and
 * the Worker decides what that person may read or write. Nothing in this package caches a document
 * on disk, synthesises a value, or retries behind the caller's back - when the network is down the
 * Task fails and the screen shows what it has always shown for an error.
 *
 * Every call runs on {@link #IO} and completes a real {@link Task}, so listeners registered the way
 * the app has always registered them (addOnSuccessListener / addOnFailureListener) are delivered on
 * the main thread by the Tasks library itself.
 */
final class Cloud {

    private Cloud() { }

    static final ExecutorService IO = Executors.newFixedThreadPool(4);
    static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** The Worker refuses more than 50 rows per request, so a bigger page is fetched in slices. */
    static final int PAGE = 50;
    /** 400 documents is the largest single page any screen asks for (the admin broadcast). */
    static final int MAX_PAGES = 8;

    interface Job<T> {
        T run() throws Exception;
    }

    /** Runs one bounded unit of work off the main thread and hands the result back as a Task. */
    static <T> Task<T> submit(final Job<T> job) {
        final TaskCompletionSource<T> source = new TaskCompletionSource<>();
        try {
            IO.execute(() -> {
                try {
                    source.setResult(job.run());
                } catch (Exception failed) {
                    source.setException(wrap(failed));
                } catch (Throwable failed) {
                    source.setException(new CloudException("unexpected error", failed));
                }
            });
        } catch (Throwable rejected) {
            source.setException(new CloudException("executor rejected the request", rejected));
        }
        return source.getTask();
    }

    /** A Task that is already finished, for the paths that have nothing to fetch. */
    static <T> Task<T> result(T value) {
        final TaskCompletionSource<T> source = new TaskCompletionSource<>();
        source.setResult(value);
        return source.getTask();
    }

    static CloudException wrap(Exception error) {
        if (error instanceof CloudException) return (CloudException) error;
        String message = error.getMessage();
        return new CloudException(message == null || message.isEmpty() ? "request failed" : message, error);
    }

    // ------------------------------------------------------------------ HTTP

    /**
     * One request, one answer. A refusal becomes a CloudException carrying both the Worker's reason and
     * the status it chose, so a caller can tell "gone" from "forbidden" from "offline".
     */
    static JSONObject call(String path, String method, JSONObject body) throws CloudException {
        try {
            JSONObject answer = CloudTransport.request(path, method, body);
            return answer == null ? new JSONObject() : answer;
        } catch (CloudTransport.HttpError refused) {
            throw new CloudException(refused.status, refused.getMessage());
        } catch (Exception failed) {
            String message = failed.getMessage();
            throw new CloudException(message == null || message.isEmpty() ? "Cloudflare request failed" : message,
                    failed);
        }
    }

    static String encode(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return "";
        }
    }

    /** Builds /v1/docs/<collection>?a=b&c=d from an ordered parameter map. */
    static String listUrl(String collectionPath, Map<String, String> params) {
        StringBuilder url = new StringBuilder("/v1/docs/").append(collectionPath);
        boolean first = true;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (entry.getValue() == null) continue;
            url.append(first ? '?' : '&').append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
            first = false;
        }
        return url.toString();
    }

    // ------------------------------------------------------------------ JSON in

    /**
     * Turns one app map into the Worker's write body.
     *
     * Firestore let a single map mix plain values with FieldValue markers, and the Worker keeps that
     * arithmetic on the server: plain values travel in `fields`, markers travel in `ops` (set / inc /
     * add / remove / unset / stamp). That split is the reason two people liking the same post at the
     * same moment cannot lose one of the likes.
     */
    static JSONObject writeBody(Map<String, Object> data, boolean replace) {
        Map<String, Object> fields = new LinkedHashMap<>();
        Map<String, Object> inc = new LinkedHashMap<>();
        Map<String, Object> add = new LinkedHashMap<>();
        Map<String, Object> remove = new LinkedHashMap<>();
        List<String> unset = new ArrayList<>();
        List<String> stamp = new ArrayList<>();

        if (data != null) {
            for (Map.Entry<String, Object> entry : data.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                if (key == null) continue;
                if (value instanceof FieldValue) {
                    FieldValue marker = (FieldValue) value;
                    switch (marker.kind) {
                        case FieldValue.SERVER_TIMESTAMP:
                            stamp.add(key);
                            break;
                        case FieldValue.DELETE:
                            unset.add(key);
                            break;
                        case FieldValue.INCREMENT:
                            inc.put(key, marker.number);
                            break;
                        case FieldValue.ARRAY_UNION:
                            add.put(key, marker.values);
                            break;
                        case FieldValue.ARRAY_REMOVE:
                            remove.put(key, marker.values);
                            break;
                        default:
                            break;
                    }
                    continue;
                }
                fields.put(key, value);
            }
        }

        JSONObject ops = new JSONObject();
        putIfNotEmpty(ops, "inc", inc);
        putIfNotEmpty(ops, "add", add);
        putIfNotEmpty(ops, "remove", remove);
        putArrayIfNotEmpty(ops, "unset", unset);
        putArrayIfNotEmpty(ops, "stamp", stamp);

        JSONObject body = new JSONObject();
        try {
            body.put("op", replace ? "replace" : "merge");
            body.put("fields", toJson(fields));
            if (ops.length() > 0) body.put("ops", ops);
        } catch (Exception ignored) {
            // A body that cannot be encoded is reported by the caller as a failed Task.
        }
        return body;
    }

    /** The same split, in the shape a batch operation carries. */
    static JSONObject batchOps(Map<String, Object> data) {
        JSONObject body = writeBody(data, false);
        return body.optJSONObject("ops");
    }

    static JSONObject batchFields(Map<String, Object> data) {
        JSONObject body = writeBody(data, false);
        return body.optJSONObject("fields");
    }

    private static void putIfNotEmpty(JSONObject target, String key, Map<String, Object> values) {
        if (values == null || values.isEmpty()) return;
        try {
            target.put(key, toJson(values));
        } catch (Exception ignored) { }
    }

    private static void putArrayIfNotEmpty(JSONObject target, String key, List<String> values) {
        if (values == null || values.isEmpty()) return;
        try {
            target.put(key, new JSONArray(values));
        } catch (Exception ignored) { }
    }

    /** Encodes any app value: numbers, text, booleans, lists, maps, dates and our own Timestamp. */
    static JSONObject toJson(Map<String, Object> map) {
        JSONObject out = new JSONObject();
        if (map == null) return out;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (entry.getKey() == null) continue;
            try {
                out.put(entry.getKey(), value(entry.getValue()));
            } catch (Exception ignored) {
                // One unencodable field must not cost the whole write.
            }
        }
        return out;
    }

    private static Object value(Object raw) throws Exception {
        if (raw == null) return JSONObject.NULL;
        if (raw instanceof JSONObject || raw instanceof JSONArray) return raw;
        if (raw instanceof String || raw instanceof Boolean) return raw;
        if (raw instanceof Integer || raw instanceof Long) return ((Number) raw).longValue();
        if (raw instanceof Float || raw instanceof Double) return ((Number) raw).doubleValue();
        if (raw instanceof Number) return ((Number) raw).doubleValue();
        if (raw instanceof Timestamp) return ((Timestamp) raw).getTime();
        if (raw instanceof Date) return ((Date) raw).getTime();
        if (raw instanceof FieldValue) return JSONObject.NULL;   // markers are split out before this
        if (raw instanceof Map) return toJson(castMap(raw));
        if (raw instanceof Collection) {
            JSONArray array = new JSONArray();
            for (Object item : (Collection<?>) raw) array.put(value(item));
            return array;
        }
        if (raw.getClass().isArray()) {
            JSONArray array = new JSONArray();
            int length = java.lang.reflect.Array.getLength(raw);
            for (int i = 0; i < length; i++) array.put(value(java.lang.reflect.Array.get(raw, i)));
            return array;
        }
        if (raw instanceof Enum) return ((Enum<?>) raw).name();
        return String.valueOf(raw);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<Object, Object> entry : ((Map<Object, Object>) raw).entrySet()) {
            out.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return out;
    }

    /** A query value becomes text for the URL: timestamps as millis, lists as comma-joined ids. */
    static String param(Object raw) {
        if (raw == null) return "";
        if (raw instanceof Timestamp) return String.valueOf(((Timestamp) raw).getTime());
        if (raw instanceof Date) return String.valueOf(((Date) raw).getTime());
        if (raw instanceof Boolean) return ((Boolean) raw) ? "true" : "false";
        if (raw instanceof Collection) {
            StringBuilder out = new StringBuilder();
            for (Object item : (Collection<?>) raw) {
                if (out.length() > 0) out.append(',');
                out.append(param(item));
            }
            return out.toString();
        }
        if (raw != null && raw.getClass().isArray()) {
            StringBuilder out = new StringBuilder();
            int length = java.lang.reflect.Array.getLength(raw);
            for (int i = 0; i < length; i++) {
                if (out.length() > 0) out.append(',');
                out.append(param(java.lang.reflect.Array.get(raw, i)));
            }
            return out.toString();
        }
        return String.valueOf(raw);
    }

    // ------------------------------------------------------------------ JSON out

    /**
     * Decodes a stored document into the Java types the app has always received, so helpers like
     * Format.ts(), Format.str() and Format.listOf() keep working without a single change: whole
     * numbers become Long, fractions Double, text String, flags Boolean, and lists and maps recurse.
     */
    static Map<String, Object> toMap(JSONObject object) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (object == null) return out;
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            out.put(key, unwrap(object.opt(key)));
        }
        return out;
    }

    static List<Object> toList(JSONArray array) {
        List<Object> out = new ArrayList<>();
        if (array == null) return out;
        for (int i = 0; i < array.length(); i++) out.add(unwrap(array.opt(i)));
        return out;
    }

    private static Object unwrap(Object value) {
        if (value == null || value == JSONObject.NULL) return null;
        if (value instanceof JSONObject) return toMap((JSONObject) value);
        if (value instanceof JSONArray) return toList((JSONArray) value);
        if (value instanceof Integer) return ((Integer) value).longValue();
        if (value instanceof Long || value instanceof Double || value instanceof Boolean || value instanceof String) {
            return value;
        }
        if (value instanceof Number) {
            double asDouble = ((Number) value).doubleValue();
            if (asDouble == Math.rint(asDouble) && Math.abs(asDouble) < 9.0e15) return (long) asDouble;
            return asDouble;
        }
        return String.valueOf(value);
    }

    /** Reads a number out of a decoded value, whatever shape the Worker stored it in. */
    static Long asLong(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        if (value instanceof String) {
            try {
                return (long) Double.parseDouble((String) value);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (value instanceof Boolean) return ((Boolean) value) ? 1L : 0L;
        return null;
    }

    static Double asDouble(Object value) {
        if (value instanceof Number) return ((Number) value).doubleValue();
        if (value instanceof String) {
            try {
                return Double.parseDouble((String) value);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (value instanceof Boolean) return ((Boolean) value) ? 1.0 : 0.0;
        return null;
    }

    static Map<String, Object> emptyMap() {
        return new HashMap<>();
    }
}
