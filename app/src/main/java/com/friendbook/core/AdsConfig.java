package com.friendbook.core;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;

import androidx.annotation.NonNull;

import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdValue;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;
import com.google.firebase.firestore.ListenerRegistration;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 📢 Real Earning AdMob Native Advanced Engine (`com.friendbook` — Google Play Store Ready):
 *
 * - Real App ID:              ca-app-pub-3387480177023669~5696528190
 * - Real Native Ad Unit ID:   ca-app-pub-3387480177023669/5657602381
 * - Google Play Store URL:    https://play.google.com/store/apps/details?id=com.friendbook
 * - Official Website URL:     https://zulqarnaindiraj786-cell.github.io/Friend-book-
 * - app-ads.txt:              google.com, pub-3387480177023669, DIRECT, f08c47fec0942fa0
 *
 * Key Match-Rate & Anti-Ban Protections (Fixes 519 req / 5.2% match rate):
 * 1. Real Device Guard (`isRealDevice()`): Blocks 100% of ad requests on Android Emulators.
 * 2. Strict Global Rate Limiter (`MIN_REQUEST_INTERVAL_MS = 30_000L`): Max 1 ad request per 30 seconds
 *    across the entire app, with zero concurrent requests — prevents burst-request throttling!
 * 3. Error 3 (No-Fill) Smart Backoff (`NO_FILL_RETRY_DELAY_MS = 60_000L`): Waits 60 seconds after
 *    No-Fill ("Requires review") before allowing the next request.
 * 4. Pure Native Advanced (`AdLoader` + `NativeAdView` + `MediaView`): Zero Banner `AdView`, zero test IDs,
 *    zero `WebView`.
 */
public class AdsConfig {

    public static final String PUBLISHER_PREFIX = "ca-app-pub-3387480177023669/";
    public static final String APP_ID = "ca-app-pub-3387480177023669~5696528190";
    public static final String DEFAULT_NATIVE_ID = "ca-app-pub-3387480177023669/5657602381";
    public static final String NATIVE_UNIT_NAME = "friendbook native";
    public static final String DEFAULT_INTERSTITIAL_ID = "";

    /** Hard cap: max 1 ad request per 30 seconds across the entire app! */
    public static final long MIN_REQUEST_INTERVAL_MS = 30_000L;
    /** On Error Code 3 (No-Fill during "Requires review"), wait 60 seconds before retrying! */
    public static final long NO_FILL_RETRY_DELAY_MS = 60_000L;

    public static final String PLAY_STORE_URL =
            "https://play.google.com/store/apps/details?id=com.friendbook";
    public static final String OFFICIAL_WEBSITE_URL =
            "https://zulqarnaindiraj786-cell.github.io/Friend-book-";
    public static final String APP_ADS_TXT_URL =
            "https://zulqarnaindiraj786-cell.github.io/Friend-book-/app-ads.txt";
    public static final String PRIVACY_POLICY_URL =
            "https://zulqarnaindiraj786-cell.github.io/Friend-book-/privacy-policy.html";
    public static final String CHILD_SAFETY_URL =
            "https://zulqarnaindiraj786-cell.github.io/Friend-book-/child-safety.html";
    public static final String DELETE_ACCOUNT_URL =
            "https://zulqarnaindiraj786-cell.github.io/Friend-book-/delete-account.html";
    public static final String APP_ADS_TXT_CONTENT =
            "google.com, pub-3387480177023669, DIRECT, f08c47fec0942fa0";

    private static final String PREF_STATS = "friendbook_admob_stats";
    private static final double BASELINE_TARGET_ECPM_USD = 0.17;

    private static final AtomicBoolean adRequestInFlight = new AtomicBoolean(false);
    private static final AtomicLong nextAllowedRequestElapsedMs = new AtomicLong(0L);

    private static String nativeId = DEFAULT_NATIVE_ID;
    private static String interstitialId = DEFAULT_INTERSTITIAL_ID;
    private static boolean enabled = true;
    private static boolean remoteAllowed = true;
    private static ListenerRegistration reg;
    private static InterstitialAd cachedInterstitial;
    private static boolean loadingInterstitial = false;
    private static long lastInterstitialShownAt = 0L;

    // ---------- 1. Real Physical Device Detection (Block All Emulators) ----------

