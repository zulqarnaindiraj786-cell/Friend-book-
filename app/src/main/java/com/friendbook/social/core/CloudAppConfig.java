package com.friendbook.social.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONObject;

/** Public feature switches only; no credentials or account data are sent. */
public final class CloudAppConfig {
    private static final String URL = "https://rough-firefly-dd7b.zulqarnaindiraj786.workers.dev/v1/android-config";
    private static final String ADS = "ads_enabled_v3";
    private static final long REFRESH_MS = TimeUnit.HOURS.toMillis(1);
    private static final AtomicBoolean FETCHING = new AtomicBoolean();
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .callTimeout(10, TimeUnit.SECONDS).followRedirects(false).build();

    private CloudAppConfig() {}

    public static void refresh(Context app) {
        SharedPreferences prefs = app.getSharedPreferences("cloud_app_config", Context.MODE_PRIVATE);
        if (prefs.contains(ADS)) AdsConfig.setRemoteAllowed(prefs.getBoolean(ADS, true));
        long age = System.currentTimeMillis() - prefs.getLong("fetched_at", 0);
        if ((age >= 0 && age < REFRESH_MS) || !FETCHING.compareAndSet(false, true)) return;
        HTTP.newCall(new Request.Builder().url(URL).build()).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException error) { FETCHING.set(false); }
            @Override public void onResponse(Call call, Response response) {
                try (Response result = response) {
                    if (!result.isSuccessful() || result.body() == null) return;
                    byte[] bytes = result.body().source().readByteArray(Math.min(1025, boundedLength(result)));
                    if (bytes.length > 1024 || !result.body().source().exhausted()) return;
                    Object value = new JSONObject(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).opt(ADS);
                    if (!(value instanceof Boolean)) return;
                    boolean allowed = (Boolean) value;
                    prefs.edit().putBoolean(ADS, allowed).putLong("fetched_at", System.currentTimeMillis()).apply();
                    new Handler(Looper.getMainLooper()).post(() -> AdsConfig.setRemoteAllowed(allowed));
                } catch (Exception ignored) {
                    // A failed fetch must not overwrite the last known switch.
                } finally { FETCHING.set(false); }
            }
        });
    }

    private static long boundedLength(Response result) throws IOException {
        okhttp3.ResponseBody body = result.body();
        long length = body.contentLength();
        if (length >= 0) return length;
        body.source().request(1025);
        return body.source().getBuffer().size();
    }
}
