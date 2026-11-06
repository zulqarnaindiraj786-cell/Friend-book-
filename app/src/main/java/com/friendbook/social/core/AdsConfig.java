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
            new SponsoredCreative(0, "StreamDeck 4K Capture Pro", "Elgato Studio Gear",
                    "Broadcast in 4K60 HDR with ultra-low latency USB-C capture & customizable tactile keys.",
                    "Shop Gear", "Official Warranty", "PRO STREAM 🎥", "StreamDeck 4K60 Pro 🎥",
                    "4K60 HDR Capture • Tactile LCD Keys • Plug & Play", 0xFF1877F2, 0xFF0A3D91, 4.9f),
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

    private static final Map<Integer, Integer> SESSION_SLOT_TO_UNIQUE_ID = new HashMap<>();

    private static final String[][] EXTRA_BRANDS = new String[][]{
            {"AeroPods Max Pro", "SonicWave PK", "Spatial 3D audio, dual-mic ENC calling & 42-hour titanium case battery.", "Shop Audio", "Rs. 4,299 • Free COD", "3D AUDIO 🎧", "AeroPods Max Spatial", "42Hr Battery • Dual-Mic ENC • Type-C Fast Charge"},
            {"Biryani & Karahi Feast", "DesiDastarkhwan", "Authentic charcoal Chicken Karahi, 2x Sindhi Biryani, Raita & Naan for 4.", "Order Feast", "Flat 40% OFF", "FAMILY DEAL 🍛", "Royal Karahi & Biryani Box", "Hot 30-Min Delivery • Serves 4 Persons"},
            {"ProBook Air M3 Laptop", "TechVault Official", "Ultra-light 14\" FHD IPS display, 16GB RAM, 512GB NVMe SSD & 14hr battery.", "View Specs", "0% Easy Installments", "TECH DEAL 💻", "ProBook Air 16GB/512GB", "1-Year Official Warranty • Free Laptop Bag"},
            {"SolarMax 6kW Hybrid Kit", "GreenVolt Energy", "Cut electricity bills by 90% with Tier-1 A-grade panels & smart Wi-Fi inverter.", "Free Quote", "Net Metering Ready", "SOLAR PK ☀️", "Zero Electricity Bill Solution", "5-Year Inverter Warranty • Fast Installation"},
            {"Oud Royale Elixir 100ml", "Maison De Parfum", "Long-lasting 48-hour Arabian Oud, Amber & French Vanilla extrait de parfum.", "Order Perfume", "Buy 1 Get Tester Free", "LUXURY SCENT ✨", "Oud Royale 48Hr Extrait", "100% Pure Oil Concentration • Gift Box"},
            {"Velocity Nitro Running", "StridePro Athletics", "Ultra-responsive nitrogen-infused foam sole for marathon comfort & daily runs.", "Shop Shoes", "35% Season Sale", "PRO RUNNER 👟", "Velocity Nitro Sneakers", "Featherlight Mesh • High-Grip Carbon Outsole"},
            {"4K Gimbal Action Cam", "VloggerGear PK", "Waterproof 4K 60FPS action camera with 3-axis HorizonSteady stabilization.", "Grab Deal", "Free Tripod + Mic", "VLOG KIT 📹", "4K 60FPS Action Camera", "3-Axis Stabilization • Wireless Dual Mic"},
            {"Smart Inverter Split AC", "CoolBreeze Appliances", "Up to 75% energy saving T3 tropical compressor with 10-second turbo cooling.", "Check Price", "Free Copper Pipe Kit", "SUMMER SAVER ❄️", "T3 Smart Inverter AC", "75% Electricity Saving • 10-Year Compressor Warranty"},
            {"Istanbul & Cappadocia 7D", "SkyWings Holidays", "Return flights, 5-star hotel, Bosphorus cruise & hot-air balloon valley tour.", "Book Package", "Visa Assistance Included", "WORLD TOUR ✈️", "7 Days in Turkey", "Flights + 5-Star Hotel + Guided Tours"},
            {"Mechanical RGB Keyboard", "GearUp Esports", "Hot-swappable red linear switches, aircraft aluminum frame & per-key RGB.", "Buy Gaming Gear", "Rs. 5,999 Special", "ESPORTS PRO ⌨️", " Pro Mechanical RGB Keyboard", "Hot-Swap Switches • Zero Ghosting • Braided Cable"},
            {"Organic Honey & Dry Fruit", "PureValley Farms", "100% raw Sidr mountain honey with roasted almonds, walnuts & pistachios.", "Order Pure", "Lab Purity Certificate", "100% ORGANIC 🍯", "Raw Sidr Honey & Nuts Jar", "Direct From Northern Valleys • Zero Sugar Syrup"},
            {"IELTS & Spoken English", "GlobalSpeak Institute", "Band 8.0+ preparation with daily 1-on-1 speaking mocks & British Council trainers.", "Book Free Demo", "100% Score Guarantee", "STUDY ABROAD 🎓", "Score 8.0+ in IELTS", "Daily Live Speaking Mocks • Full Study Material"},
            {"InstaPrint Mini Printer", "GadgetBox PK", "Inkless thermal pocket printer for phone photos, study notes & sticky labels.", "Shop Gadget", "Free 5 Paper Rolls", "VIRAL GADGET 🖨️", "Pocket Inkless Photo Printer", "Bluetooth iOS & Android • Never Needs Ink"},
            {"Luxury Lawn 3-Piece '26", "ZariFabrics Official", "Digital printed Swiss lawn shirt, embroidered chiffon dupatta & dyed trouser.", "Shop Unstitched", "Flat 30% OFF", "EID COLLECTION 👗", "Luxury Embroidered Lawn '26", "100% Pure Swiss Lawn • Express Nationwide Shipping"},
            {"Car DashCam Dual 4K", "AutoGuard Tech", "Front 4K + Rear 1080p night-vision dash camera with 24hr parking GPS monitor.", "Order DashCam", "Free 64GB SD Card", "CAR SAFETY 🚗", "Dual 4K Night-Vision DashCam", "Sony Starvis Sensor • Wi-Fi App Live View"},
            {"Zero-Sugar Electrolytes", "HydraPeak Wellness", "Instant hydration packets with Magnesium, Potassium & Vitamin B12 for energy.", "Try Pack", "30 Sticks Box", "DAILY ENERGY ⚡", "HydraPeak Electrolyte Mix", "Zero Sugar •Refreshing Lemon & Berry Flavors"}
    };

    private static final int[][] GRADIENT_PALETTES = new int[][]{
            {0xFF1877F2, 0xFF0A3D91}, {0xFF6A11CB, 0xFF2575FC}, {0xFFD32F2F, 0xFFFF6D00}, {0xFF0F2027, 0xFF2C5364},
            {0xFF1F1C2C, 0xFF928DAB}, {0xFF00695C, 0xFF00BFA5}, {0xFF134E5E, 0xFF71B280}, {0xFF8E2DE2, 0xFF4A00E0},
            {0xFF0052D4, 0xFF4364F7}, {0xFF232526, 0xFF414345}, {0xFF1A2980, 0xFF26D0CE}, {0xFF3A1C71, 0xFFD76D77},
            {0xFFB24592, 0xFFF15F79}, {0xFF11998E, 0xFF38EF7D}, {0xFF141E30, 0xFF243B55}, {0xFF004FF9, 0xFF000000},
            {0xFF833AB4, 0xFFFD1D1D}, {0xFF00B4DB, 0xFF0083B0}, {0xFF1D976C, 0xFF2B5876}, {0xFFEB3349, 0xFFF45C43},
            {0xFF4B6CB7, 0xFF182848}, {0xFFDA22FF, 0xFF9733EE}, {0xFF02AAB0, 0xFF00CDAC}, {0xFFFF512F, 0xFFDD2476}
    };

    private static final String[][] DYNAMIC_INDUSTRY_BRANDS = new String[][]{
            {"NovaMobile PK", "5G AMOLED Smartphone", "120Hz Curved Display • 5000mAh • 67W Turbo Charge", "Shop Phone", "MOBILE 5G 📱"},
            {"BiteExpress Kitchen", "Double Smash Beef Burger", "2x Flame-Grilled Patties • Cheese Fries • Chilled Cola", "Order Food", "HOT MEAL 🍔"},
            {"CloudNet Fiber", "200Mbps FTTH Unlimited", "Dual-Band Wi-Fi 6 Router • Zero Throttling • Low Ping", "Get Fiber", "FIBER NET ⚡"},
            {"LuxeTime Co.", "Titanium Sapphire Chrono", "Automatic Movement • 50M Waterproof • Stainless Strap", "Buy Watch", "LUXURY ⌚"},
            {"ElectroMart Official", "65\" 4K QLED Smart Google TV", "Dolby Vision HDR10+ • 120Hz Gaming Mode • Voice Remote", "View TV", "SMART TV 📺"},
            {"AirWings Travel", "Dubai & Baku 6-Day Holiday", "Return Ticket • 4-Star Hotel • Desert Safari & Visa", "Book Trip", "TRAVEL ✈️"},
            {"UrbanThread Studio", "Premium Winter Hoodie Set", "400GSM Fleece • Oversized Fit • Free Nationwide COD", "Shop Drop", "FASHION 🧥"},
            {"GlowSkin Labs", "Niacinamide + HA Glass Serum", "Pore Minimizing • 72Hr Deep Hydration • Dermatologist Tested", "Claim Offer", "SKINCARE ✨"},
            {"PowerVolt Solar", "8kW Hybrid Solar System", "Tier-1 Bifacial Panels • IP65 Smart Inverter • Net Metering", "Get Quote", "SOLAR ☀️"},
            {"ApexGear Esports", "Wireless Pro Gaming Mouse", "26,000 DPI Optical Sensor • 54g Ultra-Light • 80Hr Battery", "Order Gear", "GAMING 🎮"},
            {"RoyalScent Arabia", "Black Oud & Saffron Extrait", "48-Hour Projection • Pure French Perfume Oils • Gift Box", "Shop Scent", "PERFUME 💎"},
            {"FitLab Nutrition", "100% Micronized Creatine + Whey", "30 Servings • Lab Tested Purity • Muscle Strength & Recovery", "Order Now", "FITNESS 💪"},
            {"SpeedyMart 15m", "Monthly Family Grocery Pack", "Cooking Oil, Basmati Rice, Tea & Dairy at Wholesale Rates", "Order Pack", "GROCERY 🛒"},
            {"AutoShine Pro", "Graphene 10H Paint Shield", "Hydrophobic Mirror Gloss • 2-Year UV & Scratch Protection", "Get Kit", "CAR CARE 🚘"},
            {"CodeMaster Academy", "AI Engineering & Cloud Bootcamp", "Live Zoom Mentorship • 6 Real Projects • Job Placement Help", "Join Cohort", "TECH COURSE 💻"},
            {"BrewHouse Roasters", "100% Arabica Cold Brew Pack", "Single-Origin Espresso Beans • Rich Crema • Freshly Roasted", "Order Coffee", "COFFEE ☕"},
            {"HomeComfort PK", "Orthopedic Memory Foam Mattress", "Cooling Gel Top • 10-Year Official Warranty • Free Delivery", "Check Size", "HOME 🛏️"},
            {"PayFast Digital", "Zero-Fee Platinum Debit Card", "Instant Raast Transfers • 20% Dining Discount • No Annual Fee", "Get Card", "FINTECH 💳"},
            {"KicksVault PK", "Air Cushion Running Sneakers", "Breathable Knit Upper • Shock-Absorbing Sole • 7-Day Exchange", "Shop Kicks", "SNEAKERS 👟"},
            {"LensCraft Pro", "4K Wireless Vlog Microphone", "AI Noise Cancellation • 200m Range • Plug & Play Type-C/iPhone", "Buy Mic", "CREATOR 🎙️"},
            {"DesiSpice Grill", "Full Mutton Mandi Platter", "Slow-Cooked Tender Mutton • Arabic Rice • 4 Sauces Included", "Order Platter", "DESI FEAST 🍖"},
            {"CoolTech Appliances", "No-Frost Double Door Fridge", "Digital Inverter • 55% Energy Saver • Vitamin Fresh Zone", "See Price", "APPLIANCE 🧊"},
            {"SmartHome Security", "360° PTZ Wi-Fi Night Camera", "2K Color Night Vision • Two-Way Audio • Instant Phone Alerts", "Order Cam", "SECURITY 📹"},
            {"VelvetLoom PK", "3-Piece Embroidered Chiffon", "Luxury Festive Collection • Pure Raw Silk Trouser • COD Available", "Shop Suit", "FESTIVE 👗"},
            {"RidePro Motors", "Electric Scooter 85km Range", "1500W Brushless Motor • Digital Dash • Zero Fuel Cost", "Book Ride", "EV RIDE 🛵"},
            {"BookHaven Store", "Bestselling Business 5-Book Box", "Atomic Habits + Psychology of Money + 3 Top Hardcovers", "Order Books", "BOOKS 📚"},
            {"DentalBright Clinic", "Ultrasonic Scaling & Whitening", "Painless 30-Min Laser Polish • Certified Dental Specialists", "Book Slot", "HEALTH 🦷"},
            {"PetCare Express", "Premium Grain-Free Cat & Dog Food", "Rich in Omega-3 & Taurine • Free Home Delivery Today", "Shop Pet", "PET CARE 🐾"},
            {"SoundBox Audio", "80W Portable Party Soundbar", "Deep Subwoofer Bass • RGB Beat Lights • Karaoke Wireless Mic", "Shop Speaker", "AUDIO 🔊"},
            {"Backpackers Gear", "Waterproof Anti-Theft Laptop Bag", "Fits 16\" MacBook • Built-in USB Port • Ergonomic Back Support", "Grab Bag", "TRAVEL BAG 🎒"},
            {"FreshBake Artisans", "Belgian Chocolate Fudge Cake", "2-Pound Freshly Baked • 45-Min Express Gift Delivery", "Send Cake", "BAKERY 🎂"},
            {"PrimeEstate PK", "LDA Approved 5-Marla Smart Plots", "2.5-Year Easy Monthly Plan • Gated Underground Electricity", "View Plan", "PROPERTY 🏡"}
    };

    private static final String[] EDITION_SERIES = new String[]{
            "2026 Pro Edition", "Ultra Series", "Max Titanium", "Prime Collection",
            "Signature Line", "Elite Pack", "Turbo Series", "Studio Edition",
            "Platinum Release", "NextGen v2", "Limited Drop", "Supreme Box"
    };

    private static final String[] PROMO_OFFERS = new String[]{
            "Flat 45% OFF Today", "Buy 1 Get 1 Free", "Rs. 1,000 Instant Off",
            "Free Express COD", "35% Flash Discount", "0% Easy Installments",
            "50% Launch Offer", "Free Gift Included", "Rs. 750 Voucher Inside",
            "40% Season Clearance", "VIP Member Price", "25% Cashback Deal"
    };

    private static volatile Context cachedAppCtx;
    private static int memorySequenceFloor = 200;

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
            String userSeqKey = "unseen_ad_seq_v5_" + uid;
            String devSeqKey = "unseen_ad_seq_v5_device";
            int currentMax = Math.max(memorySequenceFloor,
                    Math.max(sp.getInt(userSeqKey, 200), sp.getInt(devSeqKey, 200)));
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

    /** Evicts a slot's cached ad when its ViewHolder is recycled so scrolling back always loads a brand-new ad. */
    public static synchronized void evictSlotAd(int slotKey) {
        SESSION_SLOT_TO_UNIQUE_ID.remove(slotKey);
        if (slotKey >= 10000) {
            REELS_SLOT_ADS.remove(slotKey - 10000);
        } else {
            FEED_SLOT_ADS.remove(slotKey);
        }
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
        resolveUniqueAdId(context, slotOrdinal);
        com.google.android.gms.ads.nativead.NativeAd existing = FEED_SLOT_ADS.get(slotOrdinal);
        if (existing != null) return existing;
        // Strictly poll a fresh unseen AdMob NativeAd from FEED_POOL; NEVER reuse an already-shown NativeAd!
        com.google.android.gms.ads.nativead.NativeAd polled = FEED_POOL.pollFirst();
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
        resolveUniqueAdId(context, slotOrdinal + 10000);
        com.google.android.gms.ads.nativead.NativeAd existing = REELS_SLOT_ADS.get(slotOrdinal);
        if (existing != null) return existing;
        // Strictly poll a fresh unseen AdMob NativeAd from REELS_POOL; NEVER reuse an already-shown NativeAd!
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
        if (ad != null) {
            rememberReelsAd(ad);
            REELS_SLOT_ADS.put(slotOrdinal, ad);
        }
    }

    public static synchronized void clearFeedSlotAds(Context context) {
        FEED_SLOT_ADS.clear();
        SESSION_SLOT_TO_UNIQUE_ID.entrySet().removeIf(e -> e.getKey() < 10000);
        warmUpPools(context);
    }

    public static synchronized void clearReelsSlotAds(Context context) {
        REELS_SLOT_ADS.clear();
        SESSION_SLOT_TO_UNIQUE_ID.entrySet().removeIf(e -> e.getKey() >= 10000);
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
        SponsoredCreative cr = getCreativeForSlot(slotKey);
        String docId = "sponsored_ad_creative_" + cr.id;
        String companyLogoToken = "ad_company_logo:" + cr.id;
        com.friendbook.social.models.Models.Post p = AD_POST_CACHE.get(docId);
        if (p == null) {
            p = new com.friendbook.social.models.Models.Post();
            p.id = docId;
            p.authorId = "friendbook_sponsored";
            p.authorName = cr.advertiser;
            p.authorPhoto = companyLogoToken;
            p.content = cr.headline + " — " + cr.body;
            p.privacy = "Only me"; // Keeps ad doc out of regular HomeFragment post query while real for likes/comments
            p.createdAt = 1L;
            p.likes = 0;
            p.comments = 0;
            p.shares = 0;
            AD_POST_CACHE.put(docId, p);
        } else {
            p.authorName = cr.advertiser;
            p.authorPhoto = companyLogoToken;
        }
        return p;
    }

    /** Hydrates real user likes, reactions & comment counts from Firestore `posts/{adDocId}` once per ad creative. */
    public static void hydrateAdSocialFromFirestore(final Context c, final int slotKey, final Runnable onHydrated) {
        final com.friendbook.social.models.Models.Post adPost = getOrCreateAdPost(slotKey);
        final String docId = adPost.id;
        final String companyLogoToken = adPost.authorPhoto;
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
                            adPost.authorPhoto = companyLogoToken;
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
                            seed.put("authorPhoto", companyLogoToken);
                            seed.put("authorPhotoUrl", companyLogoToken);
                            seed.put("content", adPost.content);
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

    public static com.friendbook.social.models.Models.Post findCachedAdPostById(String postId) {
        if (postId == null || postId.isEmpty()) return null;
        return AD_POST_CACHE.get(postId);
    }

    /**
     * Opens `PostDetailActivity` for Feed Sponsored Ads — 100% identical to tapping Comment on a Feed Post!
     */
    public static void openFeedAdPostDetail(final Context c, final int slotKey, final Runnable onUpdated) {
        if (c == null) return;
        final com.friendbook.social.models.Models.Post adPost = getOrCreateAdPost(slotKey);
        AD_POST_FETCHED.remove(adPost.id);
        hydrateAdSocialFromFirestore(c, slotKey, onUpdated);
        android.content.Intent i = new android.content.Intent(c, com.friendbook.social.post.PostDetailActivity.class);
        i.putExtra("post_id", adPost.id);
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
