package com.friendbook.social.core;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.LoadAdError;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

/** Main-thread, screen-owned adaptive banners. A loaded view belongs to exactly one slot. */
public final class AnchoredBannerPool {
    public enum Placement {
        FEED("ca-app-pub-3387480177023669/8179691467"),
        REELS("ca-app-pub-3387480177023669/5275819818");
        final String unit;
        Placement(String unit) { this.unit = unit; }
    }
    private static final int PRELOAD = 3;
    private static final long RETRY_MS = 10_000L;
    private static final long LOAD_TIMEOUT_MS = 15_000L;
    private static final class Diagnostic {
        int requested, loaded, impressions, failed;
        String status = "Open this placement to start requests";
        String lastFailure = "";
    }
    private static final Diagnostic FEED_DIAGNOSTIC = new Diagnostic();
    private static final Diagnostic REELS_DIAGNOSTIC = new Diagnostic();

    public static String diagnostics(Activity activity) {
        return describe(Placement.FEED, FEED_DIAGNOSTIC) + "\n\n" + describe(Placement.REELS, REELS_DIAGNOSTIC)
                + "\n\nAds enabled: " + AdsConfig.nativeAdsEnabled()
                + " • Consent: " + ConsentManager.canRequestAds(activity)
                + " • SDK ready: " + ConsentManager.isAdsSdkInitialized();
    }

    private static String describe(Placement placement, Diagnostic info) {
        return placement.name() + " (" + placement.unit + ")\nRequests: " + info.requested
                + " • Loaded: " + info.loaded + " • Shown: " + info.impressions + " • Failed: " + info.failed
                + "\n" + info.status + (info.lastFailure.isEmpty() ? "" : "\nLast failure: " + info.lastFailure);
    }
    private static final class Slot {
        final String key;
        AdView ad;
        Slot(String key) { this.key = key; }
    }
    private final Activity activity;
    private final Placement placement;
    private final Diagnostic diagnostic;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<AdView> ready = new ArrayDeque<>();
    private final Map<AdView, Runnable> loading = new LinkedHashMap<>();
    private final Map<FrameLayout, Slot> slots = new LinkedHashMap<>();
    private final Runnable retry = this::pump;
    private boolean active;
    private long retryAfter;

    public AnchoredBannerPool(Activity activity, Placement placement) {
        this.activity = activity;
        this.placement = placement;
        this.diagnostic = placement == Placement.FEED ? FEED_DIAGNOSTIC : REELS_DIAGNOSTIC;
    }

    public void setActive(boolean active) {
        if (this.active == active) { if (active) pump(); return; }
        this.active = active;
        main.removeCallbacks(retry);
        if (active) {
            if (!ConsentManager.isAdsSdkInitialized()) {
                java.lang.ref.WeakReference<AnchoredBannerPool> ref = new java.lang.ref.WeakReference<>(this);
                ConsentManager.runWhenSdkInitialized(activity, () -> {
                    AnchoredBannerPool pool = ref.get();
                    if (pool != null) pool.pump();
                });
            }
            pump();
            return;
        }
        while (!ready.isEmpty()) ready.removeFirst().destroy();
        cancelPending();
        for (FrameLayout container : new java.util.ArrayList<>(slots.keySet())) clear(container);
    }

    private void cancelPending() {
        Map<AdView,Runnable> pending = new LinkedHashMap<>(loading);
        loading.clear();
        for (Map.Entry<AdView,Runnable> entry : pending.entrySet()) {
            main.removeCallbacks(entry.getValue());
            entry.getKey().destroy();
        }
    }

    public void show(FrameLayout target, String key) {
        if (!active || key == null) { clear(target); return; }
        Slot slot = slots.get(target);
        if (slot == null || !slot.key.equals(key)) {
            clear(target);
            slots.put(target, new Slot(key));
        }
        pump();
    }

    public void clear(FrameLayout target) {
        Slot old = slots.remove(target);
        target.removeAllViews();
        target.setVisibility(View.GONE);
        if (old != null && old.ad != null) old.ad.destroy();
    }

