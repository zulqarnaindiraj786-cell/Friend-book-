package com.friendbook.social.push;

import com.friendbook.social.BuildConfig;
import com.friendbook.social.core.Fb;
import com.google.android.gms.tasks.Tasks;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Runs only off the UI thread. Private service credentials NEVER enter this process. */
public final class PushGateway {
    public static class Response {
        public final int status;
        public final JSONObject data;
        Response(int s, JSONObject d) { status = s; data = d; }
        public boolean retryable() { return status == 409 || status == 429 || status >= 500; }
    }
    public static Response call(JSONObject body, String bearer) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(BuildConfig.SUPABASE_URL + "/functions/v1/send-fcm-notification").openConnection();
        try {
            c.setRequestMethod("POST"); c.setDoOutput(true); c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(10000); c.setReadTimeout(25000);
            c.setRequestProperty("Authorization", "Bearer " + bearer);
            c.setRequestProperty("Content-Type", "application/json");
            try (java.io.OutputStream out = c.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            int status = c.getResponseCode();
            java.io.InputStream stream = status < 400 ? c.getInputStream() : c.getErrorStream();
            String text = "";
            if (stream != null) try (java.io.InputStream in = stream; java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                byte[] buffer = new byte[2048]; int n;
                while ((n = in.read(buffer)) != -1 && out.size() < 16000) out.write(buffer, 0, n);
                text = out.toString("UTF-8");
            }
            JSONObject data;
            try { data = new JSONObject(text); } catch (Exception e) { data = new JSONObject().put("error", "GATEWAY_HTTP_" + status); }
            return new Response(status, data);
        } finally { c.disconnect(); }
    }
    public static String idToken() throws Exception {
        com.google.firebase.auth.FirebaseUser user = Fb.auth().getCurrentUser();
        if (user == null) throw new IllegalStateException("Sign in required");
        return Tasks.await(user.getIdToken(false), 15, TimeUnit.SECONDS).getToken();
    }
}
