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
    private static final class Slot {
        final String key;
        AdView ad;
        Slot(String key) { this.key = key; }
    }
    private final Activity activity;
    private final Placement placement;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<AdView> ready = new ArrayDeque<>();
    private final java.util.Set<AdView> loading = new java.util.HashSet<>();
    private final Map<FrameLayout, Slot> slots = new LinkedHashMap<>();
    private final Runnable retry = this::pump;
    private boolean active;
    private long retryAfter;

    public AnchoredBannerPool(Activity activity, Placement placement) {
        this.activity = activity;
        this.placement = placement;
    }

    public void setActive(boolean active) {
        if (this.active == active) { if (active) pump(); return; }
        this.active = active;
        main.removeCallbacks(retry);
        if (active) { retryAfter = 0; pump(); return; }
        while (!ready.isEmpty()) ready.removeFirst().destroy();
        AdView[] pending = loading.toArray(new AdView[0]);
        loading.clear();
        for (AdView ad : pending) ad.destroy();
        for (FrameLayout container : new java.util.ArrayList<>(slots.keySet())) clear(container);
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

    private void load(int widthDp) {
        AdView ad = new AdView(activity);
        ad.setAdUnitId(placement.unit);
        ad.setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, widthDp));
        loading.add(ad);
        ad.setAdListener(new AdListener() {
            @Override public void onAdLoaded() {
                if (!loading.remove(ad)) return;
                ready.addLast(ad);
                main.post(AnchoredBannerPool.this::pump);
            }
            @Override public void onAdFailedToLoad(LoadAdError error) {
                if (!loading.remove(ad)) return;
                ad.destroy();
                retryAfter = android.os.SystemClock.elapsedRealtime() + RETRY_MS;
                pump();
            }
        });
        ad.loadAd(new AdRequest.Builder().build());
    }
}
