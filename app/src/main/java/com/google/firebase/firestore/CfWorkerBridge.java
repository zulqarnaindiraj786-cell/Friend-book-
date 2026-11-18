package com.google.firebase.firestore;

import android.os.Handler;
import android.os.Looper;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GetTokenResult;
import okhttp3.ConnectionPool;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Shared authenticated HTTP/2 transport to the Cloudflare Worker (D1 + RTDB).
 * Uses ONLY Firebase Auth ID tokens for identity; zero Firestore or RTDB traffic goes to Google.
 */
public final class CfWorkerBridge {
    public static final String WORKER_BASE_URL = "https://rough-firefly-dd7b.zulqarnaindiraj786.workers.dev";
    private static final MediaType JSON_MEDIA = MediaType.get("application/json; charset=utf-8");
    private static final String AUTO_ID_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RNG = new SecureRandom();

    public static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .connectionPool(new ConnectionPool(8, 5, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
            .build();

    public static final ExecutorService IO_POOL = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "cfdb-io");
        t.setDaemon(true);
        return t;
    });

    public static final ExecutorService TX_POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "cfdb-tx");
        t.setDaemon(true);
        return t;
    });

    public static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "cfdb-poll");
        t.setDaemon(true);
        return t;
    });

    private static volatile String cachedUid;
    private static volatile String cachedToken;
    private static volatile long cachedTokenExpiresAt;

    private CfWorkerBridge() {}

    public static String autoId() {
        StringBuilder sb = new StringBuilder(20);
        for (int i = 0; i < 20; i++) {
            sb.append(AUTO_ID_CHARS.charAt(RNG.nextInt(AUTO_ID_CHARS.length())));
        }
        return sb.toString();
    }

    public static void postMain(Runnable runnable) {
        try {
            Looper main = Looper.getMainLooper();
            if (main != null) {
                if (Thread.currentThread() == main.getThread()) {
                    runnable.run();
                } else {
                    new Handler(main).post(runnable);
                }
                return;
            }
        } catch (Throwable ignored) {
            // Plain JVM unit test without Android Looper
        }
        runnable.run();
    }

    public static String getAuthToken(boolean forceRefresh) throws FirebaseFirestoreException {
        FirebaseUser user;
        try {
            user = FirebaseAuth.getInstance().getCurrentUser();
        } catch (Throwable t) {
            throw new FirebaseFirestoreException("Auth unavailable", FirebaseFirestoreException.Code.UNAUTHENTICATED, t);
        }
        if (user == null) {
            throw new FirebaseFirestoreException("Not signed in", FirebaseFirestoreException.Code.UNAUTHENTICATED);
        }
        String uid = user.getUid();
        long now = System.currentTimeMillis();
        if (!forceRefresh && Objects.equals(uid, cachedUid) && cachedToken != null && now < cachedTokenExpiresAt) {
            return cachedToken;
        }
        try {
            GetTokenResult res = Tasks.await(user.getIdToken(forceRefresh), 15, TimeUnit.SECONDS);
            String tok = res != null ? res.getToken() : null;
            if (tok == null || tok.isEmpty()) {
                throw new FirebaseFirestoreException("Empty auth token", FirebaseFirestoreException.Code.UNAUTHENTICATED);
            }
            cachedUid = uid;
            cachedToken = tok;
            cachedTokenExpiresAt = now + 40L * 60_000L;
            return tok;
        } catch (FirebaseFirestoreException e) {
            throw e;
        } catch (Exception e) {
            throw new FirebaseFirestoreException("Token fetch failed", FirebaseFirestoreException.Code.UNAUTHENTICATED, e);
        }
    }

    public static JSONObject requestJson(String method, String path, JSONObject body) throws FirebaseFirestoreException {
        return requestJsonInternal(method, path, body, false);
    }

    private static JSONObject requestJsonInternal(String method, String path, JSONObject body, boolean retried)
            throws FirebaseFirestoreException {
        String token = getAuthToken(retried);
        String url = path.startsWith("http") ? path : (WORKER_BASE_URL + (path.startsWith("/") ? path : ("/" + path)));
        RequestBody reqBody = null;
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            if (body != null || !"DELETE".equalsIgnoreCase(method)) {
                String payload = body != null ? body.toString() : "{}";
                reqBody = RequestBody.create(payload, JSON_MEDIA);
            }
        }
        Request req = new Request.Builder()
                .url(url)
                .method(method, reqBody)
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .build();

        try (Response res = HTTP.newCall(req).execute()) {
            int code = res.code();
            if (code == 401 && !retried) {
                return requestJsonInternal(method, path, body, true);
            }
            ResponseBody rb = res.body();
            String raw = rb != null ? rb.string() : "";
            if (!res.isSuccessful()) {
                FirebaseFirestoreException.Code fsCode =
                        code == 401 ? FirebaseFirestoreException.Code.UNAUTHENTICATED
                        : code == 403 ? FirebaseFirestoreException.Code.PERMISSION_DENIED
                        : code == 404 ? FirebaseFirestoreException.Code.NOT_FOUND
                        : code == 400 ? FirebaseFirestoreException.Code.INVALID_ARGUMENT
                        : FirebaseFirestoreException.Code.UNAVAILABLE;
                throw new FirebaseFirestoreException("Cloudflare HTTP " + code + ": " + raw, fsCode);
            }
            if (raw.isEmpty()) return new JSONObject();
            return new JSONObject(raw);
        } catch (FirebaseFirestoreException e) {
            throw e;
        } catch (IOException e) {
            throw new FirebaseFirestoreException("Network error contacting Cloudflare", FirebaseFirestoreException.Code.UNAVAILABLE, e);
        } catch (JSONException e) {
            throw new FirebaseFirestoreException("Invalid JSON from Cloudflare", FirebaseFirestoreException.Code.INTERNAL, e);
        }
    }

    public static Object toJsonValue(Object v) throws JSONException {
        if (v == null || v == JSONObject.NULL) return JSONObject.NULL;
        if (v instanceof FieldValue) {
            FieldValue fv = (FieldValue) v;
            JSONObject o = new JSONObject();
            o.put("__fv", fv.getKind());
            if (FieldValue.KIND_INCREMENT.equals(fv.getKind())) {
                double n = fv.getNumberOperand();
                if (n == Math.rint(n) && !Double.isInfinite(n)) {
                    o.put("n", (long) n);
                } else {
                    o.put("n", n);
                }
            } else if (FieldValue.KIND_ARRAY_UNION.equals(fv.getKind()) || FieldValue.KIND_ARRAY_REMOVE.equals(fv.getKind())) {
                JSONArray arr = new JSONArray();
                for (Object item : fv.getElements()) {
                    arr.put(toJsonValue(item));
                }
                o.put("v", arr);
            }
            return o;
        }
        if (v instanceof Timestamp) {
            return ((Timestamp) v).toDate().getTime();
        }
        if (v instanceof Date) {
            return ((Date) v).getTime();
        }
        if (v instanceof Boolean || v instanceof Integer || v instanceof Long || v instanceof String) {
            return v;
        }
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) return 0;
            if (v instanceof Float) return d;
            return v;
        }
        if (v instanceof JSONObject || v instanceof JSONArray) {
            return v;
        }
        if (v instanceof Map) {
            JSONObject obj = new JSONObject();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (e.getKey() != null) {
                    obj.put(String.valueOf(e.getKey()), toJsonValue(e.getValue()));
                }
            }
            return obj;
        }
        if (v instanceof Iterable) {
            JSONArray arr = new JSONArray();
            for (Object item : (Iterable<?>) v) {
                arr.put(toJsonValue(item));
            }
            return arr;
        }
        if (v.getClass().isArray()) {
            JSONArray arr = new JSONArray();
            int len = java.lang.reflect.Array.getLength(v);
            for (int i = 0; i < len; i++) {
                arr.put(toJsonValue(java.lang.reflect.Array.get(v, i)));
            }
            return arr;
        }
        // POJO fallback
        return toJsonValue(pojoToMap(v));
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> pojoToMap(Object obj) {
        if (obj == null) return new LinkedHashMap<>();
        if (obj instanceof Map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) obj).entrySet()) {
                if (e.getKey() != null) copy.put(String.valueOf(e.getKey()), e.getValue());
            }
            return copy;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Field f : obj.getClass().getFields()) {
            if (Modifier.isStatic(f.getModifiers()) || Modifier.isTransient(f.getModifiers())) continue;
            try {
                out.put(f.getName(), f.get(obj));
            } catch (IllegalAccessException ignored) {}
        }
        return out;
    }

    public static Object fromJsonValue(Object v) throws JSONException {
        if (v == null || v == JSONObject.NULL) return null;
        if (v instanceof JSONObject) return jsonToMap((JSONObject) v);
        if (v instanceof JSONArray) return jsonToList((JSONArray) v);
        if (v instanceof Integer) return ((Integer) v).longValue();
        return v;
    }

    public static Map<String, Object> jsonToMap(JSONObject obj) throws JSONException {
        Map<String, Object> out = new LinkedHashMap<>();
        if (obj == null) return out;
        Iterator<String> it = obj.keys();
        while (it.hasNext()) {
            String k = it.next();
            out.put(k, fromJsonValue(obj.get(k)));
        }
        return out;
    }

    public static List<Object> jsonToList(JSONArray arr) throws JSONException {
        List<Object> out = new ArrayList<>(arr == null ? 0 : arr.length());
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            out.add(fromJsonValue(arr.get(i)));
        }
        return out;
    }
}
