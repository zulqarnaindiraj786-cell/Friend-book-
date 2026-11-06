package com.friendbook.social.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.android.gms.ads.AdValue;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.SetOptions;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 📢 friendbook Production AdMob Configuration (`com.friendbook.social`):
 * - Uses ONLY real production AdMob IDs (zero test IDs, zero test devices, zero debug ad mode):
 *   • App ID:       ca-app-pub-3387480177023669~1513026594
 *   • Feed Native:  ca-app-pub-3387480177023669/4957447582
 *   • Reels Native: ca-app-pub-3387480177023669/6468085788
 * - Loads Native Ads fresh via AdLoader.Builder on every Feed & Reels slot bind (never reuses old/failed ads).
 */
public class AdsConfig {

    public static final String LOG_TAG = "FriendBookAds";

    /** Official friendbook AdMob Publisher ID (matches root /app-ads.txt). */
    public static final String PUBLISHER_ID = "pub-3387480177023669";
    public static final String PUBLISHER_PREFIX = "ca-app-pub-3387480177023669/";

    /** Official Production AdMob App ID (matches AndroidManifest.xml APPLICATION_ID). */
    public static final String APP_ID = "ca-app-pub-3387480177023669~1513026594";

    /** Official Production Feed Native Ad Unit ID. */
    public static final String FEED_NATIVE_ID = "ca-app-pub-3387480177023669/4957447582";
    public static final String DEFAULT_NATIVE_ID = FEED_NATIVE_ID;
    public static final String NATIVE_UNIT_NAME = "Feed Native";

    /** Official Production Reels Native Ad Unit ID. */
    public static final String REELS_NATIVE_ID = "ca-app-pub-3387480177023669/6468085788";
    public static final String REELS_UNIT_NAME = "Reels Native";

    public static final String DEFAULT_INTERSTITIAL_ID = "";

    /** Official Google Docs Policy URL (Privacy Policy, Terms of Service & Zero-Tolerance CSAE Policy). */
    public static final String GOOGLE_DOCS_POLICY_URL =
            "https://docs.google.com/document/d/1k3JAnk4U2S_cC8aLl2nNVpdglw-UxzSzBamWV-UfEkw/view";

    /** Official Developer Website (for app-ads.txt verification) & Google Docs Policy URLs. */
    public static final String OFFICIAL_WEBSITE_URL = "https://zulqarnaindiraj786-cell.github.io/Friend-book-";
    public static final String APP_ADS_TXT_URL = OFFICIAL_WEBSITE_URL + "/app-ads.txt";
    public static final String PRIVACY_POLICY_URL = GOOGLE_DOCS_POLICY_URL;
    public static final String TERMS_OF_SERVICE_URL = GOOGLE_DOCS_POLICY_URL;
    public static final String CHILD_SAFETY_URL = GOOGLE_DOCS_POLICY_URL;
    public static final String DELETE_ACCOUNT_URL = GOOGLE_DOCS_POLICY_URL;
    public static final String PLAY_STORE_URL = "https://play.google.com/store/apps/details?id=com.friendbook.social";
    public static final String APP_ADS_TXT_RECORD =
            "google.com, pub-3387480177023669, DIRECT, f08c47fec0942fa0";

    private static final String PREF = "fb_admob_stats_v3";
    private static final String KEY_REQUESTS = "ad_requests";
    private static final String KEY_LOADS = "ad_loads";
    private static final String KEY_IMPRESSIONS = "ad_impressions";
    private static final String KEY_CLICKS = "ad_clicks";
    private static final String KEY_FAILS = "ad_fails";
    private static final String KEY_LAST_ERROR = "last_error_code";
    private static final String KEY_MICROS = "paid_micros_total";

    private static volatile String feedNativeUnitId = FEED_NATIVE_ID;
    private static volatile String reelsNativeUnitId = REELS_NATIVE_ID;
    private static volatile boolean masterSwitchEnabled = true;
    private static volatile boolean remoteAllowed = true;
    private static ListenerRegistration reg;

