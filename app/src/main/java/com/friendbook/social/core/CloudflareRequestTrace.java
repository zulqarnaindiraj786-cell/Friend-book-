package com.friendbook.social.core;

import com.friendbook.social.BuildConfig;

/** Explicit debug-only, device-local trace. Adds no HTTP requests or D1 work. */
public final class CloudflareRequestTrace {
    private static final RequestWindowCounter COUNTER = new RequestWindowCounter();
    private CloudflareRequestTrace() { }

    public static void startAppEntryWindow() {
        if (!BuildConfig.DEBUG) return;
        if (!COUNTER.begin(android.os.SystemClock.elapsedRealtime())) return;
        android.util.Log.i("FBQuotaWindow", "START app_entry_60s shared_http_only");
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            java.util.Map<String, Integer> counts = COUNTER.finish();
            int total = 0;
            for (int count : counts.values()) total += count;
            org.json.JSONObject report = new org.json.JSONObject();
            try {
                report.put("scope", "app_entry_60s_shared_http_attempts");
                report.put("windowMs", RequestWindowCounter.WINDOW_MS);
                report.put("attempts", total);
                report.put("categories", new org.json.JSONObject(counts));
                android.util.Log.i("FBQuotaWindow", report.toString());
            } catch (org.json.JSONException ignored) { /* Diagnostics never affect requests. */ }
        }, RequestWindowCounter.WINDOW_MS);
    }

    static void record(okhttp3.Request request) {
        if (!BuildConfig.DEBUG) return;
        COUNTER.record(android.os.SystemClock.elapsedRealtime(), request.method(),
                request.url().encodedPath(), request.url().queryParameter("path"));
    }
}
