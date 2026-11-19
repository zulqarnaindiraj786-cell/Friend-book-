package com.friendbook.social.d1.documents;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Authenticated HTTP transport to the Cloudflare Worker D1 document API. */
final class D1DocumentTransport {
    static final String BASE = "https://rough-firefly-dd7b.zulqarnaindiraj786.workers.dev";
    private D1DocumentTransport() {}

    static String idToken() throws Exception {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) throw new D1DocumentStoreException(D1DocumentStoreException.Code.UNAUTHENTICATED, "Not signed in");
        String token = Tasks.await(user.getIdToken(false), 15, TimeUnit.SECONDS).getToken();
        if (token == null || token.isEmpty()) throw new D1DocumentStoreException(D1DocumentStoreException.Code.UNAUTHENTICATED, "Missing auth token");
        return token;
    }

    static JSONObject request(String method, String pathAndQuery, JSONObject body) throws Exception {
        String token = idToken();
        HttpURLConnection conn = (HttpURLConnection) new URL(BASE + pathAndQuery).openConnection();
        try {
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod(method);
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Accept", "application/json");
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(bytes.length);
                conn.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = conn.getOutputStream()) { out.write(bytes); }
            }
            int code = conn.getResponseCode();
            InputStream rs = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            StringBuilder sb = new StringBuilder();
            if (rs != null) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(rs, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                }
            }
            JSONObject data;
            try { data = sb.length() > 0 ? new JSONObject(sb.toString()) : new JSONObject(); }
            catch (Exception e) {
                if (code >= 200 && code < 300)
                    throw new D1DocumentStoreException(D1DocumentStoreException.Code.UNAVAILABLE, "Invalid server response", e);
                data = new JSONObject();
            }
            // Only an explicit missing-document response is a miss. A proxy or route
            // 404 must not cause account bootstrap to recreate an existing profile.
            if (code == 404 && "GET".equals(method) && pathAndQuery.startsWith("/v1/firestore/document?")
                    && "NOT_FOUND".equals(data.optString("error"))) {
                return new JSONObject().put("exists", false);
            }
            if (code < 200 || code >= 300) {
                String err = data.optString("error", "HTTP_" + code);
                if ("NOT_FOUND".equals(err)) throw new D1DocumentStoreException(D1DocumentStoreException.Code.NOT_FOUND, err);
                if ("ALREADY_EXISTS".equals(err)) throw new D1DocumentStoreException(D1DocumentStoreException.Code.ALREADY_EXISTS, err);
                if (code == 401 || code == 403) throw new D1DocumentStoreException(D1DocumentStoreException.Code.PERMISSION_DENIED, err);
                throw new D1DocumentStoreException(D1DocumentStoreException.Code.UNAVAILABLE, err + " HTTP " + code);
            }
            return data;
        } finally {
            conn.disconnect();
        }
    }

    static String enc(String s) {
        return android.net.Uri.encode(s);
    }

    static JSONObject getDocument(String path) throws Exception {
        JSONObject data = request("GET", "/v1/firestore/document?path=" + enc(path), null);
        boolean present = data.optJSONObject("data") != null;
        if ((!data.has("exists") && !present) || (data.optBoolean("exists", false) && !present)
                || (data.has("exists") && !(data.opt("exists") instanceof Boolean)))
            throw new D1DocumentStoreException(D1DocumentStoreException.Code.UNAVAILABLE, "Invalid document response");
        return data;
    }

    static JSONObject putDocument(String path, JSONObject body) throws Exception {
        return request("PUT", "/v1/firestore/document?path=" + enc(path), body);
    }

    static JSONObject patchDocument(String path, JSONObject body) throws Exception {
        return request("PATCH", "/v1/firestore/document?path=" + enc(path), body);
    }

    static void deleteDocument(String path) throws Exception {
        request("DELETE", "/v1/firestore/document?path=" + enc(path), null);
    }

    static JSONObject addDocument(String parent, JSONObject body) throws Exception {
        return request("POST", "/v1/firestore/document?parent=" + enc(parent), body);
    }

    static JSONArray query(JSONObject body) throws Exception {
        JSONObject data = request("POST", "/v1/firestore/query", body);
        JSONArray arr = data.optJSONArray("documents");
        if (arr == null) throw new D1DocumentStoreException(D1DocumentStoreException.Code.UNAVAILABLE, "Invalid query response");
        return arr;
    }

    static int count(String parent, JSONArray filters) throws Exception {
        String q = "/v1/firestore/count?parent=" + enc(parent) + "&filters=" + enc(filters.toString());
        JSONObject data = request("GET", q, null);
        Object value = data.opt("count");
        if (!(value instanceof Number)) throw new D1DocumentStoreException(D1DocumentStoreException.Code.UNAVAILABLE, "Invalid count response");
        double count = ((Number) value).doubleValue();
        if (!Double.isFinite(count) || count < 0 || count > Integer.MAX_VALUE || count != Math.floor(count))
            throw new D1DocumentStoreException(D1DocumentStoreException.Code.UNAVAILABLE, "Invalid count response");
        return (int) count;
    }

    static void batch(JSONArray operations) throws Exception {
        JSONObject body = new JSONObject().put("operations", operations);
        request("POST", "/v1/firestore/batch", body);
    }

    static void transaction(JSONArray operations) throws Exception {
        JSONObject body = new JSONObject().put("operations", operations);
        request("POST", "/v1/firestore/transaction", body);
    }
}
