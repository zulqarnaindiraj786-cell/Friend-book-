package com.friendbook.social.core;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdValue;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.ResponseInfo;
import com.google.android.gms.ads.nativead.NativeAd;
import com.google.android.gms.ads.nativead.NativeAdOptions;
import com.google.android.gms.ads.nativead.NativeAdView;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 🎯 Facebook-style Ad Queue Manager with Meta Mediation Support
 * 
 * FEED & REELS SEPARATE QUEUES:
 * - Each queue holds exactly 3 preloaded NativeAds
 * - App Start: Preload 3 ads for Feed, 3 ads for Reels
 * - Scroll: Show 1 ad every 3 posts/reels (pop from queue)
 * - Impression: Immediately load 1 new ad to refill queue (keep 3)
 * - Cache/Reuse: Never re-request ads in queue; reuse on scroll up/down
 * 
 * REFRESH RULE (Pull-to-Refresh):
 * - Does NOT clear queue
 * - Keeps unseen ads in queue
 * - Only fills empty slots (where impression occurred)
 * - Maintains 3 ads per queue at all times
 * 
 * META MEDIATION / WATERFALL:
 * - Meta Placement ID: 4871960679794940_4871960806461594
 * - AdMob Mediation handles waterfall server-side
 * - Logs which network served the ad: AdMob vs Meta
 */
public final class AdQueueManager {
    private static final String TAG = "AdQueueManager";
    
    // Queue size - Facebook keeps 3 ads ready
    private static final int QUEUE_CAPACITY = 3;
    
    // Ad unit IDs
    private static final String FEED_AD_UNIT_ID = AdsConfig.FEED_NATIVE_ID;
    private static final String REELS_AD_UNIT_ID = AdsConfig.REELS_NATIVE_ID;
    
    // Separate queues for Feed and Reels
    private final Queue<NativeAd> feedQueue = new ArrayDeque<>(QUEUE_CAPACITY);
    private final Queue<NativeAd> reelsQueue = new ArrayDeque<>(QUEUE_CAPACITY);
    
    // Loading state - load 1 ad at a time
    private final AtomicBoolean feedLoading = new AtomicBoolean(false);
    private final AtomicBoolean reelsLoading = new AtomicBoolean(false);
    
    // Track in-flight requests to prevent duplicate loads
    private final AtomicInteger feedInFlight = new AtomicInteger(0);
    private final AtomicInteger reelsInFlight = new AtomicInteger(0);
    
    // Context for ad loading
    private volatile Context appContext;
    
    // Handler for main thread
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    // Callbacks for when ad is ready
    private volatile AdReadyCallback feedCallback;
    private volatile AdReadyCallback reelsCallback;
    
    // Singleton instance
    private static volatile AdQueueManager INSTANCE;
    
    // Initialize flags
    private volatile boolean initialized = false;
    private volatile boolean feedQueueFull = false;
    private volatile boolean reelsQueueFull = false;
    
    // Cooldown after failure (short - 5 seconds for fast retry)
    private static final long FAIL_COOLDOWN_MS = 5_000L;
    private volatile long lastFeedFailTime = 0;
    private volatile long lastReelsFailTime = 0;
    
    // ============ PUBLIC INTERFACE ============
    
    public interface AdReadyCallback {
        void onAdReady(NativeAd ad, String adType); // adType: "feed" or "reels"
    }
    
    public interface AdConsumer {
        void onAdAvailable(NativeAd ad, String adType);
        void onNoAdAvailable(String adType);
    }
    
    private AdQueueManager() {}
    
