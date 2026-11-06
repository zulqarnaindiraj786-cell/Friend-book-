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
    // ⚡ Zero-Wait Preloaded NativeAd Pool + 16-Creative Rotating Catalog
    // ------------------------------------------------------------------------
    private static final int POOL_TARGET_SIZE = 6;
    private static final int MAX_ROTATING_ADS = 16;
    private static final java.util.Deque<com.google.android.gms.ads.nativead.NativeAd> FEED_POOL =
            new java.util.ArrayDeque<>();
    private static final java.util.Deque<com.google.android.gms.ads.nativead.NativeAd> REELS_POOL =
            new java.util.ArrayDeque<>();
    private static final java.util.List<com.google.android.gms.ads.nativead.NativeAd> LOADED_FEED_ADS =
            new java.util.ArrayList<>();
    private static final java.util.List<com.google.android.gms.ads.nativead.NativeAd> LOADED_REELS_ADS =
            new java.util.ArrayList<>();
    private static final Map<Integer, com.google.android.gms.ads.nativead.NativeAd> FEED_SLOT_ADS =
            new HashMap<>();
    private static final Map<Integer, com.google.android.gms.ads.nativead.NativeAd> REELS_SLOT_ADS =
            new HashMap<>();
    private static int feedInFlight = 0;
    private static int reelsInFlight = 0;
    private static int adRotationOffset = (int) ((System.currentTimeMillis() / 1000L) % 16);

    public static final class SponsoredCreative {
        public final int id;
        public final String headline;
        public final String advertiser;
        public final String body;
        public final String cta;
        public final String storeOrPrice;
        public final String bannerBadge;
        public final String bannerBigTitle;
        public final String bannerSubTitle;
        public final int bannerColorStart;
        public final int bannerColorEnd;
        public final float stars;

        public SponsoredCreative(int id, String headline, String advertiser, String body, String cta,
                                 String storeOrPrice, String bannerBadge, String bannerBigTitle,
                                 String bannerSubTitle, int bannerColorStart, int bannerColorEnd, float stars) {
            this.id = id;
            this.headline = headline;
            this.advertiser = advertiser;
            this.body = body;
            this.cta = cta;
            this.storeOrPrice = storeOrPrice;
            this.bannerBadge = bannerBadge;
            this.bannerBigTitle = bannerBigTitle;
            this.bannerSubTitle = bannerSubTitle;
            this.bannerColorStart = bannerColorStart;
            this.bannerColorEnd = bannerColorEnd;
            this.stars = stars;
        }
    }

    private static final SponsoredCreative[] CATALOG = new SponsoredCreative[]{
            new SponsoredCreative(0, "Creator Studio 4K Pro", "FriendBook Creators",
                    "Go Live in HD, earn instant gifts & grow your audience faster with Pro Creator tools.",
                    "Explore Now", "Free Upgrade", "LIVE STUDIO", "Go Live & Earn Gems 💎",
                    "HD Stream • Instant Gifts • Creator Analytics", 0xFF1877F2, 0xFF0A3D91, 4.9f),
            new SponsoredCreative(1, "HyperStrike Mobile Arena", "Apex Interactive Games",
                    "Squad up in 60FPS battle royale matches with ultra-low ping servers & voice chat.",
                    "Play Free", "Google Play • 4.8★", "60FPS GAMING", "HyperStrike Season 9 🎮",
                    "New Maps • Squad Voice • Zero-Lag Servers", 0xFF6A11CB, 0xFF2575FC, 4.8f),
            new SponsoredCreative(2, "Zinger Feast Mega Box", "CraveBite Express",
                    "Hot crispy zinger burger, loaded fries & chilled drink delivered to your door in 25 mins.",
                    "Order Now", "50% OFF Today", "HOT DEAL 🍔", "Mega Zinger Box 50% OFF",
                    "25-Min Express Delivery • Free Drink Included", 0xFFD32F2F, 0xFFFF6D00, 4.9f),
            new SponsoredCreative(3, "SoundPulse ANC Buds Pro", "NovaTech Audio",
                    "45dB Active Noise Cancellation, deep bass & 36-hour battery life with fast Type-C charge.",
                    "Shop Deal", "Rs. 3,499 • Free Delivery", "FLASH SALE 🎧", "SoundPulse ANC Pro",
                    "45dB Noise Cancelling • 36Hr Battery Life", 0xFF0F2027, 0xFF2C5364, 4.7f),
            new SponsoredCreative(4, "Urban Drip Streetwear '26", "VogueLane PK",
                    "Oversized premium cotton hoodies, cargo joggers & limited-edition sneakers now in stock.",
                    "Shop Collection", "Cash on Delivery", "NEW ARRIVAL 👕", "Urban Streetwear Drop",
                    "Buy 2 Get 1 Free • Nationwide Cash on Delivery", 0xFF1F1C2C, 0xFF928DAB, 4.8f),
            new SponsoredCreative(5, "Cricket Live HD Pass", "SportsMax Stream",
                    "Watch every T20 & International match live in Full HD with ball-by-ball instant replays.",
                    "Watch Live", "HD Ad-Free Stream", "LIVE CRICKET 🏏", "Watch Every Match in HD",
                    "Live Scoreboard • Multi-Cam • Instant Highlights", 0xFF00695C, 0xFF00BFA5, 4.9f),
            new SponsoredCreative(6, "Northern Peaks 5-Day Tour", "WanderLust Travels",
                    "All-inclusive Hunza, Skardu & Fairy Meadows luxury group tour with bonfire & photography.",
                    "Book Seat", "20% Early Bird Off", "TRAVEL PK 🏔️", "Explore Hunza & Skardu",
                    "Luxury Transport • 4-Star Hotels • Guided Tour", 0xFF134E5E, 0xFF71B280, 4.9f),
            new SponsoredCreative(7, "AI Photo & Reel Cut Pro", "PixelCraft Studio",
                    "One-tap AI background remover, viral velocity transitions & auto captions for Reels.",
                    "Try Free", "Top Video Editor", "AI EDITOR ✨", "Create Viral Reels in 10s",
                    "Auto Captions • 4K Export • Beat Sync Effects", 0xFF8E2DE2, 0xFF4A00E0, 4.8f),
            new SponsoredCreative(8, "TurboFiber 100Mbps Unlimited", "NetWave Fiber",
                    "True fiber-to-the-home internet with zero speed throttling, free dual-band Wi-Fi 6 router.",
                    "Check Coverage", "Free Installation", "FIBER WIFI 6 ⚡", "100Mbps True Fiber",
                    "Zero Buffering • Lowest Gaming Ping • 24/7 Support", 0xFF0052D4, 0xFF4364F7, 4.7f),
            new SponsoredCreative(9, "FitFuel Whey Isolate+", "IronCore Nutrition",
                    "27g ultra-pure hydrolyzed protein per scoop with BCAAs for lean muscle & rapid recovery.",
                    "Order Authentic", "100% Lab Verified", "FITNESS PRO 💪", "FitFuel Isolate 27g Protein",
                    "Zero Added Sugar • Rich Double Chocolate", 0xFF232526, 0xFF414345, 4.8f),
            new SponsoredCreative(10, "Full-Stack AI & Freelancing", "SkillLaunch Academy",
                    "Master React, Python AI & Upwork client acquisition in 8 weeks with live mentor projects.",
                    "Join Free Class", "Certificate Included", "LEARN & EARN 💻", "Master AI & Freelancing",
                    "Live Weekend Classes • Portfolio Projects", 0xFF1A2980, 0xFF26D0CE, 4.9f),
            new SponsoredCreative(11, "SmartWatch Ultra AMOLED", "ChronoGear Official",
                    "Always-on 2.1\" curved AMOLED display, Bluetooth calling, SpO2 & 7-day battery backup.",
                    "Buy Now", "40% Launch Discount", "SMART TECH ⌚", "Ultra AMOLED SmartWatch",
                    "Bluetooth Calling • Waterproof IP68 • 100+ Modes", 0xFF3A1C71, 0xFFD76D77, 4.8f),
            new SponsoredCreative(12, "Glow Derm Vitamin C Serum", "Pure Aura Skincare",
                    "Dermatologist-tested 15% Vitamin C + Hyaluronic Acid for instant glass-skin radiance.",
                    "Get Offer", "Buy 1 Get 1 50% Off", "SKINCARE ✨", "24K Glow Vitamin C Serum",
                    "Dark Spot Corrector • Deep Hydration Formula", 0xFFB24592, 0xFFF15F79, 4.9f),
            new SponsoredCreative(13, "MegaMart Grocery in 20m", "QuickCart Instant",
                    "Fresh fruits, dairy, snacks & household essentials at wholesale supermarket prices.",
                    "Start Shopping", "Rs. 500 Welcome Voucher", "SUPERMARKET 🛒", "Groceries in 20 Minutes",
                    "Wholesale Prices • Free First 3 Deliveries", 0xFF11998E, 0xFF38EF7D, 4.8f),
            new SponsoredCreative(14, "AutoCare Ceramic Coat Kit", "TorqueLabs PK",
                    "9H hydrophobic nano-ceramic shine that protects your car & bike paint for 12 months.",
                    "Order Kit", "Free Microfiber Kit", "AUTO CARE 🚘", "9H Nano Ceramic Coating",
                    "Mirror Gloss • UV & Scratch Protection", 0xFF141E30, 0xFF243B55, 4.7f),
            new SponsoredCreative(15, "PocketPay Zero-Fee Wallet", "FinEdge Digital",
                    "Instant IBFT transfers, bill split, virtual Visa card & up to 15% cashback on food & fuel.",
                    "Open Account", "Rs. 250 Signup Bonus", "INSTANT CASHBACK 💳", "PocketPay Virtual Visa",
                    "Zero Annual Fee • 15% Cashback on Dining", 0xFF004FF9, 0xFF000000, 4.9f)
    };

    public static synchronized SponsoredCreative getCreativeForSlot(int slotKey) {
        int idx = Math.abs(slotKey + adRotationOffset) % CATALOG.length;
        return CATALOG[idx];
    }

    private static synchronized void rememberFeedAd(com.google.android.gms.ads.nativead.NativeAd ad) {
        if (ad == null) return;
        if (!LOADED_FEED_ADS.contains(ad)) {
            if (LOADED_FEED_ADS.size() >= MAX_ROTATING_ADS) LOADED_FEED_ADS.remove(0);
            LOADED_FEED_ADS.add(ad);
        }
    }

    private static synchronized void rememberReelsAd(com.google.android.gms.ads.nativead.NativeAd ad) {
        if (ad == null) return;
        if (!LOADED_REELS_ADS.contains(ad)) {
            if (LOADED_REELS_ADS.size() >= MAX_ROTATING_ADS) LOADED_REELS_ADS.remove(0);
            LOADED_REELS_ADS.add(ad);
        }
    }

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
        if (polled == null && slotOrdinal < LOADED_FEED_ADS.size()) {
            polled = LOADED_FEED_ADS.get(slotOrdinal);
        }
        if (polled != null) {
            FEED_SLOT_ADS.put(slotOrdinal, polled);
        }
        warmUpPools(context);
        return polled;
    }

    public static synchronized void putFeedSlotAd(int slotOrdinal, com.google.android.gms.ads.nativead.NativeAd ad) {
        if (ad != null) {
            rememberFeedAd(ad);
            FEED_SLOT_ADS.put(slotOrdinal, ad);
        }
    }

    public static synchronized com.google.android.gms.ads.nativead.NativeAd getOrPollReelsAd(Context context, int slotOrdinal) {
        com.google.android.gms.ads.nativead.NativeAd existing = REELS_SLOT_ADS.get(slotOrdinal);
        if (existing != null) return existing;
        com.google.android.gms.ads.nativead.NativeAd polled = REELS_POOL.pollFirst();
        if (polled == null && slotOrdinal < LOADED_REELS_ADS.size()) {
            polled = LOADED_REELS_ADS.get(slotOrdinal);
        }
        if (polled != null) {
            REELS_SLOT_ADS.put(slotOrdinal, polled);
        }
        warmUpPools(context);
        return polled;
    }

    public static synchronized boolean hasReadyReelsSlotOrPoolAd(int slotOrdinal) {
        return REELS_SLOT_ADS.containsKey(slotOrdinal)
                || !REELS_POOL.isEmpty()
                || slotOrdinal < LOADED_REELS_ADS.size();
    }

    public static synchronized void putReelsSlotAd(int slotOrdinal, com.google.android.gms.ads.nativead.NativeAd ad) {
        if (ad != null) {
            rememberReelsAd(ad);
            REELS_SLOT_ADS.put(slotOrdinal, ad);
        }
    }

    public static synchronized void clearFeedSlotAds(Context context) {
        FEED_SLOT_ADS.clear();
        adRotationOffset = (adRotationOffset + 3) % CATALOG.length;
        warmUpPools(context);
    }

    public static synchronized void clearReelsSlotAds(Context context) {
        REELS_SLOT_ADS.clear();
        adRotationOffset = (adRotationOffset + 5) % CATALOG.length;
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
                                    rememberFeedAd(nativeAd);
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
                                    rememberReelsAd(nativeAd);
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

    // ------------------------------------------------------------------------
    // 💬 100% REAL Multi-User Likes, Reactions & Full-Post Comments on Ads
    //    Backed by real Firestore `posts/{adDocId}`, `Reactions.java`, and
    //    `ReelCommentsActivity` / `CommentsEngine` (zero fake base counts!)
    // ------------------------------------------------------------------------
    private static final Map<String, com.friendbook.social.models.Models.Post> AD_POST_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<String> AD_POST_FETCHED =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    public static String adDocId(int slotKey) {
        SponsoredCreative cr = getCreativeForSlot(slotKey);
        return "sponsored_ad_creative_" + cr.id;
    }

    public static com.friendbook.social.models.Models.Post getOrCreateAdPost(int slotKey) {
        SponsoredCreative cr = getCreativeForSlot(slotKey);
        String docId = "sponsored_ad_creative_" + cr.id;
        com.friendbook.social.models.Models.Post p = AD_POST_CACHE.get(docId);
        if (p == null) {
            p = new com.friendbook.social.models.Models.Post();
            p.id = docId;
            p.authorId = "friendbook_sponsored";
            p.authorName = cr.advertiser;
            p.authorPhoto = Ui.TEAM_AVATAR_TOKEN;
            p.text = cr.headline + " — " + cr.body;
            p.privacy = "Only me"; // Keeps ad doc out of regular HomeFragment post query while real for likes/comments
            p.createdAt = 1L;
            p.likes = 0;
            p.comments = 0;
            p.shares = 0;
            AD_POST_CACHE.put(docId, p);
        }
        return p;
    }

    /** Hydrates real user likes, reactions & comment counts from Firestore `posts/{adDocId}` once per ad creative. */
    public static void hydrateAdSocialFromFirestore(final Context c, final int slotKey, final Runnable onHydrated) {
        final com.friendbook.social.models.Models.Post adPost = getOrCreateAdPost(slotKey);
        final String docId = adPost.id;
        if (!AD_POST_FETCHED.add(docId)) {
            if (onHydrated != null) onHydrated.run();
            return;
        }
        try {
            Fb.db().collection("posts").document(docId).get()
                    .addOnSuccessListener(snap -> {
                        if (snap != null && snap.exists()) {
                            com.friendbook.social.models.Models.Post remote =
                                    com.friendbook.social.models.Models.Post.fromDoc(snap);
                            adPost.likes = Math.max(0, remote.likes);
                            adPost.comments = Math.max(0, remote.comments);
                            adPost.shares = Math.max(0, remote.shares);
                            if (remote.reactionsMap != null) {
                                adPost.reactionsMap.clear();
                                adPost.reactionsMap.putAll(remote.reactionsMap);
                            }
                            if (remote.inlineComments != null) {
                                adPost.inlineComments.clear();
                                adPost.inlineComments.addAll(remote.inlineComments);
                            }
                            com.friendbook.social.post.Reactions.hydrateFromPostInstant(adPost);
                        } else {
                            // Seed minimal parent doc with 0 likes & 0 comments so real user reactions/comments attach cleanly
                            Map<String, Object> seed = new HashMap<>();
                            seed.put("id", docId);
                            seed.put("authorId", "friendbook_sponsored");
                            seed.put("authorName", adPost.authorName);
                            seed.put("authorPhoto", Ui.TEAM_AVATAR_TOKEN);
                            seed.put("text", adPost.text);
                            seed.put("privacy", "Only me");
                            seed.put("createdAt", 1L);
                            seed.put("likes", 0);
                            seed.put("comments", 0);
                            seed.put("shares", 0);
                            Fb.db().collection("posts").document(docId).set(seed, SetOptions.merge());
                        }
                        if (onHydrated != null) onHydrated.run();
                    })
                    .addOnFailureListener(e -> {
                        AD_POST_FETCHED.remove(docId);
                        if (onHydrated != null) onHydrated.run();
                    });
        } catch (Throwable ignored) {
            AD_POST_FETCHED.remove(docId);
        }
    }

    public static String getAdMyReaction(Context c, int slotKey) {
        com.friendbook.social.models.Models.Post adPost = getOrCreateAdPost(slotKey);
        String cached = FastCache.getMyReaction(adPost.id);
        if (cached != null) return "none".equals(cached) ? "" : cached;
        String me = Fb.uid();
        if (me != null && adPost.reactionsMap != null && adPost.reactionsMap.containsKey(me)) {
            Map<String, Object> m = adPost.reactionsMap.get(me);
            Object t = m != null ? m.get("type") : null;
            if (t instanceof String && !"none".equals(t)) return (String) t;
        }
        return "";
    }

    public static int getAdLikeCount(Context c, int slotKey) {
        com.friendbook.social.models.Models.Post adPost = getOrCreateAdPost(slotKey);
        int cached = FastCache.getReactionTotal(adPost.id, -1);
        int mapSize = adPost.reactionsMap != null ? adPost.reactionsMap.size() : 0;
        int raw = cached >= 0 ? Math.max(cached, mapSize) : Math.max(adPost.likes, mapSize);
        return Math.max(0, raw);
    }

    public static int getAdCommentCount(Context c, int slotKey) {
        com.friendbook.social.models.Models.Post adPost = getOrCreateAdPost(slotKey);
        int inlineCount = adPost.inlineComments != null ? adPost.inlineComments.size() : 0;
        return Math.max(0, Math.max(adPost.comments, inlineCount));
    }

    public static int getAdShareCount(Context c, int slotKey) {
        com.friendbook.social.models.Models.Post adPost = getOrCreateAdPost(slotKey);
        return Math.max(0, adPost.shares);
    }

    /** Applies a 100% REAL user reaction (`Reactions.tapLike` / `Reactions.apply`) on `posts/{adDocId}` in Firestore! */
    public static void setOrToggleAdReaction(Context c, int slotKey, String picked, Runnable onUpdated) {
        if (c == null) return;
        com.friendbook.social.models.Models.Post adPost = getOrCreateAdPost(slotKey);
        if (picked == null) {
            com.friendbook.social.post.Reactions.tapLike(c, adPost);
        } else {
            com.friendbook.social.post.Reactions.apply(c, adPost, picked);
        }
        if (onUpdated != null) onUpdated.run();
    }

    /** Opens the real `Reactions.showWhoReacted` sheet showing every real app user who liked/reacted to this Ad! */
    public static void showWhoReactedToAd(Context c, int slotKey) {
        if (!(c instanceof android.app.Activity)) return;
        com.friendbook.social.models.Models.Post adPost = getOrCreateAdPost(slotKey);
        com.friendbook.social.post.Reactions.showWhoReacted((android.app.Activity) c, adPost.id);
    }

    public static void shareSponsoredAd(Context c, int slotKey, CharSequence headline, CharSequence body, Runnable onUpdated) {
        if (c == null) return;
        com.friendbook.social.models.Models.Post adPost = getOrCreateAdPost(slotKey);
        adPost.shares = Math.max(0, adPost.shares) + 1;
        if (onUpdated != null) onUpdated.run();
        try {
            Map<String, Object> up = new HashMap<>();
            up.put("shares", FieldValue.increment(1));
            Fb.db().collection("posts").document(adPost.id).set(up, SetOptions.merge());
        } catch (Throwable ignored) { }
        String h = headline != null && headline.length() > 0 ? headline.toString() : adPost.authorName;
        String b = body != null && body.length() > 0 ? ("\n" + body) : "";
        android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(android.content.Intent.EXTRA_TEXT, "📢 " + h + b + "\n\n" + PLAY_STORE_URL);
        c.startActivity(android.content.Intent.createChooser(send, "Share Sponsored Post"));
    }

    /**
     * Opens the FULL real-time multi-user `ReelCommentsActivity` thread backed by `CommentsEngine`
     * on `posts/{adDocId}` — supporting real comments, @mentions, replies, comment likes & sorting!
     */
    public static void openSponsoredCommentsSheet(final Context c, final int slotKey, CharSequence headline, final Runnable onUpdated) {
        if (c == null) return;
        final com.friendbook.social.models.Models.Post adPost = getOrCreateAdPost(slotKey);
        // Ensure parent doc exists in Firestore before opening full comments screen
        AD_POST_FETCHED.remove(adPost.id);
        hydrateAdSocialFromFirestore(c, slotKey, onUpdated);
        android.content.Intent i = new android.content.Intent(c, com.friendbook.social.reels.ReelCommentsActivity.class);
        i.putExtra("reel_id", adPost.id);
        i.putExtra("collection", "posts");
        i.putExtra("reel_author_id", "friendbook_sponsored");
        c.startActivity(i);
    }
}
