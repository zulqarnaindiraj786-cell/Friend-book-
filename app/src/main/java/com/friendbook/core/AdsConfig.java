package com.friendbook.core;

import android.app.Activity;
import android.content.Context;

import androidx.annotation.NonNull;

import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.HashMap;
import java.util.Map;

/**
 * 📢 Google AdMob Production Native Ads Configuration:
 * - Zero Banner Ads anywhere in the app.
 * - Serves real AdMob Native Ads in-feed after every 5 posts.
 * - Strictly blocks Google sample/test ad unit prefixes.
 */
public class AdsConfig {

    private static final String GOOGLE_TEST_PREFIX = "ca-app-pub-3940256099942544";

    public static final String APP_ID = "ca-app-pub-3387480177023669~4887770836";
    public static final String DEFAULT_NATIVE_ID = "";
    public static final String DEFAULT_INTERSTITIAL_ID = "";

    private static String nativeId = DEFAULT_NATIVE_ID;
    private static String interstitialId = DEFAULT_INTERSTITIAL_ID;
    private static boolean enabled = true;
    private static boolean remoteAllowed = true;
    private static ListenerRegistration reg;
    private static InterstitialAd cachedInterstitial;
    private static boolean loadingInterstitial = false;
    private static long lastInterstitialShownAt = 0L;

    /** Ensures only real production AdMob unit IDs (never Google sample/test IDs) are accepted. */
    public static boolean isRealProductionUnit(String id) {
        if (id == null) return false;
        String clean = id.trim();
        if (clean.isEmpty() || clean.startsWith(GOOGLE_TEST_PREFIX)) return false;
        return clean.startsWith("ca-app-pub-") && clean.contains("/") && clean.length() > 25;
    }

    public static String nativeId() {
        if (isRealProductionUnit(nativeId)) return nativeId.trim();
        return isRealProductionUnit(DEFAULT_NATIVE_ID) ? DEFAULT_NATIVE_ID : "";
    }

    public static String interstitialId() {
        return isRealProductionUnit(interstitialId) ? interstitialId.trim() : "";
    }

    public static boolean nativeAdsEnabled() {
        return enabled && remoteAllowed && !nativeId().isEmpty();
    }

    public static boolean isMasterSwitchEnabled() {
        return enabled && remoteAllowed;
    }

    public interface ChangeListener { void onChange(String nativeAdUnitId, boolean enabled); }
    private static ChangeListener changeListener;
    public static void setChangeListener(ChangeListener l) { changeListener = l; }

    /** Live-listen Firestore config/ads so admin edits apply without an app update. */
    public static void startWatching() {
        if (reg != null) return;
        reg = Fb.db().collection("config").document("ads")
                .addSnapshotListener((d, err) -> {
                    if (d != null && d.exists()) {
                        String nId = d.getString("nativeId");
                        String iId = d.getString("interstitialId");
                        Boolean en = d.getBoolean("enabled");
                        nativeId = isRealProductionUnit(nId) ? nId.trim() : DEFAULT_NATIVE_ID;
                        interstitialId = isRealProductionUnit(iId) ? iId.trim() : DEFAULT_INTERSTITIAL_ID;
                        if (en != null) enabled = en;
                        notifyChange();
                    }
                });
    }

    /** Remote Config kill switch (key ads_enabled, console-managed). */
    public static void setRemoteAllowed(boolean allowed) {
        remoteAllowed = allowed;
        notifyChange();
    }

    public static void notifyChange() {
        if (changeListener != null) changeListener.onChange(nativeId(), nativeAdsEnabled());
    }

    /** Preload optional real production InterstitialAd if configured. */
    public static void preloadInterstitial(Context ctx) {
        String unit = interstitialId();
        if (ctx == null || unit.isEmpty() || !isMasterSwitchEnabled() || cachedInterstitial != null || loadingInterstitial) {
            return;
        }
        loadingInterstitial = true;
        AdRequest request = new AdRequest.Builder().build();
        InterstitialAd.load(ctx.getApplicationContext(), unit, request, new InterstitialAdLoadCallback() {
            @Override
            public void onAdLoaded(@NonNull InterstitialAd ad) {
                loadingInterstitial = false;
                cachedInterstitial = ad;
            }

            @Override
            public void onAdFailedToLoad(@NonNull LoadAdError err) {
                loadingInterstitial = false;
                cachedInterstitial = null;
            }
        });
    }

    /** Show preloaded real InterstitialAd at natural screen transitions (max once per 3 minutes). */
    public static void showInterstitialIfReady(Activity activity) {
        if (activity == null || activity.isFinishing() || !isMasterSwitchEnabled()) return;
        long now = System.currentTimeMillis();
        if (cachedInterstitial != null && (now - lastInterstitialShownAt) > 180_000L) {
            lastInterstitialShownAt = now;
            InterstitialAd ad = cachedInterstitial;
            cachedInterstitial = null;
            ad.show(activity);
            preloadInterstitial(activity);
        } else if (cachedInterstitial == null) {
            preloadInterstitial(activity);
        }
    }

    public static void save(String newNativeId, String newInterstitialId, boolean on, Context c) {
        String cleanNative = isRealProductionUnit(newNativeId) ? newNativeId.trim() : DEFAULT_NATIVE_ID;
        String cleanInter = isRealProductionUnit(newInterstitialId) ? newInterstitialId.trim() : "";
        Map<String, Object> m = new HashMap<>();
        m.put("nativeId", cleanNative);
        m.put("interstitialId", cleanInter);
        m.put("enabled", on);
        m.put("updatedAt", com.google.firebase.firestore.FieldValue.serverTimestamp());
        Fb.db().collection("config").document("ads")
                .set(m, com.google.firebase.firestore.SetOptions.merge())
                .addOnSuccessListener(x -> Ui.toast(c, "Real AdMob Native Ad settings saved ✓"))
                .addOnFailureListener(e -> Ui.toast(c, "Save failed: " + e.getMessage()));
    }

    public static void reset(Context c) {
        Fb.db().collection("config").document("ads").delete()
                .addOnSuccessListener(x -> {
                    nativeId = DEFAULT_NATIVE_ID;
                    interstitialId = DEFAULT_INTERSTITIAL_ID;
                    enabled = true;
                    Ui.toast(c, "Reset to built-in Native Ad config ✓");
                    notifyChange();
                });
    }
}
