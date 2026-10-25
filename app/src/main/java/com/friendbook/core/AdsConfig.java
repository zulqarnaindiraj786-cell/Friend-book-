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
 * 📢 Google AdMob Production Configuration:
 * Uses ONLY real production AdMob IDs for com.friendbook.
 * - Real App ID:        ca-app-pub-3387480177023669~4887770836
 * - Real Banner Unit:   ca-app-pub-3387480177023669/6266308174
 * - Optional Real Interstitial Unit (configurable live via Admin AdMob Settings; Banner-only by default).
 */
public class AdsConfig {

    public static final String PUBLISHER_PREFIX = "ca-app-pub-3387480177023669/";
    public static final String APP_ID = "ca-app-pub-3387480177023669~4887770836";
    public static final String DEFAULT_BANNER_ID = "ca-app-pub-3387480177023669/6266308174";
    public static final String DEFAULT_INTERSTITIAL_ID = ""; // Banner-only unless admin sets a real production unit

    private static final String LEGACY_OBSOLETE_UNIT = "ca-app-pub-3387480177023669/2691090902";

    private static String bannerId = DEFAULT_BANNER_ID;
    private static String interstitialId = DEFAULT_INTERSTITIAL_ID;
    private static boolean enabled = true;
    private static boolean remoteAllowed = true;
    private static ListenerRegistration reg;
    private static InterstitialAd cachedInterstitial;
    private static boolean loadingInterstitial = false;
    private static long lastInterstitialShownAt = 0L;

    /** Ensures only real production AdMob unit IDs for this publisher are accepted. */
    public static boolean isRealProductionUnit(String id) {
        if (id == null) return false;
        String clean = id.trim();
        if (clean.isEmpty() || LEGACY_OBSOLETE_UNIT.equals(clean)) return false;
        return clean.startsWith(PUBLISHER_PREFIX) && clean.length() > PUBLISHER_PREFIX.length() + 5;
    }

    public static String bannerId() {
        return isRealProductionUnit(bannerId) ? bannerId : DEFAULT_BANNER_ID;
    }

    public static String interstitialId() {
        return isRealProductionUnit(interstitialId) ? interstitialId : "";
    }

    public static boolean bannersEnabled() {
        return enabled && remoteAllowed;
    }

    public interface ChangeListener { void onChange(String bannerId, boolean enabled); }
    private static ChangeListener changeListener;
    public static void setChangeListener(ChangeListener l) { changeListener = l; }

    /** Live-listen Firestore config/ads so admin edits apply without an app update. */
    public static void startWatching() {
        if (reg != null) return;
        reg = Fb.db().collection("config").document("ads")
                .addSnapshotListener((d, err) -> {
                    if (d != null && d.exists()) {
                        String bId = d.getString("bannerId");
                        String iId = d.getString("interstitialId");
                        Boolean en = d.getBoolean("enabled");
                        bannerId = isRealProductionUnit(bId) ? bId.trim() : DEFAULT_BANNER_ID;
                        interstitialId = isRealProductionUnit(iId) ? iId.trim() : "";
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
        if (changeListener != null) changeListener.onChange(bannerId(), bannersEnabled());
    }

    /** Preload optional real production InterstitialAd if admin configured a real unit ID. */
    public static void preloadInterstitial(Context ctx) {
        String unit = interstitialId();
        if (ctx == null || unit.isEmpty() || !bannersEnabled() || cachedInterstitial != null || loadingInterstitial) {
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
        if (activity == null || activity.isFinishing() || !bannersEnabled()) return;
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

    /** Admin saves Banner ID (+ optional Interstitial ID) & toggle. */
    public static void save(String newBannerId, boolean on, Context c) {
        save(newBannerId, interstitialId, on, c);
    }

    public static void save(String newBannerId, String newInterstitialId, boolean on, Context c) {
        String cleanBanner = isRealProductionUnit(newBannerId) ? newBannerId.trim() : DEFAULT_BANNER_ID;
        String cleanInter = isRealProductionUnit(newInterstitialId) ? newInterstitialId.trim() : "";
        Map<String, Object> m = new HashMap<>();
        m.put("bannerId", cleanBanner);
        m.put("interstitialId", cleanInter);
        m.put("enabled", on);
        m.put("updatedAt", com.google.firebase.firestore.FieldValue.serverTimestamp());
        Fb.db().collection("config").document("ads")
                .set(m, com.google.firebase.firestore.SetOptions.merge())
                .addOnSuccessListener(x -> Ui.toast(c, "Real AdMob production settings saved ✓"))
                .addOnFailureListener(e -> Ui.toast(c, "Save failed: " + e.getMessage()));
    }

    /** Reset override → use built-in live production Banner ID. */
    public static void reset(Context c) {
        Fb.db().collection("config").document("ads").delete()
                .addOnSuccessListener(x -> {
                    bannerId = DEFAULT_BANNER_ID;
                    interstitialId = DEFAULT_INTERSTITIAL_ID;
                    enabled = true;
                    Ui.toast(c, "Reset to production Banner ID ✓");
                    notifyChange();
                });
    }
}