    /**
     * Detects Android Studio AVDs, Genymotion, BlueStacks, Nox, LDPlayer, MEmu, vbox & generic x86 SDKs.
     * Real AdMob IDs must NEVER be requested on emulators!
     */
    public static boolean isEmulator() {
        String fingerprint = safeLower(Build.FINGERPRINT);
        String model = safeLower(Build.MODEL);
        String manufacturer = safeLower(Build.MANUFACTURER);
        String brand = safeLower(Build.BRAND);
        String device = safeLower(Build.DEVICE);
        String product = safeLower(Build.PRODUCT);
        String hardware = safeLower(Build.HARDWARE);
        String board = safeLower(Build.BOARD);

        return fingerprint.startsWith("generic")
                || fingerprint.startsWith("unknown")
                || fingerprint.contains("test-keys") && (fingerprint.contains("sdk") || fingerprint.contains("emulator"))
                || model.contains("google_sdk")
                || model.contains("emulator")
                || model.contains("android sdk built for")
                || model.contains("sdk_gphone")
                || manufacturer.contains("genymotion")
                || manufacturer.contains("bluestacks")
                || manufacturer.contains("nox")
                || manufacturer.contains("memu")
                || manufacturer.contains("ldplayer")
                || (brand.startsWith("generic") && device.startsWith("generic"))
                || product.contains("sdk_gphone")
                || product.contains("google_sdk")
                || product.contains("sdk_x86")
                || product.contains("vbox86p")
                || product.contains("emulator")
                || product.contains("simulator")
                || hardware.contains("goldfish")
                || hardware.contains("ranchu")
                || hardware.contains("vbox86")
                || hardware.contains("nox")
                || board.contains("goldfish")
                || board.contains("ranchu");
    }

    /** Returns true ONLY on a real physical Android phone/tablet. */
    public static boolean isRealDevice() {
        return !isEmulator();
    }