    public static AdQueueManager getInstance() {
        if (INSTANCE == null) {
            synchronized (AdQueueManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new AdQueueManager();
                }
            }
        }
        return INSTANCE;
    }
    
    /**
     * Initialize and start preloading 3 ads for Feed + 3 for Reels
     * Call this on app startup (e.g., in Application.onCreate or MainActivity.onCreate)
     */
    public synchronized void initialize(Context context) {
        if (initialized) return;
        
        if (context == null) return;
        appContext = context.getApplicationContext();
        
        // Ensure MobileAds is initialized
        if (!MobileAds.getInitializationStatus().isInitialized()) {
            Log.w(TAG, "MobileAds not initialized yet, waiting...");
            MobileAds.initialize(appContext, initializationStatus -> {
                Log.d(TAG, "MobileAds initialized, starting queue preload");
                startPreloading();
            });
        } else {
            startPreloading();
        }
        
        initialized = true;
        Log.d(TAG, "AdQueueManager initialized - will preload " + QUEUE_CAPACITY + " ads per queue");
    }
    
    /**
     * Start preloading both queues
     */
    private void startPreloading() {
        if (appContext == null) return;
        
        // Preload Feed queue (3 ads)
        preloadFeedQueue();
        
        // Preload Reels queue (3 ads) 
        preloadReelsQueue();
    }
    
    // ============ FEED QUEUE OPERATIONS ============
    
    /**
     * Get next ad from Feed queue (call when binding ad slot)
     * Returns ad immediately if available, null if queue empty
     */
    public synchronized NativeAd pollFeedAd() {
        NativeAd ad = feedQueue.poll();
        if (ad != null) {
            Log.d(TAG, "Feed queue: popped ad, remaining=" + feedQueue.size());
            // Trigger refill to keep queue at 3
            if (feedQueue.size() < QUEUE_CAPACITY) {
                scheduleFeedRefill();
            }
            return ad;
        }
        
        // Queue empty - trigger emergency refill
        Log.w(TAG, "Feed queue EMPTY! Triggering emergency refill");
        scheduleFeedRefill();
        return null;
    }
    
    /**
     * Check if Feed queue has ad ready
     */
    public boolean hasFeedAd() {
        return !feedQueue.isEmpty();
    }
    
    /**
     * Get current Feed queue size (for debugging)
     */
    public int getFeedQueueSize() {
        return feedQueue.size();
    }
    
    /**
     * Set callback for when Feed ad is ready (for waiting slots)
     */
    public void setFeedAdReadyCallback(AdReadyCallback callback) {
        this.feedCallback = callback;
    }
    
    // ============ REELS QUEUE OPERATIONS ============
    
    /**
     * Get next ad from Reels queue
     */
    public synchronized NativeAd pollReelsAd() {
        NativeAd ad = reelsQueue.poll();
        if (ad != null) {
            Log.d(TAG, "Reels queue: popped ad, remaining=" + reelsQueue.size());
            if (reelsQueue.size() < QUEUE_CAPACITY) {
                scheduleReelsRefill();
            }
            return ad;
        }
        
        Log.w(TAG, "Reels queue EMPTY! Triggering emergency refill");
        scheduleReelsRefill();
        return null;
    }
    
    /**
     * Check if Reels queue has ad ready
     */
    public boolean hasReelsAd() {
        return !reelsQueue.isEmpty();
    }
    
    /**
     * Get current Reels queue size
     */
    public int getReelsQueueSize() {
        return reelsQueue.size();
    }
    
    /**
     * Set callback for when Reels ad is ready
     */
    public void setReelsAdReadyCallback(AdReadyCallback callback) {
        this.reelsCallback = callback;
    }
    
    // ============ IMPRESSION TRACKING (KEY FEATURE) ============
    
    /**
     * Call this when an ad gets an impression (user saw it)
     * Immediately loads 1 new ad to refill the queue
     */
    public void onFeedAdImpression() {
        Log.d(TAG, "Feed ad impression - refilling queue");
        if (feedQueue.size() < QUEUE_CAPACITY) {
            scheduleFeedRefill();
        }
    }
    
    public void onReelsAdImpression() {
        Log.d(TAG, "Reels ad impression - refilling queue");
        if (reelsQueue.size() < QUEUE_CAPACITY) {
            scheduleReelsRefill();
        }
    }
    
    // ============ REFRESH LOGIC (NEW) ============
    
    /**
     * Refresh queue on pull-to-refresh
     * Does NOT clear existing ads - only fills empty slots to maintain 3 ads
     * Preserves unseen ads, only refills slots where impression occurred
     */
    public void refreshQueue() {
        if (appContext == null || !AdsConfig.nativeAdsEnabled()) return;
        
        Log.d(TAG, "refreshQueue called - Feed: " + feedQueue.size() + "/3, Reels: " + reelsQueue.size() + "/3");
        
        // Only fill empty slots, don't clear existing ads
        if (feedQueue.size() < QUEUE_CAPACITY) {
            preloadFeedQueue();
        }
        if (reelsQueue.size() < QUEUE_CAPACITY) {
            preloadReelsQueue();
        }
    }
    
    // ============ PRELOADING LOGIC ============
    
    /**
     * Preload Feed queue to full capacity (3 ads)
     * Loads 1 ad at a time sequentially
     */
    private void preloadFeedQueue() {
        if (appContext == null || !AdsConfig.nativeAdsEnabled()) return;
        
        int needed = QUEUE_CAPACITY - feedQueue.size();
        if (needed <= 0) {
            feedQueueFull = true;
            return;
        }
        
        if (feedLoading.get() || feedInFlight.get() > 0) return;
        
        // Load 1 ad at a time
        loadSingleFeedAd();
    }
    
    /**
     * Preload Reels queue to full capacity (3 ads)
     */
    private void preloadReelsQueue() {
        if (appContext == null || !AdsConfig.nativeAdsEnabled()) return;
        
        int needed = QUEUE_CAPACITY - reelsQueue.size();
        if (needed <= 0) {
            reelsQueueFull = true;
            return;
        }
        
        if (reelsLoading.get() || reelsInFlight.get() > 0) return;
        
        loadSingleReelsAd();
    }
    
    /**
     * Schedule a refill (with small delay to batch)
     */
    private void scheduleFeedRefill() {
        if (feedQueue.size() >= QUEUE_CAPACITY) return;
        mainHandler.postDelayed(this::preloadFeedQueue, 100);
    }
    
    private void scheduleReelsRefill() {
        if (reelsQueue.size() >= QUEUE_CAPACITY) return;
        mainHandler.postDelayed(this::preloadReelsQueue, 100);
    }
    
    // ============ SINGLE AD LOADING ============
    
    /**
     * Load exactly 1 Feed ad using AdLoader
     */
    private void loadSingleFeedAd() {
        if (appContext == null || !AdsConfig.nativeAdsEnabled()) return;
        if (feedLoading.getAndSet(true)) return;
        
        feedInFlight.incrementAndGet();
        
        NativeAdOptions adOptions = new NativeAdOptions.Builder()
                .setAdChoicesPlacement(NativeAdOptions.ADCHOICES_TOP_RIGHT)
                .setMediaAspectRatio(NativeAdOptions.NATIVE_MEDIA_ASPECT_RATIO_ANY)
                .setVideoOptions(new com.google.android.gms.ads.VideoOptions.Builder()
                        .setStartMuted(true)
                        .build())
                .build();
        
        com.google.android.gms.ads.AdLoader adLoader = new com.google.android.gms.ads.AdLoader.Builder(appContext, FEED_AD_UNIT_ID)
                .forNativeAd(nativeAd -> {
                    feedInFlight.decrementAndGet();
                    feedLoading.set(false);
                    
                    if (nativeAd == null) {
                        Log.w(TAG, "Feed ad loaded but null");
                        scheduleFeedRefill();
                        return;
                    }
                    
                    // Log which network served the ad (AdMob vs Meta via Mediation)
                    logAdSource(nativeAd, "Feed");
                    
                    // Add to queue if not full
                    synchronized (feedQueue) {
                        if (feedQueue.size() < QUEUE_CAPACITY) {
                            feedQueue.offer(nativeAd);
                            Log.d(TAG, "Feed queue: added ad, size=" + feedQueue.size());
                            
                            // Notify waiting callback
                            if (feedCallback != null) {
                                mainHandler.post(() -> feedCallback.onAdReady(nativeAd, "feed"));
                            }
                        } else {
                            // Queue full - destroy ad to avoid memory leak
                            try { nativeAd.destroy(); } catch (Throwable ignored) {}
                            Log.d(TAG, "Feed queue full, discarding ad");
                        }
                    }
                    
                    // Continue loading until queue full
                    if (feedQueue.size() < QUEUE_CAPACITY) {
                        scheduleFeedRefill();
                    } else {
                        feedQueueFull = true;
                        Log.d(TAG, "Feed queue FULL (3/3)");
                    }
                })
                .withAdListener(new com.google.android.gms.ads.AdListener() {
                    @Override
                    public void onAdFailedToLoad(LoadAdError adError) {
                        feedInFlight.decrementAndGet();
                        feedLoading.set(false);
                        lastFeedFailTime = System.currentTimeMillis();
                        Log.w(TAG, "Feed ad load failed: " + adError.getCode() + " - " + adError.getMessage());
                        
                        // Short cooldown then retry
                        mainHandler.postDelayed(() -> {
                            if (feedQueue.size() < QUEUE_CAPACITY) {
                                preloadFeedQueue();
                            }
                        }, FAIL_COOLDOWN_MS);
                    }
                    
                    @Override
                    public void onAdImpression() {
                        // Ad was shown - trigger refill
                        onFeedAdImpression();
                    }
                })
                .withNativeAdOptions(new NativeAdOptions.Builder()
                        .setAdChoicesPlacement(NativeAdOptions.ADCHOICES_TOP_RIGHT)
                        .setMediaAspectRatio(NativeAdOptions.NATIVE_MEDIA_ASPECT_RATIO_ANY)
                        .setVideoOptions(new com.google.android.gms.ads.VideoOptions.Builder()
                                .setStartMuted(true)
                                .build())
                        .build())
                .build();
        
        // Load exactly 1 ad
        adLoader.loadAd(new AdRequest.Builder().build());
    }
    
    /**
     * Load exactly 1 Reels ad
     */
    private void loadSingleReelsAd() {
        if (appContext == null || !AdsConfig.nativeAdsEnabled()) return;
        if (reelsLoading.getAndSet(true)) return;
        
        reelsInFlight.incrementAndGet();
        
        NativeAdOptions adOptions = new NativeAdOptions.Builder()
                .setAdChoicesPlacement(NativeAdOptions.ADCHOICES_TOP_RIGHT)
                .setMediaAspectRatio(NativeAdOptions.NATIVE_MEDIA_ASPECT_RATIO_ANY)
                .setVideoOptions(new com.google.android.gms.ads.VideoOptions.Builder()
                        .setStartMuted(true) // Reels start muted
                        .build())
                .build();
        
        com.google.android.gms.ads.AdLoader adLoader = new com.google.android.gms.ads.AdLoader.Builder(appContext, REELS_AD_UNIT_ID)
                .forNativeAd(nativeAd -> {
                    reelsInFlight.decrementAndGet();
                    reelsLoading.set(false);
                    
                    if (nativeAd == null) {
                        Log.w(TAG, "Reels ad loaded but null");
                        scheduleReelsRefill();
                        return;
                    }
                    
                    // Log which network served the ad
                    logAdSource(nativeAd, "Reels");
                    
                    synchronized (reelsQueue) {
                        if (reelsQueue.size() < QUEUE_CAPACITY) {
                            reelsQueue.offer(nativeAd);
                            Log.d(TAG, "Reels queue: added ad, size=" + reelsQueue.size());
                            
                            if (reelsCallback != null) {
                                mainHandler.post(() -> reelsCallback.onAdReady(nativeAd, "reels"));
                            }
                        } else {
                            try { nativeAd.destroy(); } catch (Throwable ignored) {}
                            Log.d(TAG, "Reels queue full, discarding ad");
                        }
                    }
                    
                    if (reelsQueue.size() < QUEUE_CAPACITY) {
                        scheduleReelsRefill();
                    } else {
                        reelsQueueFull = true;
                        Log.d(TAG, "Reels queue FULL (3/3)");
                    }
                })
                .withAdListener(new com.google.android.gms.ads.AdListener() {
                    @Override
                    public void onAdFailedToLoad(LoadAdError adError) {
                        reelsInFlight.decrementAndGet();
                        reelsLoading.set(false);
                        lastReelsFailTime = System.currentTimeMillis();
                        Log.w(TAG, "Reels ad load failed: " + adError.getCode() + " - " + adError.getMessage());
                        
                        mainHandler.postDelayed(() -> {
                            if (reelsQueue.size() < QUEUE_CAPACITY) {
                                preloadReelsQueue();
                            }
                        }, FAIL_COOLDOWN_MS);
                    }
                    
                    @Override
                    public void onAdImpression() {
                        onReelsAdImpression();
                    }
                })
                .withNativeAdOptions(new NativeAdOptions.Builder()
                        .setAdChoicesPlacement(NativeAdOptions.ADCHOICES_TOP_RIGHT)
                        .setMediaAspectRatio(NativeAdOptions.NATIVE_MEDIA_ASPECT_RATIO_ANY)
                        .setVideoOptions(new com.google.android.gms.ads.VideoOptions.Builder()
                                .setStartMuted(true)
                                .build())
                        .build())
                .build();
        
        adLoader.loadAd(new AdRequest.Builder().build());
    }
    
    // ============ CLEANUP ============
    
    /**
     * Clear queues and destroy ads (call on logout/app background)
     */
    public void clearAll() {
        synchronized (feedQueue) {
            for (NativeAd ad : feedQueue) {
                try { ad.destroy(); } catch (Throwable ignored) {}
            }
            feedQueue.clear();
        }
        synchronized (reelsQueue) {
            for (NativeAd ad : reelsQueue) {
                try { ad.destroy(); } catch (Throwable ignored) {}
            }
            reelsQueue.clear();
        }
        feedQueueFull = false;
        reelsQueueFull = false;
        feedLoading.set(false);
        reelsLoading.set(false);
        feedInFlight.set(0);
        reelsInFlight.set(0);
        Log.d(TAG, "All ad queues cleared");
    }
    
    /**
     * Get queue status for debugging
     */
    public String getStatus() {
        return String.format("Feed: %d/3 %s | Reels: %d/3 %s | FeedLoading: %s | ReelsLoading: %s",
                feedQueue.size(), feedQueueFull ? "FULL" : "",
                reelsQueue.size(), reelsQueueFull ? "FULL" : "",
                feedLoading.get(), reelsLoading.get());
    }
    
    /**
     * Warm up - start preloading if not already
     */
    public void warmUp() {
        if (!initialized) return;
        if (feedQueue.size() < QUEUE_CAPACITY) preloadFeedQueue();
        if (reelsQueue.size() < QUEUE_CAPACITY) preloadReelsQueue();
    }
    
    /**
     * Log which ad network served the ad (AdMob vs Meta via Mediation)
     * Uses the response info from the loaded ad
     */
    private void logAdSource(NativeAd nativeAd, String adType) {
        try {
            ResponseInfo responseInfo = nativeAd.getResponseInfo();
            if (responseInfo != null) {
                String adapterClassName = responseInfo.getMediationAdapterClassName();
                String network = "AdMob";
                if (adapterClassName != null && !adapterClassName.isEmpty()) {
                    if (adapterClassName.toLowerCase().contains("facebook") || 
                        adapterClassName.toLowerCase().contains("meta")) {
                        network = "Meta";
                    } else {
                        network = "Mediation: " + adapterClassName;
                    }
                }
                Log.i(TAG, adType + " ad loaded from: " + network + " (adapter: " + adapterClassName + ")");
            } else {
                Log.i(TAG, adType + " ad loaded from: AdMob (direct)");
            }
        } catch (Throwable t) {
            Log.w(TAG, "Could not determine ad source: " + t.getMessage());
        }
    }
}