    public interface ChangeListener {
        void onAdsConfigChanged(String nativeUnitId, boolean enabled);
    }

    private static ChangeListener listener;

    public static void setChangeListener(ChangeListener l) {
        listener = l;
    }

    public static void notifyChange() {
        if (listener != null) {
            listener.onAdsConfigChanged(feedNativeId(), nativeAdsEnabled());
        }
    }

    /** Validates that a unit ID belongs to our real production AdMob publisher (`pub-3387480177023669`). */
    public static boolean isRealProductionUnit(String unitId) {
        if (unitId == null) return false;
        String trimmed = unitId.trim();
        if (!trimmed.startsWith(PUBLISHER_PREFIX)) return false;
        String suffix = trimmed.substring(PUBLISHER_PREFIX.length());
        if (suffix.length() < 6) return false;
        for (int i = 0; i < suffix.length(); i++) {
            if (!Character.isDigit(suffix.charAt(i))) return false;
        }
        return true;
    }

    /** Active Feed Native Ad Unit ID (`ca-app-pub-3387480177023669/4957447582`). */
    public static String feedNativeId() {
        String id = feedNativeUnitId;
        return isRealProductionUnit(id) ? id : FEED_NATIVE_ID;
    }

    /** Active Reels Native Ad Unit ID (`ca-app-pub-3387480177023669/6468085788`). */
    public static String reelsNativeId() {
        String id = reelsNativeUnitId;
        return isRealProductionUnit(id) ? id : REELS_NATIVE_ID;
    }

    /** Alias for Feed Native Ad Unit ID for existing callers. */
    public static String nativeId() {
        return feedNativeId();
    }

    public static String interstitialId() {
        return DEFAULT_INTERSTITIAL_ID;
    }

    public static boolean isMasterSwitchEnabled() {
        return masterSwitchEnabled;
    }

    /** Returns true when production Native Ads are enabled. */
    public static boolean nativeAdsEnabled() {
        return masterSwitchEnabled && remoteAllowed;
    }

    public static void setRemoteAllowed(boolean allowed) {
        remoteAllowed = allowed;
        notifyChange();
    }

    public static void recordAdRequest(Context c) {
        if (c == null) return;
        SharedPreferences sp = prefs(c);
        sp.edit().putLong(KEY_REQUESTS, sp.getLong(KEY_REQUESTS, 0L) + 1L).apply();
    }

    public static void onNativeAdLoaded(Context c) {
        if (c == null) return;
        SharedPreferences sp = prefs(c);
        sp.edit()
                .putLong(KEY_LOADS, sp.getLong(KEY_LOADS, 0L) + 1L)
                .putInt(KEY_LAST_ERROR, -1)
                .apply();
    }

    public static void onNativeAdFailed(Context c, int errorCode) {
        if (c == null) return;
        SharedPreferences sp = prefs(c);
        sp.edit()
                .putLong(KEY_FAILS, sp.getLong(KEY_FAILS, 0L) + 1L)
                .putInt(KEY_LAST_ERROR, errorCode)
                .apply();
    }

    public static void recordAdImpression(Context c) {
        if (c == null) return;
        SharedPreferences sp = prefs(c);
        sp.edit().putLong(KEY_IMPRESSIONS, sp.getLong(KEY_IMPRESSIONS, 0L) + 1L).apply();
    }

    public static void recordAdClick(Context c) {
        if (c == null) return;
        SharedPreferences sp = prefs(c);
        sp.edit().putLong(KEY_CLICKS, sp.getLong(KEY_CLICKS, 0L) + 1L).apply();
    }

    public static void recordPaidEvent(Context c, AdValue adValue) {
        if (c == null || adValue == null) return;
        SharedPreferences sp = prefs(c);
        long total = sp.getLong(KEY_MICROS, 0L) + adValue.getValueMicros();
        sp.edit().putLong(KEY_MICROS, total).apply();
    }

