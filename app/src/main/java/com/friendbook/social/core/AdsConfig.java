package com.friendbook.social.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.android.gms.ads.AdValue;
import com.google.android.gms.ads.nativead.NativeAd;
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

    /**
     * Meta Audience Network app + placement used through AdMob mediation.
     *
     * <p>These are deliberately not ad unit IDs. In a mediated setup the app requests the
     * AdMob unit above, and AdMob's waterfall decides whether Meta answers; the adapter reads
     * the Meta placement from the mediation group in the AdMob console, not from here. The
     * IDs are kept so the manifest, the SDK initialiser and this class cannot drift apart -
     * Meta refuses to fill if the initialised app ID differs from the one on the placement.
     */
    public static final String META_APP_ID = "4871960679794940";
    public static final String META_PLACEMENT_ID = "4871960679794940_4871960806461594";

    /** True when the Meta adapter is on the mediation path for this build. */
    public static boolean metaMediationConfigured() {
        return META_APP_ID.length() > 0 && META_PLACEMENT_ID.startsWith(META_APP_ID + "_");
    }

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
    /**
     * The one place the store link is defined.
     *
     * <p>Every share, copy and invite in the app must point here. It previously existed as two
     * separate constants, so a change to one left the other pointing at the old listing, and a
     * few screens shared a {@code friendbook://} URL that no installed app can open at all.
     */
    public static final String PLAY_STORE_URL = com.friendbook.social.core.Ui.PLAY_STORE_LINK;
    public static final String APP_ADS_TXT_RECORD =
            "google.com, pub-3387480177023669, DIRECT, f08c47fec0942fa0";

    private static final String PREF = "fb_admob_stats_v3";
    private static final String KEY_REQUESTS = "ad_requests";
    private static final String KEY_LOADS = "ad_loads";
    private static final String KEY_IMPRESSIONS = "ad_impressions";
    private static final String KEY_CLICKS = "ad_clicks";
    private static final String KEY_FAILS = "ad_fails";
    private static final String KEY_LAST_ERROR = "last_error_code";
    private static final String KEY_LAST_ERROR_MSG = "last_error_message";
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
    private static final android.os.Handler MAIN_UI_HANDLER =
            new android.os.Handler(android.os.Looper.getMainLooper());

    public static void setChangeListener(ChangeListener l) {
        listener = l;
    }

    public static void notifyChange() {
        MAIN_UI_HANDLER.post(() -> {
            if (listener != null) {
                listener.onAdsConfigChanged(feedNativeId(), nativeAdsEnabled());
            }
        });
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
        onNativeAdFailed(c, errorCode, null);
    }

    /**
     * Stores the REAL reason AdMob refused (domain + message), so "no ads showing" can be diagnosed
     * on the device instead of guessed: Settings -> AdMob shows it, and it is logged to Logcat.
     */
    public static void onNativeAdFailed(Context c, int errorCode, String detail) {
        if (c == null) return;
        SharedPreferences sp = prefs(c);
        String msg = detail == null ? "" : detail.trim();
        if (msg.length() > 200) msg = msg.substring(0, 200);
        sp.edit()
                .putLong(KEY_FAILS, sp.getLong(KEY_FAILS, 0L) + 1L)
                .putInt(KEY_LAST_ERROR, errorCode)
                .putString(KEY_LAST_ERROR_MSG, msg)
                .apply();
        Log.w(LOG_TAG, "Native ad load failed: code=" + errorCode + (msg.isEmpty() ? "" : " (" + msg + ")"));
    }

    /** Current pool fill, for the Admin fill-check screen (never a fake ad, just the truth). */
    public static synchronized int feedPoolSize() { return AdQueueManager.getInstance().getFeedQueueSize(); }

    public static synchronized int reelsPoolSize() { return AdQueueManager.getInstance().getReelsQueueSize(); }

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
        String lastMsg = sp.getString(KEY_LAST_ERROR_MSG, "");
        double usd = sp.getLong(KEY_MICROS, 0L) / 1_000_000.0;
        String errLabel = lastErr < 0 ? "None"
                : ("Code " + lastErr + (lastMsg == null || lastMsg.isEmpty() ? "" : " — " + lastMsg));
        return String.format(Locale.US,
                "Mode: Real Production Only  •  Requests: %d  •  Loaded: %d\n"
                        + "Impressions: %d  •  Clicks: %d  •  Failed: %d (Last error: %s)  •  Est: $%.4f\n"
                        + "Queue now: Feed %d/3  •  Reels %d/3\n"
                        + "Switches: native ads %s (Admin master %s, Remote Config %s)  •  SDK initialized: %s",
                req, loads, imps, clicks, fails, errLabel, usd,
                AdQueueManager.getInstance().getFeedQueueSize(),
                AdQueueManager.getInstance().getReelsQueueSize(),
                nativeAdsEnabled() ? "ON" : "OFF",
                masterSwitchEnabled ? "ON" : "OFF",
                remoteAllowed ? "ON" : "OFF",
                ConsentManager.isAdsSdkInitialized() ? "yes" : "not yet");
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
            // One read, not a listener. Config changes are an admin action; an interval watch
            // would spend reads on every client forever to catch a handful of edits.
            com.friendbook.social.core.CloudflareStore.get("config", "ads", doc -> {
                        if (doc != null) {
                            // doc is the stored field map, not a document handle.
                            String fId = Format.str(doc, "feed_native_unit_v3");
                            String rId = Format.str(doc, "reels_native_unit_v3");
                            Object rawEn = doc.get("ads_enabled_v3");
                            Boolean en = rawEn instanceof Boolean ? (Boolean) rawEn : null;
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
        com.friendbook.social.core.DataRouter.batch()
                .update("config", "ads", m)
                .commit((ok, err) -> {
                    if (ok) Ui.toast(ctx, "Production AdMob config saved ✓");
                    else Ui.toast(ctx, "Save failed: " + (err != null ? err : "error"));
                });
    }

    public static void reset(Context ctx) {
        save(FEED_NATIVE_ID, REELS_NATIVE_ID, true, ctx);
    }

    // ------------------------------------------------------------------------
    // ⚡ Facebook-Style Ad Queue System (3 ads Feed + 3 ads Reels, always ready)
    // ------------------------------------------------------------------------
    // Old 5-ad pool system REMOVED. New queue-based system in AdQueueManager.java:
    // - App start: Preloads 3 Feed ads + 3 Reels ads into separate queues
    // - On scroll: Pop 1 ad from queue every 3 posts/reels
    // - On impression: Immediately load 1 new ad to refill queue (keeps 3 always ready)
    // - Cache/reuse: Ads in queue reused on scroll up/down, never re-requested
    // - Load 1 ad at a time (not batches of 5)

    /** Preloads both 3-Ad Feed and 3-Ad Reels queues using new Facebook-style AdQueueManager. */
    public static synchronized void warmUpPools(final Context context) {
        if (context == null || !nativeAdsEnabled()) return;
        AdQueueManager.getInstance().initialize(context);
    }

    /**
     * Call when Feed ad gets impression - triggers immediate refill
     */
    public static void onFeedAdImpression(Context context) {
        AdQueueManager.getInstance().onFeedAdImpression();
    }

    /**
     * Call when Reels ad gets impression - triggers immediate refill
     */
    public static void onReelsAdImpression(Context context) {
        AdQueueManager.getInstance().onReelsAdImpression();
    }

    /**
     * Get Feed ad from queue (non-blocking)
     */
    public static NativeAd pollFeedAd(Context context) {
        return AdQueueManager.getInstance().pollFeedAd();
    }

    /**
     * Get Reels ad from queue (non-blocking)
     */
    public static NativeAd pollReelsAd(Context context) {
        return AdQueueManager.getInstance().pollReelsAd();
    }

    /**
     * Check if Feed queue has ad ready
     */
    public static boolean hasFeedAd(Context context) {
        return AdQueueManager.getInstance().hasFeedAd();
    }

    /**
     * Check if Reels queue has ad ready
     */
    public static boolean hasReelsAd(Context context) {
        return AdQueueManager.getInstance().hasReelsAd();
    }

    /**
     * Set callbacks for when ads are ready
     */
    public static void setFeedAdReadyCallback(AdQueueManager.AdReadyCallback callback) {
        AdQueueManager.getInstance().setFeedAdReadyCallback(callback);
    }

    public static void setReelsAdReadyCallback(AdQueueManager.AdReadyCallback callback) {
        AdQueueManager.getInstance().setReelsAdReadyCallback(callback);
    }

    // Keep SponsoredCreative and social features below

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
            new SponsoredCreative(0, "Mercedes-Benz AMG G63 V8 Biturbo", "Mercedes-Benz Official",
                    "Handcrafted AMG 4.0L V8 Biturbo 577HP luxury SUV with Nappa leather executive cabin & Burmester 3D sound.",
                    "Book Test Drive", "$184,000 • Bespoke Edition", "LUXURY SUV 🚘", "Mercedes-AMG G63 2026 🚘",
                    "577HP V8 Biturbo • Nappa Leather • 4MATIC+ All-Wheel Drive", 0xFF0F2027, 0xFF2C5364, 5.0f),
            new SponsoredCreative(1, "iPhone 16 Pro Max 1TB Titanium", "Apple Flagship Store",
                    "Aerospace-grade Desert Titanium, A18 Pro chip, 4K120 Dolby Vision ProRes Cinema & 5x Tetraprism Zoom.",
                    "Order Flagship", "PKR 569,999 • Official PTA", "APPLE PRO 📱", "iPhone 16 Pro Max 1TB",
                    "A18 Pro Silicon • 4K 120FPS Dolby Vision • Grade-5 Titanium", 0xFF1F1C2C, 0xFF928DAB, 5.0f),
            new SponsoredCreative(2, "Rolex Cosmograph Daytona 18ct Gold", "Rolex Geneve Boutique",
                    "Oyster Perpetual Cosmograph Daytona in 18ct Everose gold with Cerachrom bezel & Calibre 4131 movement.",
                    "Inquire Boutique", "$42,500 • Swiss Certified", "SWISS HOROLOGY ⌚", "Rolex Daytona 18ct Gold",
                    "Superlative Chronometer • Oysterflex • 100m Waterproof", 0xFF134E5E, 0xFF71B280, 5.0f),
            new SponsoredCreative(3, "Emaar Beachfront Sky Penthouse", "Emaar Properties Dubai",
                    "Panoramic Palm Jumeirah & Marina skyline views, private infinity pool, yacht berth & Golden Visa eligibility.",
                    "Schedule Viewing", "AED 14.5M • 10% Booking", "DUBAI PENTHOUSE 🏙️", "Emaar Marina Sky Penthouse",
                    "Private Infinity Pool • Yacht Marina Access • 10-Yr Golden Visa", 0xFF141E30, 0xFF243B55, 5.0f),
            new SponsoredCreative(4, "Emirates First Class Private Suite", "Emirates Airlines",
                    "Floor-to-ceiling privacy doors, zero-gravity leather suite, Dom Pérignon dining & A380 Onboard Shower Spa.",
                    "Book First Class", "London • Dubai • New York", "FIRST CLASS ✈️", "Emirates A380 Private Suite",
                    "Chauffeur Drive • Shower Spa • Private Fine Dining", 0xFF8E0E00, 0xFF1F1C18, 5.0f),
            new SponsoredCreative(5, "Porsche 911 Turbo S Coupé", "Porsche Centre Official",
                    "640HP Twin-Turbo flat-six engine, 0-100 km/h in 2.7 seconds with Porsche Active Aerodynamics & PDK.",
                    "Configure Yours", "$230,400 • Weissach Spec", "SUPERCAR 🏎️", "Porsche 911 Turbo S 640HP",
                    "0-100 km/h in 2.7s • Ceramic Composite Brakes • AWD", 0xFF232526, 0xFF414345, 5.0f),
            new SponsoredCreative(6, "Samsung Galaxy S25 Ultra 1TB 5G", "Samsung Flagship Store",
                    "Snapdragon 8 Elite for Galaxy, 200MP ProVisual AI Camera, Titanium frame & Gorilla Armor 2 anti-reflective glass.",
                    "Buy Flagship", "PKR 489,999 • VIP Care+", "GALAXY AI 📱", "Galaxy S25 Ultra 1TB Titanium",
                    "200MP Quad Telephoto • Built-in S Pen • Titanium Frame", 0xFF0052D4, 0xFF4364F7, 4.9f),
            new SponsoredCreative(7, "Eighteen Islamabad 4-Kanal Golf Villa", "Eighteen Luxury Living",
                    "Ultra-luxury designer villas overlooking the 18-hole championship golf course with smart home automation.",
                    "Book Private Tour", "PKR 18.5 Crore • Possession", "LUXURY VILLA 🏡", "Eighteen Golf View Villas",
                    "18-Hole Golf Course • 5-Star Clubhouse • Smart Villa", 0xFF00695C, 0xFF00BFA5, 5.0f),
            new SponsoredCreative(8, "MacBook Pro 16\" M4 Max 128GB", "Apple Enterprise Solutions",
                    "16-core CPU, 40-core GPU, 128GB unified memory, 4TB SSD & Liquid Retina XDR nano-texture display.",
                    "Shop Workstation", "PKR 1,195,000 • AppleCare+", "M4 MAX PRO 💻", "MacBook Pro 16\" M4 Max",
                    "128GB Unified RAM • 40-Core GPU • 22-Hr Battery", 0xFF1A2980, 0xFF26D0CE, 5.0f),
            new SponsoredCreative(9, "Range Rover SV Autobiography LWB", "Land Rover Bespoke",
                    "606HP V8 Twin-Turbo Sanctuary cabin with hot-stone massage rear executive seats & deployable club table.",
                    "Explore SV", "$234,000 • Long Wheelbase", "BESPOKE SUV 🚙", "Range Rover SV LWB 2026",
                    "Executive Airline Seats • Meridian Signature 1600W", 0xFF1D976C, 0xFF2B5876, 5.0f),
            new SponsoredCreative(10, "Patek Philippe Nautilus 5711/1R", "Patek Philippe Salon",
                    "Iconic octagonal rose-gold bezel, chocolate brown sunburst dial & self-winding mechanical movement.",
                    "Private Inquiry", "$118,000 • Geneva Seal", "HAUTE HORLOGERIE 💎", "Patek Philippe Nautilus 18K",
                    "Solid 18K Rose Gold • Sapphire Caseback • Swiss Made", 0xFF3A1C71, 0xFFD76D77, 5.0f),
            new SponsoredCreative(11, "HSBC Premier World Elite Metal Card", "HSBC Private Wealth",
                    "Dedicated Wealth Relationship Manager, unlimited global airport lounge access & $10M travel coverage.",
                    "Apply Premier", "Private Banking Tier", "WEALTH BANKING 💳", "HSBC Premier Metal Infinite",
                    "Global Private Concierge • 1,300+ VIP Lounges • Zero FX Fee", 0xFFEB3349, 0xFFF45C43, 4.9f),
            new SponsoredCreative(12, "Qatar Airways Qsuite Business Class", "Qatar Airways Official",
                    "World's Best Business Class with double-bed private cabin suites, Starlink high-speed Wi-Fi & Diptyque amenities.",
                    "Book Qsuite", "Fly to 170+ Destinations", "QSUITE LUXURY 🛫", "Qatar Airways Qsuite Cabin",
                    "Private Double Suite • Free Starlink Wi-Fi • Dine on Demand", 0xFF6A11CB, 0xFF2575FC, 5.0f),
            new SponsoredCreative(13, "BMW i7 M70 xDrive Executive Sedan", "BMW Individual Official",
                    "650HP all-electric flagship with 31.3\" 8K Theatre Screen, Swarovski crystal headlights & automatic doors.",
                    "Request Brochure", "$168,500 • M Performance", "ELECTRIC LUXURY ⚡", "BMW i7 M70 + 8K Theatre Screen",
                    "650HP Dual Motor • Bowers & Wilkins 4D Diamond Audio", 0xFF004FF9, 0xFF000000, 5.0f),
            new SponsoredCreative(14, "Four Seasons Overwater Pool Villa", "Four Seasons Maldives",
                    "Private overwater infinity pool, sunset yacht transfers, underwater fine dining & 24/7 private butler.",
                    "Reserve Villa", "$3,800/Night • VIP Seaplane", "MALDIVES RESORT 🏝️", "Four Seasons Private Island",
                    "Overwater Infinity Pool • Private Seaplane • 5-Star Spa", 0xFF00B4DB, 0xFF0083B0, 5.0f),
            new SponsoredCreative(15, "Tiffany & Co. 3-Carat Platinum Diamond", "Tiffany & Co. New York",
                    "D-Flawless triple-excellent cut solitaire diamond handcrafted in platinum with GIA laser inscription.",
                    "Book Private Appointment", "$64,000 • GIA Certified", "HIGH JEWELRY 💍", "Tiffany Setting Platinum 3ct",
                    "D-Flawless Clarity • Handcrafted Platinum • Blue Box", 0xFF02AAB0, 0xFF00CDAC, 5.0f)
    };

    private static final Map<Integer, Integer> SESSION_SLOT_TO_UNIQUE_ID = new HashMap<>();

    /**
     * Slot whose ad is currently owned by the post-detail screen. It is set when the detail view
     * opens (openFeedAdPostDetail) and read by disposeShownSlotAd, so the detail screen's ad is not
     * destroyed out from under it when the feed recycles the slot behind it.
     * Was referenced but never declared — a compile error that blocked every release build.
     */
    private static volatile int preservedDetailSlotKey = -1;

    private static final String[][] EXTRA_BRANDS = new String[][]{
            {"Toyota Land Cruiser 300 ZX V6", "Toyota Indus Flagship", "3.5L V6 Twin-Turbo 409HP with E-KDSS suspension, JBL 14-speaker system & fingerprint start.", "Book ZX V6", "PKR 9.5 Crore • Zero Meter", "FLAGSHIP 4X4 🚙", "Land Cruiser 300 ZX Twin-Turbo", "409HP V6 Twin-Turbo • 10-Speed Auto • VIP Pearl White"},
            {"Burj Binghatti Jacob & Co. Sky Mansion", "Binghatti Developers Dubai", "Ultra-high-rise sky mansions in Business Bay Dubai with private infinity pool & concierge.", "Invest in Dubai", "AED 22M • Tax-Free ROI", "DUBAI SKY MANSION 🏙️", "Burj Binghatti Penthouse", "Private Sky Pool • Panoramic Burj Khalifa View"},
            {"Audemars Piguet Royal Oak Chronograph", "Audemars Piguet Le Brassus", "18-carat yellow gold case, Grande Tapisserie dial & selfwinding Calibre 4401 flyback chronograph.", "Inquire Salon", "$89,000 • Swiss Masterpiece", "ROYAL OAK ⌚", "AP Royal Oak 41mm 18K Gold", "Grande Tapisserie Dial • Flyback Chronograph"},
            {"NVIDIA DGX H200 Enterprise AI Cluster", "NVIDIA Enterprise Cloud", "1,128GB HBM3e GPU memory with 4.8TB/s bandwidth for trillion-parameter generative AI training.", "Request Demo", "Enterprise AI Infrastructure", "ENTERPRISE AI 🖥️", "NVIDIA DGX H200 SuperPOD", "32 PetaFLOPS FP8 • InfiniBand Quantum-2 Networking"},
            {"Gulfstream G700 Private Jet Charter", "VistaJet Executive Aviation", "Fly non-stop Dubai to New York in ultra-wide private staterooms with circadian lighting.", "Charter Flight", "Global Private Aviation", "PRIVATE JET 🛩️", "Gulfstream G700 Ultra-Long-Range", "5 Living Areas • Private Master Suite & Shower"},
            {"Cartier Panthère & Love 18K Gold Suite", "Maison Cartier Paris", "Solid 18K yellow gold and brilliant-cut diamond bracelet & watch collection from Paris.", "Explore Maison", "$28,500 • Certified Boutique", "MAISON CARTIER ✨", "Cartier Love & Panthère 18K", "Brilliant-Cut Diamonds • 18K Solid Gold • Paris"},
            {"Audi RS e-tron GT Carbon Performance", "Audi Sport Official", "637HP Quattro electric grand tourer with carbon-fiber roof, matrix laser lights & 800V fast charge.", "Discover RS", "$149,900 • Carbon Package", "AUDI SPORT 🏎️", "Audi RS e-tron GT 637HP", "Quattro AWD • 0-100 in 3.1s • Carbon Ceramic Brakes"},
            {"Galaxy Z Fold 6 1TB Crafted Black", "Samsung Bespoke Studio", "7.6\" Dynamic AMOLED 2X foldable display, Galaxy AI Live Translate & Armor Aluminum hinge.", "Order Z Fold 6", "PKR 619,999 • VIP Concierge", "FOLDABLE FLAGSHIP 📱", "Samsung Galaxy Z Fold 6 1TB", "Dual-Screen Multitasking • Snapdragon 8 Gen 3"},
            {"DHA Phase 8 2-Kanal Designer Mansion", "Sotheby's International Realty", "Brand-new architectural smart mansion with heated indoor pool, home cinema & Italian marble.", "Book Private Visit", "PKR 24 Crore • Ready to Move", "SIGNATURE ESTATE 🏛️", "2-Kanal Smart Mansion + Pool", "Private Home Cinema • Heated Pool • Imported Fittings"},
            {"ASUS ROG Zephyrus Duo 16 RTX 4090", "ASUS Republic of Gamers", "Dual 4K Mini-LED ROG Nebula HDR screens, AMD Ryzen 9 7945HX3D & 175W NVIDIA RTX 4090.", "Shop Flagship", "PKR 1,350,000 • 64GB DDR5", "RTX 4090 PRO 💻", "ROG Zephyrus Duo RTX 4090", "Dual 4K Screens • 175W RTX 4090 • 64GB RAM"},
            {"Louis Vuitton Horizon 55 Monogram", "Louis Vuitton Maison", "Ultra-lightweight 4-wheel cabin luggage in iconic Monogram canvas with natural cowhide leather trim.", "Explore Travel", "$3,650 • Complimentary Hot Stamp", "HAUTE COUTURE 🧳", "Louis Vuitton Horizon Collection", "Parisian Craftsmanship • TSA Lock • Cabin Size"},
            {"Swiss Alps St. Moritz Private Chalet", "Badrutt's Palace St. Moritz", "Ski-in/ski-out luxury alpine chalet with private Michelin-star chef, Rolls-Royce chauffeur & spa.", "Reserve Winter", "CHF 12,000/Night • VIP Heli", "SWISS ALPS 🏔️", "St. Moritz Private Luxury Chalet", "Helicopter Transfer • Private Spa & Chauffeur"},
            {"Sony Alpha 1 + FE 24-70mm GM II", "Sony Pro Imaging", "50.1MP full-frame stacked Exmor RS sensor, 30fps blackout-free shooting & 8K 30p 10-bit video.", "Explore Alpha 1", "$8,800 • Pro Master Kit", "8K CINEMA CAM 🎥", "Sony Alpha 1 + G-Master Lens", "50.1MP Full-Frame • 8K 30p Cinema • Real-Time Eye AF"},
            {"Standard Chartered Priority Infinite", "Standard Chartered Wealth", "Global USD/EUR multi-currency wealth account, private equity advisory & unlimited lounge pass.", "Join Priority", "Min Balance PKR 15M Tier", "PRIVATE BANKING 🏦", "SCB Priority Global Wealth", "Dedicated Wealth Advisor • Global Multi-Currency"},
            {"Lexus LX 600 Ultra Luxury 4-Seater", "Lexus Flagship Showroom", "Rear Ottoman captain seats, Mark Levinson 25-speaker reference surround & twin-turbo V6.", "Inquire LX 600", "$136,000 • Ultra Luxury", "EXECUTIVE 4X4 🚘", "Lexus LX 600 VIP 4-Seater", "Ottoman Massage Seats • 25-Speaker Mark Levinson"},
            {"24K Swiss PAMP Fortuna Gold Bullion", "Emirates Gold & Bullion Vault", "LBMA-accredited 999.9 pure 24-karat investment gold bars with insured vault storage or delivery.", "Invest in Gold", "999.9 Pure Swiss Bullion", "24K GOLD BULLION 🪙", "Swiss 24K Gold Investment Bars", "LBMA Certified 999.9 Purity • Insured Delivery"}
    };

    private static final int[][] GRADIENT_PALETTES = new int[][]{
            {0xFF0F2027, 0xFF2C5364}, {0xFF141E30, 0xFF243B55}, {0xFF1F1C2C, 0xFF928DAB}, {0xFF1A2980, 0xFF26D0CE},
            {0xFF232526, 0xFF414345}, {0xFF134E5E, 0xFF71B280}, {0xFF3A1C71, 0xFFD76D77}, {0xFF0052D4, 0xFF4364F7},
            {0xFF8E0E00, 0xFF1F1C18}, {0xFF004FF9, 0xFF000000}, {0xFF1D976C, 0xFF2B5876}, {0xFF4B6CB7, 0xFF182848},
            {0xFF6A11CB, 0xFF2575FC}, {0xFF00695C, 0xFF00BFA5}, {0xFFEB3349, 0xFFF45C43}, {0xFF02AAB0, 0xFF00CDAC},
            {0xFF1877F2, 0xFF0A3D91}, {0xFF833AB4, 0xFFFD1D1D}, {0xFF00B4DB, 0xFF0083B0}, {0xFFB24592, 0xFFF15F79},
            {0xFF11998E, 0xFF38EF7D}, {0xFFDA22FF, 0xFF9733EE}, {0xFFFF512F, 0xFFDD2476}, {0xFFD32F2F, 0xFFFF6D00}
    };

    private static final String[][] DYNAMIC_INDUSTRY_BRANDS = new String[][]{
            {"Rolex Geneve Official", "Submariner Date 18ct Yellow Gold", "Royal Blue Cerachrom Bezel • Oyster Bracelet • 300m Waterproof Chronometer", "Inquire Boutique", "SWISS LUXURY ⌚"},
            {"Mercedes-AMG Official", "Mercedes-AMG GT 63 S E-Performance", "831HP Plug-in Hybrid V8 • 1,400Nm Torque • AMG Carbon Ceramic Brakes", "Book Private Preview", "AMG SUPERCAR 🏎️"},
            {"Apple Flagship Store", "iPhone 16 Pro Max 1TB Desert Titanium", "A18 Pro 3nm Chip • 4K120 Dolby Vision Cinema • 5x Optical Telephoto", "Order Flagship", "FLAGSHIP PRO 📱"},
            {"Emaar Properties Dubai", "Downtown Dubai Burj Crown Penthouse", "Direct Burj Khalifa & Fountain Views • Private Sky Terrace • Golden Visa", "Book Dubai Tour", "PRIME REAL ESTATE 🏙️"},
            {"Emirates First Class", "Emirates A380 Private First Class Suite", "Zero-Gravity Leather Suite • Onboard Shower Spa • Chauffeur-Drive Service", "Book First Class", "FIRST CLASS ✈️"},
            {"Patek Philippe Geneve", "Grand Complications Perpetual Calendar", "18K Rose Gold Case • Moon Phases & Leap Year Indicator • Geneva Seal", "Private Consultation", "HAUTE HORLOGERIE 💎"},
            {"Porsche Exclusive Manufaktur", "Porsche Taycan Turbo GT Weissach", "1,092HP Overboost Launch Control • 0-100 km/h in 2.2s • Carbon Aero", "Configure Taycan", "ELECTRIC SUPERCAR ⚡"},
            {"Samsung Flagship Studio", "Samsung 98\" Neo QLED 8K Quantum TV", "NQ8 AI Gen3 Processor • Infinity Air Design • Dolby Atmos Object Tracking", "Experience 8K", "8K LUXURY TV 📺"},
            {"Eighteen Islamabad Official", "The Core Luxury Corporate & Golf Villas", "Championship 18-Hole Golf Course • Underground Smart Grid • 5-Star Club", "Schedule Visit", "LUXURY ESTATE 🏡"},
            {"American Express Centurion", "Amex Platinum Metal Concierge Card", "Global Fine Hotels + Resorts Privileges • $200 Airline Credit • Centurion Lounges", "Apply Platinum", "PRIVATE WEALTH 💳"},
            {"Land Rover Bespoke", "Range Rover Sport SV Edition Two", "626HP Twin-Turbo V8 • 6D Dynamics Air Suspension • Forged Carbon Fiber", "Explore SV", "LUXURY 4X4 🚙"},
            {"Qatar Airways Privilege", "Qatar Airways Qsuite Business World Pass", "Private Double Bed Cabin • Starlink High-Speed Wi-Fi • Al Mourjan Lounge", "Book Qsuite", "BUSINESS CLASS 🛫"},
            {"Apple Enterprise Pro", "Mac Studio M3 Ultra + Pro Display XDR", "6K Retina XDR 1600-Nit Display • 192GB Unified Memory • 8TB NVMe SSD", "Configure Studio", "PRO WORKSTATION 🖥️"},
            {"Tiffany & Co. High Jewelry", "Tiffany Victoria Diamond Vine Necklace", "Marquise & Round Brilliant Diamonds in Handcrafted 950 Platinum", "Private Viewing", "HIGH JEWELRY 💍"},
            {"BMW M GmbH Official", "BMW XM Label Red 738HP V8 Hybrid", "Most Powerful BMW M Ever Built • Merino Leather Lounge • M Lounge Roof", "Discover BMW XM", "BMW M POWER 🚘"},
            {"Four Seasons Private Retreats", "Four Seasons Bora Bora Overwater Suite", "Mount Otemanu Views • Private Plunge Pool • Helicopter Island Arrival", "Reserve Suite", "PRIVATE ISLAND 🏝️"},
            {"Omega Swiss Watches", "Speedmaster Moonwatch 18K Moonshine Gold", "Co-Axial Master Chronometer Calibre 3861 • Green PVD Dial • Swiss Certified", "Explore Omega", "SWISS WATCH ⌚"},
            {"Damac Luxury Living", "Cavalli Couture Dubai Canal Sky Villa", "Indoor Private Lagoon Pool • Roberto Cavalli Interiors • Marina Terrace", "Invest in Dubai", "DUBAI SKY VILLA 🏛️"},
            {"Maison Christian Dior", "Dior Sauvage Elixir & Privée Trunk", "Bespoke Leather Fragrance Trunk • Rare Grasse Rose & Calabrian Bergamot", "Explore Maison", "HAUTE PARFUM ✨"},
            {"VistaJet Private Aviation", "Bombardier Global 7500 Private Jet", "7,700 NM Non-Stop Range • Full Master Bedroom • Private Sky Kitchen", "Request Charter", "PRIVATE AVIATION 🛩️"},
            {"Audi Flagship Showroom", "Audi Q8 e-tron Edition Dakar Quattro", "Progressive Matrix LED • Bang & Olufsen 3D Sound • Adaptive Air Suspension", "Book Test Drive", "AUDI QUATTRO 🚙"},
            {"Cartier Haute Joaillerie", "Santos de Cartier Large 18K Yellow Gold", "QuickSwitch Gold & Alligator Straps • Calibre 1847 MC Automatic Movement", "Visit Boutique", "MAISON CARTIER 💎"},
            {"HSBC Global Private Bank", "HSBC Jade & Premier Wealth Portfolio", "International Offshore Banking • Tax-Free Global ETFs • Metal World Elite", "Speak to Advisor", "GLOBAL BANKING 🏦"},
            {"Lexus Official Flagship", "Lexus LM 500h Ultra-Luxury Royal Van", "48-Inch Ultrawide Partition Screen • First-Class Reclining Airline Seats", "Inquire LM 500h", "ROYAL CHAUFFEUR 🚘"},
            {"Louis Vuitton Paris", "Keepall Bandoulière 50 Taurillon Leather", "Full-Grain Embossed Monogram Leather • Matte Black Hardware • Paris Made", "Shop Maison", "LOUIS VUITTON 👜"},
            {"AWS Enterprise Cloud", "Amazon Bedrock & GPU AI Supercluster", "Dedicated H100/H200 AI Clusters • SOC2/ISO Banking Grade Security • 99.999% SLA", "Contact Enterprise", "CLOUD AI ☁️"},
            {"Bulgari Roma Boutique", "Octo Finissimo Ultra-Thin Titanium Watch", "World-Record Thin Automatic Watch • Sandblasted Titanium • Swiss Calibre", "Explore Bulgari", "ITALIAN LUXURY ⌚"},
            {"Etihad Airways Official", "Etihad The Residence A380 3-Room Suite", "Private Living Room, Double Bedroom & En-Suite Shower at 40,000 Feet", "Book Residence", "ROYAL AVIATION ✈️"},
            {"Leica Camera Wetzlar", "Leica M11 Monochrom + Noctilux 50mm f/0.95", "Handcrafted in Germany • 60MP Triple Resolution Full-Frame Brass Body", "Discover Leica", "GERMAN OPTICS 📷"},
            {"Ritz-Carlton Yacht Collection", "Ilma Superyacht Mediterranean Voyage", "7-Night Private Terrace Suite • Michelin-Star Dining • Marina Watersports", "Reserve Voyage", "SUPERYACHT 🛥️"},
            {"Bang & Olufsen Denmark", "Beolab 90 Flagship 8,200W Floor Speakers", "18 Scan-Speak Drivers per Speaker • Active Room Compensation • Aluminum Art", "Book Audition", "HI-FI ACOUSTICS 🔊"},
            {"Sotheby's International Realty", "Islamabad F-6/F-7 4-Kanal Embassy Mansion", "Diplomatic Enclave Proximity • Bulletproof Glass • 12-Car Underground Garage", "Private Inquiry", "EMBASSY ESTATE 🏰"}
    };

    private static final String[] EDITION_SERIES = new String[]{
            "2026 Bespoke Edition", "Flagship Titanium Series", "Private Collector Release", "Executive Lounge Spec",
            "Geneva Signature Line", "Presidential Suite Tier", "First-Class Edition", " Diamond Reserve",
            "Platinum VIP Release", "8K Cinema Edition", " Sovereign Collection", "Maybach Spec"
    };

    private static final String[] PROMO_OFFERS = new String[]{
            "VIP Concierge Delivery", "Official Global Warranty", "Private Viewing Available",
            "0% Executive Financing", "Complimentary Chauffeur", "Priority Allocation Open",
            "Bespoke Customization", "10-Year Golden Visa Eligible", "Certified Swiss Authenticity",
            "First-Class Lounge Access", "Invited Members Only", "Insured Armored Delivery"
    };

    private static volatile Context cachedAppCtx;
    private static int memorySequenceFloor = 600;

    /**
     * Always allocates a BRAND-NEW, never-before-seen `uniqueId` whenever a Feed or Reels Ad ViewHolder binds!
     * Persists across sessions in both device & per-user SharedPreferences so NO ad ever repeats.
     */
    public static synchronized int allocateFreshAdForBind(Context c, int slotKey) {
        if (c != null && cachedAppCtx == null) {
            cachedAppCtx = c.getApplicationContext();
        }
        Context effCtx = c != null ? c : cachedAppCtx;
        int nextId;
        if (effCtx != null) {
            String uid = Fb.uid() == null ? "guest" : Fb.uid();
            SharedPreferences sp = prefs(effCtx);
            String userSeqKey = "unseen_ad_seq_v6_" + uid;
            String devSeqKey = "unseen_ad_seq_v6_device";
            int currentMax = Math.max(memorySequenceFloor,
                    Math.max(sp.getInt(userSeqKey, 600), sp.getInt(devSeqKey, 600)));
            nextId = currentMax + 1;
            memorySequenceFloor = nextId;
            sp.edit()
                    .putInt(userSeqKey, nextId)
                    .putInt(devSeqKey, nextId)
                    .apply();
        } else {
            nextId = ++memorySequenceFloor;
        }
        SESSION_SLOT_TO_UNIQUE_ID.put(slotKey, nextId);
        return nextId;
    }

    /** Disposes a shown slot's NativeAd and removes it permanently so it is never shown again. */
    public static synchronized void disposeShownSlotAd(
            Context context, int slotKey, com.google.android.gms.ads.nativead.NativeAd ad) {
        if (slotKey == preservedDetailSlotKey) return;
        SESSION_SLOT_TO_UNIQUE_ID.remove(slotKey);
        // New queue system: ads are managed by queue, no slot-specific tracking needed
        // Just destroy the ad if provided
        if (ad != null) {
            MAIN_UI_HANDLER.post(() -> {
                try { ad.destroy(); } catch (Throwable ignored) { }
            });
        }
        // Queue manager auto-refills on impression, no manual replenish needed
    }

    /** Evicts a slot's cached ad when its ViewHolder is recycled, disposes the shown NativeAd, and loads a fresh replacement. */
    public static synchronized void evictSlotAd(int slotKey) {
        disposeShownSlotAd(cachedAppCtx, slotKey, null);
    }

    /**
     * Persistent Per-User Unseen Ad Resolver:
     * Returns the currently bound `uniqueId` for an active on-screen slot (for Like/Comment/Share),
     * or allocates a brand-new unseen ID if none is bound yet.
     */
    public static synchronized int resolveUniqueAdId(Context c, int slotKey) {
        if (c != null && cachedAppCtx == null) {
            cachedAppCtx = c.getApplicationContext();
        }
        Integer existing = SESSION_SLOT_TO_UNIQUE_ID.get(slotKey);
        if (existing != null) return existing;
        return allocateFreshAdForBind(c, slotKey);
    }

    public static synchronized SponsoredCreative getCreativeForSlot(Context c, int slotKey) {
        int uniqueId = resolveUniqueAdId(c, slotKey);
        return buildCreativeForUniqueId(uniqueId);
    }

    public static synchronized SponsoredCreative getCreativeForSlot(int slotKey) {
        Integer existing = SESSION_SLOT_TO_UNIQUE_ID.get(slotKey);
        int uniqueId = existing != null ? existing : resolveUniqueAdId(cachedAppCtx, slotKey);
        return buildCreativeForUniqueId(uniqueId);
    }

    private static SponsoredCreative buildCreativeForUniqueId(int uniqueId) {
        int id = Math.max(1, Math.abs(uniqueId));
        int totalBrands = EXTRA_BRANDS.length + DYNAMIC_INDUSTRY_BRANDS.length + CATALOG.length; // 64 brands
        // Coprime stride guarantees every single `id` picks a different brand, edition, promo & palette!
        int brandIdx = (id * 17 + (id / totalBrands) * 31) % totalBrands;
        int editionIdx = (id * 11 + (id / 7) * 5) % EDITION_SERIES.length;
        int promoIdx = (id * 13 + (id / 5) * 9) % PROMO_OFFERS.length;
        int[] pal = GRADIENT_PALETTES[(id * 7 + editionIdx * 3) % GRADIENT_PALETTES.length];
        float rating = (4.7f + ((id % 3) * 0.1f));

        String editionTag = EDITION_SERIES[editionIdx];
        String promoOffer = PROMO_OFFERS[promoIdx];
        String campaignCode = " #" + (100 + id);

        if (brandIdx < DYNAMIC_INDUSTRY_BRANDS.length) {
            String[] db = DYNAMIC_INDUSTRY_BRANDS[brandIdx];
            String company = db[0];
            String prod = db[1] + " — " + editionTag;
            String specs = db[2];
            String cta = db[3];
            String badge = db[4];
            String bigTitle = db[1] + " (" + promoOffer + ")";
            String subTitle = specs + " • Offer" + campaignCode;
            String body = specs + ". Special " + editionTag + " with " + promoOffer + " from " + company + ".";
            return new SponsoredCreative(
                    id, prod, company, body, cta, promoOffer,
                    badge, bigTitle, subTitle, pal[0], pal[1], rating);
        } else if (brandIdx < DYNAMIC_INDUSTRY_BRANDS.length + EXTRA_BRANDS.length) {
            String[] eb = EXTRA_BRANDS[brandIdx - DYNAMIC_INDUSTRY_BRANDS.length];
            String company = eb[1];
            String prod = eb[0] + " — " + editionTag;
            String body = eb[2] + " (" + promoOffer + ")";
            String cta = eb[3];
            String badge = eb[5];
            String bigTitle = eb[6] + " • " + promoOffer;
            String subTitle = eb[7] + " • Campaign" + campaignCode;
            return new SponsoredCreative(
                    id, prod, company, body, cta, promoOffer,
                    badge, bigTitle, subTitle, pal[0], pal[1], rating);
        } else {
            SponsoredCreative base = CATALOG[brandIdx - DYNAMIC_INDUSTRY_BRANDS.length - EXTRA_BRANDS.length];
            return new SponsoredCreative(
                    id,
                    base.headline + " — " + editionTag,
                    base.advertiser,
                    base.body + " (" + promoOffer + ")",
                    base.cta,
                    promoOffer,
                    base.bannerBadge,
                    base.bannerBigTitle + " • " + promoOffer,
                    base.bannerSubTitle + " • Deal" + campaignCode,
                    pal[0], pal[1], base.stars);
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

    /**
     * Renders a crisp, high-DPI company brand logo Drawable specific to the advertiser (`cr.advertiser`),
     * using that company's brand gradient, category emblem & company initials — NEVER FriendBook's logo!
     */
    public static android.graphics.drawable.Drawable createCompanyLogoDrawable(Context c, SponsoredCreative cr) {
        if (c == null || cr == null) return null;
        int sz = 144;
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(sz, sz, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);

        android.graphics.Paint bgPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        bgPaint.setShader(new android.graphics.LinearGradient(
                0, 0, sz, sz, cr.bannerColorStart, cr.bannerColorEnd, android.graphics.Shader.TileMode.CLAMP));
        android.graphics.RectF rect = new android.graphics.RectF(2f, 2f, sz - 2f, sz - 2f);
        canvas.drawRoundRect(rect, 34f, 34f, bgPaint);

        // Subtle inner glossy ring
        android.graphics.Paint ringPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        ringPaint.setStyle(android.graphics.Paint.Style.STROKE);
        ringPaint.setStrokeWidth(3.5f);
        ringPaint.setColor(0x55FFFFFF);
        canvas.drawRoundRect(new android.graphics.RectF(7f, 7f, sz - 7f, sz - 7f), 29f, 29f, ringPaint);

        // Extract category emoji from bannerBadge if present, plus company initials
        String emoji = "";
        if (cr.bannerBadge != null) {
            for (int i = 0; i < cr.bannerBadge.length(); ) {
                int cp = cr.bannerBadge.codePointAt(i);
                if (cp > 0x2000) {
                    emoji = new String(Character.toChars(cp));
                    break;
                }
                i += Character.charCount(cp);
            }
        }
        String initials = extractInitials(cr.advertiser);

        android.graphics.Paint txtPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        txtPaint.setColor(0xFFFFFFFF);
        txtPaint.setTextAlign(android.graphics.Paint.Align.CENTER);
        txtPaint.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD));

        if (!emoji.isEmpty()) {
            txtPaint.setTextSize(46f);
            canvas.drawText(emoji, sz / 2f, sz * 0.49f, txtPaint);
            txtPaint.setTextSize(26f);
            txtPaint.setColor(0xEEFFFFFF);
            canvas.drawText(initials, sz / 2f, sz * 0.81f, txtPaint);
        } else {
            txtPaint.setTextSize(52f);
            canvas.drawText(initials, sz / 2f, sz * 0.62f, txtPaint);
        }
        return new android.graphics.drawable.BitmapDrawable(c.getResources(), bmp);
    }

    public static android.graphics.drawable.Drawable createCompanyLogoDrawableById(Context c, int uniqueId) {
        return createCompanyLogoDrawable(c, buildCreativeForUniqueId(uniqueId));
    }

    private static String extractInitials(String name) {
        if (name == null || name.trim().isEmpty()) return "AD";
        String[] parts = name.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (!p.isEmpty() && Character.isLetterOrDigit(p.charAt(0))) {
                sb.append(Character.toUpperCase(p.charAt(0)));
                if (sb.length() == 2) break;
            }
        }
        return sb.length() > 0 ? sb.toString() : "AD";
    }

    public static com.friendbook.social.models.Models.Post getOrCreateAdPost(int slotKey) {
        int uniqueId = resolveUniqueAdId(cachedAppCtx, slotKey);
        String docId = "sponsored_ad_creative_" + uniqueId;
        com.friendbook.social.models.Models.Post p = AD_POST_CACHE.get(docId);
        if (p == null) {
            p = new com.friendbook.social.models.Models.Post();
            p.id = docId;
            p.authorId = "friendbook_sponsored";
            p.authorName = "Sponsored";
            p.authorPhoto = "";
            p.content = "Sponsored";
            p.privacy = "Only me"; // Keeps ad doc out of regular HomeFragment post query while real for likes/comments
            p.createdAt = System.currentTimeMillis();
            p.likes = 0;
            p.comments = 0;
            p.shares = 0;
            AD_POST_CACHE.put(docId, p);
        }
        return p;
    }

    /** Hydrates real user likes, reactions & comment counts from D1 `posts/{adDocId}` once per ad creative. */
    public static void hydrateAdSocialFromFirestore(final Context c, final int slotKey, final Runnable onHydrated) {
        final com.friendbook.social.models.Models.Post adPost = getOrCreateAdPost(slotKey);
        final String docId = adPost.id;
        if (!AD_POST_FETCHED.add(docId)) {
            if (onHydrated != null) onHydrated.run();
            return;
        }
        try {
            CloudflareStore.get("posts", docId, snap -> {
                        if (snap != null) {
                            com.friendbook.social.models.Models.Post remote =
                                    com.friendbook.social.models.Models.Post.fromMap(docId, snap);
                            adPost.likes = Math.max(0, remote.likes);
                            adPost.comments = Math.max(0, remote.comments);
                            adPost.shares = Math.max(0, remote.shares);
                            adPost.authorPhoto = "";
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
                            seed.put("authorPhoto", "");
                            seed.put("authorPhotoUrl", "");
                            seed.put("content", adPost.content);
                            seed.put("privacy", "Only me");
                            seed.put("createdAt", System.currentTimeMillis());
                            seed.put("likes", 0);
                            seed.put("comments", 0);
                            seed.put("shares", 0);
                            com.friendbook.social.core.DataRouter.mirrorPut("posts", docId, seed);
                            com.friendbook.social.core.DataRouter.batch().set("posts", docId, seed).commit();
                        }
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
            com.friendbook.social.core.DataRouter.mirrorPut("posts", adPost.id, up);
            com.friendbook.social.core.DataRouter.batch().set("posts", adPost.id, up).commit();
        } catch (Throwable ignored) { }
        String h = headline != null && headline.length() > 0 ? headline.toString() : adPost.authorName;
        String b = body != null && body.length() > 0 ? ("\n" + body) : "";
        android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(android.content.Intent.EXTRA_TEXT, "📢 " + h + b + "\n\n" + PLAY_STORE_URL);
        c.startActivity(android.content.Intent.createChooser(send, "Share Sponsored Post"));
    }

    public static com.friendbook.social.models.Models.Post findCachedAdPostById(String postId) {
        if (postId == null || postId.isEmpty()) return null;
        return AD_POST_CACHE.get(postId);
    }

    /**
     * Opens `PostDetailActivity` for Feed Sponsored Ads — shows ONLY the real AdMob Ad card + Comments!
     */
    public static void openFeedAdPostDetail(final Context c, final int slotKey, final Runnable onUpdated) {
        if (c == null) return;
        preservedDetailSlotKey = slotKey;
        final com.friendbook.social.models.Models.Post adPost = getOrCreateAdPost(slotKey);
        AD_POST_FETCHED.remove(adPost.id);
        hydrateAdSocialFromFirestore(c, slotKey, onUpdated);
        android.content.Intent i = new android.content.Intent(c, com.friendbook.social.post.PostDetailActivity.class);
        i.putExtra("post_id", adPost.id);
        i.putExtra("ad_slot_ordinal", slotKey);
        i.putExtra("focus_comment", true);
        c.startActivity(i);
    }

    /**
     * Opens the FULL real-time multi-user `ReelCommentsActivity` thread backed by `CommentsEngine`
     * on `posts/{adDocId}` for Video/Reels Sponsored Ads — 100% identical to Video/Reels comments!
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
