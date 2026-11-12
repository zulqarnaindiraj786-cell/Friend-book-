package com.example.frienbook.core;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runtime switches served by Cloudflare (`GET /v1/config`) - Firebase Remote Config is not part of
 * this product any more.
 *
 * The Worker answers plain public booleans (no uid, no data): `adsEnabled` is the emergency ad kill
 * switch and `presence` says whether the realtime presence path is on. Flipping either is a Worker
 * variable change, so it reaches installed apps without an app update and without a Firebase
 * console. Values are cached in-process; a failed fetch keeps the last known value (defaults are
 * permissive for ads exactly as before, so an outage cannot silently switch revenue off).
 */
public final class CloudConfig {

    private CloudConfig() {}

    private static final long REFRESH_MS = 60_000L;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cf-config");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static long lastFetch;
    private static boolean adsEnabled = true;
    private static boolean presenceEnabled = true;

    public static boolean adsEnabled() { return adsEnabled; }
    public static boolean presenceEnabled() { return presenceEnabled; }

    /** Fetches at most once a minute; safe to call from every onResume. */
    public static void refresh() {
        long now = System.currentTimeMillis();
        if (now - lastFetch < REFRESH_MS) return;
        lastFetch = now;
        CloudTransport.withToken(IO, MAIN, token -> {
            try {
                JSONObject data = CloudTransport.send(new URL(CloudTransport.BASE + "/v1/config"), "GET", token, null);
                if (data == null) return;
                adsEnabled = data.optBoolean("adsEnabled", adsEnabled);
                presenceEnabled = data.optBoolean("presence", presenceEnabled);
                final boolean ads = adsEnabled;
                MAIN.post(() -> AdsConfig.setRemoteAllowed(ads));
            } catch (Throwable ignored) {
                // Keep the last known switches; the next refresh tries again.
            }
        }, null);
    }
}