    public static String statsSummary(Context c) {
        SharedPreferences sp = prefs(c);
        long req = sp.getLong(KEY_REQUESTS, 0L);
        long loads = sp.getLong(KEY_LOADS, 0L);
        long imps = sp.getLong(KEY_IMPRESSIONS, 0L);
        long clicks = sp.getLong(KEY_CLICKS, 0L);
        long fails = sp.getLong(KEY_FAILS, 0L);
        int lastErr = sp.getInt(KEY_LAST_ERROR, -1);
        double usd = sp.getLong(KEY_MICROS, 0L) / 1_000_000.0;
        String errLabel = lastErr < 0 ? "None" : ("Code " + lastErr);
        return String.format(Locale.US,
                "Mode: Real Production Only  •  Requests: %d  •  Loaded: %d\n"
                        + "Impressions: %d  •  Clicks: %d  •  Failed: %d (Last error: %s)  •  Est: $%.4f",
                req, loads, imps, clicks, fails, errLabel, usd);
    }

    /** Reads bundled assets/app-ads.txt for instant verification inside the app. */
    public static String readBundledAppAdsTxt(Context c) {
        try (InputStream is = c.getAssets().open("app-ads.txt");
             BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line = br.readLine();
            if (line != null && !line.trim().isEmpty()) return line.trim();
        } catch (Throwable ignored) {}
        return APP_ADS_TXT_RECORD;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** Watches Firestore `config/ads` v3 fields (ignores retired legacy IDs so new real IDs stay active). */
    public static void startWatching() {
        if (reg != null) return;
        try {
            reg = Fb.db().collection("config").document("ads")
                    .addSnapshotListener((snap, err) -> {
                        if (snap != null && snap.exists()) {
                            String fId = snap.getString("feed_native_unit_v3");
                            String rId = snap.getString("reels_native_unit_v3");
                            Boolean en = snap.getBoolean("ads_enabled_v3");
                            feedNativeUnitId = isRealProductionUnit(fId) ? fId.trim() : FEED_NATIVE_ID;
                            reelsNativeUnitId = isRealProductionUnit(rId) ? rId.trim() : REELS_NATIVE_ID;
                            if (en != null) masterSwitchEnabled = en;
                            notifyChange();
                        }
                    });
        } catch (Throwable t) {
            Log.w(LOG_TAG, "AdsConfig Firestore watch skipped: " + t.getMessage());
        }
    }

    /** Saves real Feed & Reels Native Ad Unit IDs to Firestore `config/ads` (Admin Panel). */
    public static void save(String newFeedNativeId, String newReelsNativeId, boolean isEnabled, Context ctx) {
        String cleanFeed = isRealProductionUnit(newFeedNativeId) ? newFeedNativeId.trim() : FEED_NATIVE_ID;
        String cleanReels = isRealProductionUnit(newReelsNativeId) ? newReelsNativeId.trim() : REELS_NATIVE_ID;
        Map<String, Object> m = new HashMap<>();
        m.put("appId", APP_ID);
        m.put("feed_native_unit_v3", cleanFeed);
        m.put("reels_native_unit_v3", cleanReels);
        m.put("ads_enabled_v3", isEnabled);
        m.put("websiteUrl", OFFICIAL_WEBSITE_URL);
        m.put("appAdsTxtUrl", APP_ADS_TXT_URL);
        m.put("updatedBy", Fb.uid());
        m.put("updatedAt", FieldValue.serverTimestamp());
        Fb.db().collection("config").document("ads")
                .set(m, SetOptions.merge())
                .addOnSuccessListener(v -> Ui.toast(ctx, "Production AdMob config saved ✓"))
                .addOnFailureListener(e -> Ui.toast(ctx, "Save failed: " + e.getMessage()));
    }

    public static void reset(Context ctx) {
        save(FEED_NATIVE_ID, REELS_NATIVE_ID, true, ctx);
    }

    // ------------------------------------------------------------------------
    // ⚡ Zero-Wait Preloaded NativeAd Pool for Direct Feed & Reels Display
    // ------------------------------------------------------------------------
    private static final int POOL_TARGET_SIZE = 3;
    private static final java.util.Deque<com.google.android.gms.ads.nativead.NativeAd> FEED_POOL =
            new java.util.ArrayDeque<>();
    private static final java.util.Deque<com.google.android.gms.ads.nativead.NativeAd> REELS_POOL =
            new java.util.ArrayDeque<>();
    private static final Map<Integer, com.google.android.gms.ads.nativead.NativeAd> FEED_SLOT_ADS =
            new HashMap<>();
    private static final Map<Integer, com.google.android.gms.ads.nativead.NativeAd> REELS_SLOT_ADS =
            new HashMap<>();
    private static int feedInFlight = 0;
    private static int reelsInFlight = 0;

    /** Preloads both Feed and Reels NativeAds in the background so slots render in 0ms without loading screens. */
    public static synchronized void warmUpPools(final Context context) {
        if (context == null || !nativeAdsEnabled()) return;
        final Context appCtx = context.getApplicationContext();
        ConsentManager.ensureInitialized(appCtx);
        while (FEED_POOL.size() + feedInFlight < POOL_TARGET_SIZE) {
            feedInFlight++;
            preloadOneFeedAd(appCtx);
        }
        while (REELS_POOL.size() + reelsInFlight < POOL_TARGET_SIZE) {
            reelsInFlight++;
            preloadOneReelsAd(appCtx);
        }
    }

    public static synchronized com.google.android.gms.ads.nativead.NativeAd getOrPollFeedAd(Context context, int slotOrdinal) {
        com.google.android.gms.ads.nativead.NativeAd existing = FEED_SLOT_ADS.get(slotOrdinal);
        if (existing != null) return existing;
        com.google.android.gms.ads.nativead.NativeAd polled = FEED_POOL.pollFirst();
        if (polled != null) {
            FEED_SLOT_ADS.put(slotOrdinal, polled);
        }
        warmUpPools(context);
        return polled;
    }

    public static synchronized void putFeedSlotAd(int slotOrdinal, com.google.android.gms.ads.nativead.NativeAd ad) {
        if (ad != null) FEED_SLOT_ADS.put(slotOrdinal, ad);
    }

    public static synchronized com.google.android.gms.ads.nativead.NativeAd getOrPollReelsAd(Context context, int slotOrdinal) {
        com.google.android.gms.ads.nativead.NativeAd existing = REELS_SLOT_ADS.get(slotOrdinal);
        if (existing != null) return existing;
        com.google.android.gms.ads.nativead.NativeAd polled = REELS_POOL.pollFirst();
        if (polled != null) {
            REELS_SLOT_ADS.put(slotOrdinal, polled);
        }
        warmUpPools(context);
        return polled;
    }

    public static synchronized boolean hasReadyReelsSlotOrPoolAd(int slotOrdinal) {
        return REELS_SLOT_ADS.containsKey(slotOrdinal) || !REELS_POOL.isEmpty();
    }

    public static synchronized void putReelsSlotAd(int slotOrdinal, com.google.android.gms.ads.nativead.NativeAd ad) {
        if (ad != null) REELS_SLOT_ADS.put(slotOrdinal, ad);
    }

    public static synchronized void clearFeedSlotAds(Context context) {
        FEED_SLOT_ADS.clear();
        warmUpPools(context);
    }

    public static synchronized void clearReelsSlotAds(Context context) {
        REELS_SLOT_ADS.clear();
        warmUpPools(context);
    }

    private static void preloadOneFeedAd(final Context appCtx) {
        final String unitId = feedNativeId();
        try {
            recordAdRequest(appCtx);
            com.google.android.gms.ads.VideoOptions videoOptions =
                    new com.google.android.gms.ads.VideoOptions.Builder().setStartMuted(true).build();
            com.google.android.gms.ads.nativead.NativeAdOptions adOptions =
                    new com.google.android.gms.ads.nativead.NativeAdOptions.Builder()
                            .setAdChoicesPlacement(com.google.android.gms.ads.nativead.NativeAdOptions.ADCHOICES_TOP_RIGHT)
                            .setVideoOptions(videoOptions)
                            .build();
            com.google.android.gms.ads.AdLoader adLoader =
                    new com.google.android.gms.ads.AdLoader.Builder(appCtx, unitId)
                            .forNativeAd(nativeAd -> {
                                synchronized (AdsConfig.class) {
                                    feedInFlight = Math.max(0, feedInFlight - 1);
                                    onNativeAdLoaded(appCtx);
                                    nativeAd.setOnPaidEventListener(adValue -> recordPaidEvent(appCtx, adValue));
                                    FEED_POOL.offerLast(nativeAd);
                                }
                            })
                            .withAdListener(new com.google.android.gms.ads.AdListener() {
                                @Override
                                public void onAdImpression() { recordAdImpression(appCtx); }
                                @Override
                                public void onAdClicked() { recordAdClick(appCtx); }
                                @Override
                                public void onAdFailedToLoad(com.google.android.gms.ads.LoadAdError adError) {
                                    synchronized (AdsConfig.class) {
                                        feedInFlight = Math.max(0, feedInFlight - 1);
                                    }
                                    onNativeAdFailed(appCtx, adError != null ? adError.getCode() : -1);
                                }
                            })
                            .withNativeAdOptions(adOptions)
                            .build();
            adLoader.loadAd(new com.google.android.gms.ads.AdRequest.Builder().build());
        } catch (Throwable t) {
            synchronized (AdsConfig.class) {
                feedInFlight = Math.max(0, feedInFlight - 1);
            }
        }
    }

    private static void preloadOneReelsAd(final Context appCtx) {
        final String unitId = reelsNativeId();
        try {
            recordAdRequest(appCtx);
            boolean startMuted = !Prefs.reelsStartWithSound(appCtx);
            com.google.android.gms.ads.VideoOptions videoOptions =
                    new com.google.android.gms.ads.VideoOptions.Builder().setStartMuted(startMuted).build();
            com.google.android.gms.ads.nativead.NativeAdOptions adOptions =
                    new com.google.android.gms.ads.nativead.NativeAdOptions.Builder()
                            .setAdChoicesPlacement(com.google.android.gms.ads.nativead.NativeAdOptions.ADCHOICES_TOP_RIGHT)
                            .setMediaAspectRatio(com.google.android.gms.ads.nativead.NativeAdOptions.NATIVE_MEDIA_ASPECT_RATIO_PORTRAIT)
                            .setVideoOptions(videoOptions)
                            .build();
            com.google.android.gms.ads.AdLoader adLoader =
                    new com.google.android.gms.ads.AdLoader.Builder(appCtx, unitId)
                            .forNativeAd(nativeAd -> {
                                synchronized (AdsConfig.class) {
                                    reelsInFlight = Math.max(0, reelsInFlight - 1);
                                    onNativeAdLoaded(appCtx);
                                    nativeAd.setOnPaidEventListener(adValue -> recordPaidEvent(appCtx, adValue));
                                    REELS_POOL.offerLast(nativeAd);
                                }
                            })
                            .withAdListener(new com.google.android.gms.ads.AdListener() {
                                @Override
                                public void onAdImpression() { recordAdImpression(appCtx); }
                                @Override
                                public void onAdClicked() { recordAdClick(appCtx); }
                                @Override
                                public void onAdFailedToLoad(com.google.android.gms.ads.LoadAdError adError) {
                                    synchronized (AdsConfig.class) {
                                        reelsInFlight = Math.max(0, reelsInFlight - 1);
                                    }
                                    onNativeAdFailed(appCtx, adError != null ? adError.getCode() : -1);
                                }
                            })
                            .withNativeAdOptions(adOptions)
                            .build();
            adLoader.loadAd(new com.google.android.gms.ads.AdRequest.Builder().build());
        } catch (Throwable t) {
            synchronized (AdsConfig.class) {
                reelsInFlight = Math.max(0, reelsInFlight - 1);
            }
        }
    }
}
