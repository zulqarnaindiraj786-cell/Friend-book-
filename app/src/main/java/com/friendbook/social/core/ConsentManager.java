package com.friendbook.social.core;

import android.app.Activity;
import android.content.Context;
import android.util.Log;

import com.google.android.gms.ads.MobileAds;
import com.google.android.ump.ConsentInformation;
import com.google.android.ump.ConsentRequestParameters;
import com.google.android.ump.UserMessagingPlatform;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 🛡️ Google UMP GDPR Consent + Production MobileAds Initializer (`com.friendbook.social`):
 * - Uses ONLY real production mode (zero debug geography, zero test device hashes, zero test ad mode).
 * - Guarantees `MobileAds.initialize(...)` runs cleanly so real Feed & Reels Native Ads load reliably.
 */
public final class ConsentManager {

    private static final AtomicBoolean mobileAdsInitStarted = new AtomicBoolean(false);
    private static volatile boolean mobileAdsReady = false;
    /** True only when `MobileAds.initialize(...)` itself has called back. */
    private static final AtomicBoolean sdkInitCompleted = new AtomicBoolean(false);
    private static final AtomicBoolean bootPreloadScheduled = new AtomicBoolean(false);
    /** Safety net: if Play Services never calls back, boot the ad pools anyway after 4s. */
    private static final long SDK_BOOT_PRELOAD_FALLBACK_MS = 4_000L;
    private static final android.os.Handler MAIN_HANDLER =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private static final java.util.List<Runnable> PENDING_READY_CALLBACKS =
            new java.util.ArrayList<>();
    private static final java.util.List<Runnable> SDK_READY_CALLBACKS =
            new java.util.ArrayList<>();

    private ConsentManager() {}

    /** Initializes Google Mobile Ads SDK immediately from Application.onCreate() or Activity startup. */
    public static void ensureInitialized(final Context context) {
        if (context == null) return;
        initializeMobileAdsOnce(context.getApplicationContext(), null);
    }