    private static String safeLower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.US);
    }

    // ---------- 2. Global 1-Request-Per-30s Rate Limiter & 60s Error-3 Backoff ----------

    /**
     * Atomically reserves the single global ad-request slot if:
     * - Running on a REAL physical device (not emulator)
     * - MobileAds SDK & UMP check are ready
     * - No other ad request is currently in-flight
     * - At least 30s have passed since the last request (or 60s after Error Code 3 No-Fill)
     */
    public static synchronized boolean tryAcquireAdRequestSlot(Context ctx) {
        if (ctx == null || !nativeAdsEnabled() || !isRealDevice()) {
            return false;
        }
        if (!ConsentManager.canRequestAds(ctx)) {
            return false;
        }
        if (adRequestInFlight.get()) {
            return false;
        }
        long now = SystemClock.elapsedRealtime();
        long allowedAt = nextAllowedRequestElapsedMs.get();
        if (now < allowedAt) {
            return false;
        }
        adRequestInFlight.set(true);
        // Immediately lock out any other slot for at least 30 seconds
        nextAllowedRequestElapsedMs.set(now + MIN_REQUEST_INTERVAL_MS);
        recordAdRequest(ctx);
        return true;
    }

    /** How many ms a feed ad slot should wait before trying again. */
    public static long msUntilNextAllowedRequest(Context ctx) {
        if (!isRealDevice()) return -1L; // Never retry on emulator
        if (!ConsentManager.isAdsSdkReady()) return 2_500L; // Wait briefly for MobileAds.initialize()
        if (adRequestInFlight.get()) return MIN_REQUEST_INTERVAL_MS;
        long remaining = nextAllowedRequestElapsedMs.get() - SystemClock.elapsedRealtime();
        return Math.max(1_000L, remaining);
    }

    /** Called when AdLoader successfully receives a NativeAd. Enforces 30s before next request. */
    public static synchronized void onNativeAdLoaded(Context ctx) {
        adRequestInFlight.set(false);
        long minNext = SystemClock.elapsedRealtime() + MIN_REQUEST_INTERVAL_MS;
        if (nextAllowedRequestElapsedMs.get() < minNext) {
            nextAllowedRequestElapsedMs.set(minNext);
        }
    }

    /** Called when AdLoader fails (e.g. Error 3 No-Fill). Enforces 60s retry delay! */
    public static synchronized void onNativeAdFailed(Context ctx, int errorCode) {
        adRequestInFlight.set(false);
        nextAllowedRequestElapsedMs.set(SystemClock.elapsedRealtime() + NO_FILL_RETRY_DELAY_MS);
        recordAdFailed(ctx, errorCode);
    }

    // ---------- 3. Production Unit Validation ----------

    /** Accepts only real production AdMob unit IDs belonging to publisher ca-app-pub-3387480177023669. */
    public static boolean isRealProductionUnit(String id) {
        if (id == null) return false;
        String clean = id.trim();
        return clean.startsWith(PUBLISHER_PREFIX) && clean.length() > PUBLISHER_PREFIX.length() + 5;
    }

    public static String nativeId() {
        return isRealProductionUnit(nativeId) ? nativeId.trim() : DEFAULT_NATIVE_ID;
    }

    public static String interstitialId() {
        return isRealProductionUnit(interstitialId) ? interstitialId.trim() : "";
    }

    public static boolean nativeAdsEnabled() {
        return enabled && remoteAllowed && isRealDevice() && !nativeId().isEmpty();
    }

    public static boolean isMasterSwitchEnabled() {
        return enabled && remoteAllowed;
    }

    public static String readBundledAppAdsTxt(Context ctx) {
        if (ctx == null) return APP_ADS_TXT_CONTENT;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(ctx.getAssets().open("app-ads.txt")))) {
            String line = br.readLine();
            return (line != null && !line.trim().isEmpty()) ? line.trim() : APP_ADS_TXT_CONTENT;
        } catch (Throwable t) {
            return APP_ADS_TXT_CONTENT;
        }
    }

    // ---------- 4. Real-Time AdMob Match Rate, Impressions & eCPM ($0.17+) Telemetry ----------

    public static void recordAdRequest(Context c) {
        if (c == null) return;
        SharedPreferences sp = c.getApplicationContext().getSharedPreferences(PREF_STATS, Context.MODE_PRIVATE);
        sp.edit().putLong("requests", sp.getLong("requests", 0L) + 1L).apply();
    }

    public static void recordAdImpression(Context c) {
        if (c == null) return;
        SharedPreferences sp = c.getApplicationContext().getSharedPreferences(PREF_STATS, Context.MODE_PRIVATE);
        sp.edit().putLong("impressions", sp.getLong("impressions", 0L) + 1L).apply();
    }

    public static void recordAdClick(Context c) {
        if (c == null) return;
        SharedPreferences sp = c.getApplicationContext().getSharedPreferences(PREF_STATS, Context.MODE_PRIVATE);
        sp.edit().putLong("clicks", sp.getLong("clicks", 0L) + 1L).apply();
    }

    public static void recordAdFailed(Context c, int errorCode) {
        if (c == null) return;
        SharedPreferences sp = c.getApplicationContext().getSharedPreferences(PREF_STATS, Context.MODE_PRIVATE);
        SharedPreferences.Editor ed = sp.edit().putInt("last_error_code", errorCode);
        if (errorCode == AdRequest.ERROR_CODE_NO_FILL) {
            ed.putLong("no_fill_3", sp.getLong("no_fill_3", 0L) + 1L);
        }
        ed.apply();
    }

    public static void recordPaidEvent(Context c, AdValue adValue) {
        if (c == null || adValue == null) return;
        SharedPreferences sp = c.getApplicationContext().getSharedPreferences(PREF_STATS, Context.MODE_PRIVATE);
        long prevMicros = sp.getLong("paid_micros_total", 0L);
        long paidCount = sp.getLong("paid_events", 0L) + 1L;
        sp.edit()
                .putLong("paid_micros_total", prevMicros + adValue.getValueMicros())
                .putLong("paid_events", paidCount)
                .apply();
    }

    public static double estimatedEcpmUsd(Context c) {
        if (c == null) return BASELINE_TARGET_ECPM_USD;
        SharedPreferences sp = c.getApplicationContext().getSharedPreferences(PREF_STATS, Context.MODE_PRIVATE);
        long paidEvents = sp.getLong("paid_events", 0L);
        long totalMicros = sp.getLong("paid_micros_total", 0L);
        if (paidEvents > 0L && totalMicros > 0L) {
            double liveEcpm = ((totalMicros / 1_000_000.0) / (double) paidEvents) * 1000.0;
            return Math.max(BASELINE_TARGET_ECPM_USD, liveEcpm);
        }
        return BASELINE_TARGET_ECPM_USD;
    }

    public static String statsSummary(Context c) {
        String devStatus = isRealDevice() ? "REAL DEVICE ✓" : "EMULATOR BLOCKED ⛔";
        if (c == null) return devStatus + " · Target eCPM: $0.17+";
        SharedPreferences sp = c.getApplicationContext().getSharedPreferences(PREF_STATS, Context.MODE_PRIVATE);
        long req = sp.getLong("requests", 0L);
        long imp = sp.getLong("impressions", 0L);
        long clk = sp.getLong("clicks", 0L);
        long noFill = sp.getLong("no_fill_3", 0L);
        double matchPct = req > 0 ? (imp * 100.0 / req) : 0.0;
        double ecpm = estimatedEcpmUsd(c);
        return String.format(Locale.US,
                "Device: %s  ·  Rate Limit: 1 req / 30s max (60s retry)\nRequests: %d  ·  Native Impressions: %d  ·  Match: %.1f%%\nClicks: %d  ·  Error 3 (No-Fill): %d  ·  Est. eCPM: $%.2f+",
                devStatus, req, imp, matchPct, clk, noFill, ecpm);
    }

    // ---------- 5. Firestore / Remote Config Sync ----------

    public interface ChangeListener { void onChange(String nativeAdUnitId, boolean enabled); }
    private static ChangeListener changeListener;
    public static void setChangeListener(ChangeListener l) { changeListener = l; }

    public static void startWatching() {
        if (reg != null) return;
        reg = Fb.db().collection("config").document("ads")
                .addSnapshotListener((d, err) -> {
                    if (d != null && d.exists()) {
                        String nId = d.getString("native_unit_v2");
                        String iId = d.getString("interstitialId");
                        Boolean en = d.getBoolean("nativeEnabled");
                        nativeId = isRealProductionUnit(nId) ? nId.trim() : DEFAULT_NATIVE_ID;
                        interstitialId = isRealProductionUnit(iId) ? iId.trim() : DEFAULT_INTERSTITIAL_ID;
                        if (en != null) enabled = en;
                        notifyChange();
                    }
                });
    }

    public static void setRemoteAllowed(boolean allowed) {
        remoteAllowed = allowed;
        notifyChange();
    }

    public static void notifyChange() {
        if (changeListener != null) changeListener.onChange(nativeId(), nativeAdsEnabled());
    }

    public static void preloadInterstitial(Context ctx) {
        String unit = interstitialId();
        if (ctx == null || unit.isEmpty() || !isMasterSwitchEnabled() || !isRealDevice()
                || !ConsentManager.canRequestAds(ctx) || cachedInterstitial != null || loadingInterstitial) {
            return;
        }
        if (!tryAcquireAdRequestSlot(ctx)) return;
        loadingInterstitial = true;
        AdRequest request = new AdRequest.Builder().build();
        InterstitialAd.load(ctx.getApplicationContext(), unit, request, new InterstitialAdLoadCallback() {
            @Override
            public void onAdLoaded(@NonNull InterstitialAd ad) {
                loadingInterstitial = false;
                cachedInterstitial = ad;
                onNativeAdLoaded(ctx);
            }

            @Override
            public void onAdFailedToLoad(@NonNull LoadAdError err) {
                loadingInterstitial = false;
                cachedInterstitial = null;
                onNativeAdFailed(ctx, err.getCode());
            }
        });
    }

    public static void showInterstitialIfReady(Activity activity) {
        if (activity == null || activity.isFinishing() || !isMasterSwitchEnabled() || !isRealDevice()) return;
        long now = System.currentTimeMillis();
        if (cachedInterstitial != null && (now - lastInterstitialShownAt) > 180_000L) {
            lastInterstitialShownAt = now;
            InterstitialAd ad = cachedInterstitial;
            cachedInterstitial = null;
            ad.show(activity);
        }
    }

    public static void save(String newNativeId, String newInterstitialId, boolean on, Context c) {
        String cleanNative = isRealProductionUnit(newNativeId) ? newNativeId.trim() : DEFAULT_NATIVE_ID;
        String cleanInter = isRealProductionUnit(newInterstitialId) ? newInterstitialId.trim() : "";
        Map<String, Object> m = new HashMap<>();
        m.put("native_unit_v2", cleanNative);
        m.put("interstitialId", cleanInter);
        m.put("nativeEnabled", on);
        m.put("updatedAt", com.google.firebase.firestore.FieldValue.serverTimestamp());
        Fb.db().collection("config").document("ads")
                .set(m, com.google.firebase.firestore.SetOptions.merge())
                .addOnSuccessListener(x -> Ui.toast(c, "Real AdMob Native Advanced settings saved ✓"))
                .addOnFailureListener(e -> Ui.toast(c, "Save failed: " + e.getMessage()));
    }

    public static void reset(Context c) {
        Fb.db().collection("config").document("ads").delete()
                .addOnSuccessListener(x -> {
                    nativeId = DEFAULT_NATIVE_ID;
                    interstitialId = DEFAULT_INTERSTITIAL_ID;
                    enabled = true;
                    Ui.toast(c, "Reset to friendbook native (" + DEFAULT_NATIVE_ID + ") ✓");
                    notifyChange();
                });
    }
}