    private void schedule(long delay) {
        main.removeCallbacks(retry);
        main.postDelayed(retry, delay);
    }

    private void pump() {
        if (!active || activity.isFinishing() || activity.isDestroyed()) return;
        if (!AdsConfig.nativeAdsEnabled() || !ConsentManager.canRequestAds(activity)
                || !ConsentManager.isAdsSdkInitialized()) {
            // Hide even an already attached banner when consent or the master switch is off.
            for (Map.Entry<FrameLayout, Slot> entry : slots.entrySet()) {
                entry.getKey().setVisibility(View.GONE);
                entry.getKey().removeAllViews();
                if (entry.getValue().ad != null) entry.getValue().ad.destroy();
                entry.getValue().ad = null;
            }
            while (!ready.isEmpty()) ready.removeFirst().destroy();
            cancelPending();
            diagnostic.status = !AdsConfig.nativeAdsEnabled() ? "Disabled by ad settings"
                    : !ConsentManager.canRequestAds(activity) ? "Waiting for Google consent"
                    : "Waiting for Google Mobile Ads SDK";
            schedule(RETRY_MS);
            return;
        }
        for (Map.Entry<FrameLayout, Slot> entry : slots.entrySet()) {
            if (ready.isEmpty()) break;
            if (entry.getValue().ad != null) continue;
            AdView ad = ready.removeFirst();
            entry.getValue().ad = ad;
            FrameLayout target = entry.getKey();
            target.addView(ad, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
            target.setVisibility(View.VISIBLE);
        }
        long remaining = retryAfter - android.os.SystemClock.elapsedRealtime();
        if (remaining > 0) { schedule(remaining); return; }
        View content = activity.findViewById(android.R.id.content);
        int width = content == null ? 0 : content.getWidth() - content.getPaddingLeft() - content.getPaddingRight();
        if (width <= 0) { schedule(250L); return; }
        int widthDp = Math.max(1, (int) (width / activity.getResources().getDisplayMetrics().density));
        int missing = PRELOAD - ready.size() - loading.size();
        for (int i = 0; i < missing; i++) load(widthDp);
    }

    private boolean finishPending(AdView ad) {
        Runnable timeout = loading.remove(ad);
        if (timeout == null) return false;
        main.removeCallbacks(timeout);
        return true;
    }

    private void failPending(AdView ad, String reason) {
        if (!finishPending(ad)) return;
        ad.destroy();
        diagnostic.failed++;
        diagnostic.lastFailure = reason;
        diagnostic.status = "Retry scheduled";
        android.util.Log.w("FriendBookAds", placement + " banner: " + reason);
        retryAfter = android.os.SystemClock.elapsedRealtime() + RETRY_MS;
        pump();
    }

    private void load(int widthDp) {
        AdView ad = new AdView(activity);
        ad.setAdUnitId(placement.unit);
        ad.setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, widthDp));
        Runnable timeout = () -> failPending(ad, "No SDK callback within 15 seconds");
        loading.put(ad, timeout);
        diagnostic.requested++;
        diagnostic.status = "Requesting banner from AdMob";
        ad.setAdListener(new AdListener() {
            @Override public void onAdLoaded() {
                if (!finishPending(ad)) return;
                diagnostic.loaded++;
                diagnostic.status = "Banner received";
                ready.addLast(ad);
                main.post(AnchoredBannerPool.this::pump);
            }
            @Override public void onAdFailedToLoad(LoadAdError error) {
                failPending(ad, "Code " + error.getCode() + " (" + error.getDomain() + "): " + error.getMessage());
            }
            @Override public void onAdImpression() {
                diagnostic.impressions++;
                diagnostic.status = "Banner shown";
            }
        });
        main.postDelayed(timeout, LOAD_TIMEOUT_MS);
        try {
            ad.loadAd(new AdRequest.Builder().build());
        } catch (RuntimeException error) {
            failPending(ad, "SDK could not start the request: " + error.getClass().getSimpleName());
        }
    }
}