    /**
     * Runs `action` on the main thread only AFTER `MobileAds.initialize(...)` has completed,
     * preventing premature `AdLoader` requests from failing during cold start.
     */
    public static void runWhenInitialized(final Context context, final Runnable action) {
        if (action == null) return;
        if (mobileAdsReady) {
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                action.run();
            } else {
                MAIN_HANDLER.post(action);
            }
            return;
        }
        if (context == null) {
            MAIN_HANDLER.post(action);
            return;
        }
        initializeMobileAdsOnce(context.getApplicationContext(), action);
    }

    /** Gathers UMP GDPR consent (where required) and ensures MobileAds SDK is initialized. */
    public static void gatherConsentAndInitAds(final Activity activity, final Runnable onAdsReady) {
        if (activity == null || activity.isFinishing()) return;
        final Context appCtx = activity.getApplicationContext();

        // Always ensure MobileAds SDK is initialized so real ad requests are never blocked
        initializeMobileAdsOnce(appCtx, onAdsReady);

        try {
            ConsentRequestParameters params = new ConsentRequestParameters.Builder()
                    .setTagForUnderAgeOfConsent(false)
                    .build();

            final ConsentInformation consentInfo = UserMessagingPlatform.getConsentInformation(activity);
            consentInfo.requestConsentInfoUpdate(
                    activity,
                    params,
                    () -> {
                        if (activity.isFinishing() || activity.isDestroyed()) return;
                        UserMessagingPlatform.loadAndShowConsentFormIfRequired(
                                activity,
                                loadAndShowError -> {
                                    if (loadAndShowError != null) {
                                        Log.w(AdsConfig.LOG_TAG, "UMP consent form notice: code="
                                                + loadAndShowError.getErrorCode() + " msg="
                                                + loadAndShowError.getMessage());
                                    }
                                    AdsConfig.notifyChange();
                                    if (onAdsReady != null) onAdsReady.run();
                                }
                        );
                    },
                    requestConsentError -> Log.w(AdsConfig.LOG_TAG, "UMP consent update notice: code="
                            + requestConsentError.getErrorCode() + " msg="
                            + requestConsentError.getMessage())
            );
        } catch (Throwable t) {
            Log.w(AdsConfig.LOG_TAG, "UMP check skipped: " + t.getMessage());
        }
    }

    /** Returns true if EEA/UK GDPR privacy options entry point should be shown in Settings. */
    public static boolean isPrivacyOptionsRequired(Context context) {
        if (context == null) return false;
        try {
            ConsentInformation ci = UserMessagingPlatform.getConsentInformation(context);
            return ci.getPrivacyOptionsRequirementStatus()
                    == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Opens Google UMP Privacy Options form so EEA/UK users can update ad consent anytime. */
    public static void showPrivacyOptionsForm(Activity activity) {
        showPrivacyOptionsForm(activity, null);
    }

    /** Opens Google UMP Privacy Options form so EEA/UK users can update ad consent anytime. */
    public static void showPrivacyOptionsForm(Activity activity, Runnable onDone) {
        if (activity == null || activity.isFinishing()) return;
        try {
            UserMessagingPlatform.showPrivacyOptionsForm(activity, formError -> {
                if (onDone != null) onDone.run();
            });
        } catch (Throwable t) {
            if (onDone != null) onDone.run();
        }
    }

    public static boolean isAdsSdkReady() {
        return mobileAdsReady;
    }

    /** True only after `MobileAds.initialize(...)` itself called back (not the 900ms unblock). */
    public static boolean isAdsSdkInitialized() {
        return sdkInitCompleted.get();
    }

    /**
     * Runs `action` on the main thread once the ads SDK has REALLY finished initializing.
     *
     * Issuing the first ad request into a half-booted SDK is what made ads feel late: the request
     * either queues behind initialization or comes back as a cold-start no-fill, which then parked
     * the pool in the failure cooldown. This is guaranteed to run — the real callback flushes it,
     * and the 4s boot fallback flushes it too, so no ad slot can wait forever.
     */
    public static void runWhenSdkInitialized(final Context context, final Runnable action) {
        if (action == null) return;
        if (sdkInitCompleted.get()) {
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                action.run();
            } else {
                MAIN_HANDLER.post(action);
            }
            return;
        }
        synchronized (SDK_READY_CALLBACKS) {
            SDK_READY_CALLBACKS.add(action);
        }
        if (context != null) {
            final Context appCtx = context.getApplicationContext();
            initializeMobileAdsOnce(appCtx, null);
            scheduleBootPreload(appCtx);
        }
    }

    private static void flushSdkReadyCallbacks() {
        final java.util.List<Runnable> toRun;
        synchronized (SDK_READY_CALLBACKS) {
            if (SDK_READY_CALLBACKS.isEmpty()) return;
            toRun = new java.util.ArrayList<>(SDK_READY_CALLBACKS);
            SDK_READY_CALLBACKS.clear();
        }
        MAIN_HANDLER.post(() -> {
            for (Runnable r : toRun) {
                try { r.run(); } catch (Throwable ignored) { }
            }
        });
    }

    private static void initializeMobileAdsOnce(final Context appCtx, final Runnable onAdsReady) {
        if (onAdsReady != null) {
            synchronized (PENDING_READY_CALLBACKS) {
                if (!mobileAdsReady) {
                    PENDING_READY_CALLBACKS.add(onAdsReady);
                } else {
                    MAIN_HANDLER.post(onAdsReady);
                }
            }
        }
        if (mobileAdsInitStarted.compareAndSet(false, true)) {
            // Safety fallback: if Play Services takes > 900ms to fire callback, unblock pending ad requests immediately
            MAIN_HANDLER.postDelayed(() -> markReadyAndFlush(appCtx, false), 900L);
            // Initialize MobileAds on a dedicated thread to prevent UI thread ANR while booting AdMob fast
            Thread initThread = new Thread(() -> {
                try {
                    MobileAds.initialize(appCtx, initializationStatus -> {
                        Log.i(AdsConfig.LOG_TAG, "MobileAds SDK initialized (App ID: "
                                + AdsConfig.APP_ID + ", Feed: " + AdsConfig.FEED_NATIVE_ID
                                + ", Reels: " + AdsConfig.REELS_NATIVE_ID + ")");
                        markReadyAndFlush(appCtx, true);
                    });
                } catch (Throwable t) {
                    Log.e(AdsConfig.LOG_TAG, "MobileAds.initialize failed: " + t.getMessage(), t);
                    markReadyAndFlush(appCtx, true);
                }
            }, "admob-bg-init");
            initThread.setPriority(Thread.NORM_PRIORITY + 1);
            initThread.start();
        }
    }

    /**
     * Unblocks every waiter as soon as the SDK is usable, and starts the ad pools at the earliest
     * moment a request can actually be served.
     *
     * `sdkCompleted` is true only for the real `MobileAds.initialize` callback. Firing the very
     * first ad request before that point is what made ads feel late: the request either queues
     * behind initialization or comes back as a cold-start no-fill, which then parked the pool in
     * the failure cooldown. The 900ms fallback still flushes pending UI callbacks immediately, so
     * nothing ever hangs waiting for Play Services.
     */
    private static void markReadyAndFlush(final Context appCtx, final boolean sdkCompleted) {
        mobileAdsReady = true;
        if (sdkCompleted) sdkInitCompleted.set(true);
        java.util.List<Runnable> toRun;
        synchronized (PENDING_READY_CALLBACKS) {
            toRun = new java.util.ArrayList<>(PENDING_READY_CALLBACKS);
            PENDING_READY_CALLBACKS.clear();
        }
        MAIN_HANDLER.post(() -> {
            if (sdkInitCompleted.get()) {
                flushSdkReadyCallbacks();
                preloadAdPools(appCtx);
            } else {
                scheduleBootPreload(appCtx);
            }
            for (Runnable r : toRun) {
                try { r.run(); } catch (Throwable ignored) { }
            }
            AdsConfig.notifyChange();
        });
    }

    /** Kicks the new Facebook-style Ad Queue Manager (3 ads Feed + 3 ads Reels). Idempotent. */
    private static void preloadAdPools(final Context appCtx) {
        if (appCtx == null) return;
        try {
            AdsConfig.warmUpPools(appCtx);
        } catch (Throwable ignored) { }
    }

    /** One-shot fallback so ads still boot if the Play Services callback never arrives. */
    private static void scheduleBootPreload(final Context appCtx) {
        if (appCtx == null || !bootPreloadScheduled.compareAndSet(false, true)) return;
        MAIN_HANDLER.postDelayed(() -> {
            // Waited long enough: treat the SDK as usable so deferred ad requests can never be
            // stranded, then boot the pools.
            if (sdkInitCompleted.compareAndSet(false, true)) {
                flushSdkReadyCallbacks();
                preloadAdPools(appCtx);
            }
        }, SDK_BOOT_PRELOAD_FALLBACK_MS);
    }
}
