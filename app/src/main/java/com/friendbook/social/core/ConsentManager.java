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

    private ConsentManager() {}

    /** Initializes Google Mobile Ads SDK immediately from Application.onCreate() or Activity startup. */
    public static void ensureInitialized(final Context context) {
        if (context == null) return;
        initializeMobileAdsOnce(context.getApplicationContext(), null);
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

    private static void initializeMobileAdsOnce(final Context appCtx, final Runnable onAdsReady) {
        if (mobileAdsInitStarted.compareAndSet(false, true)) {
            try {
                MobileAds.initialize(appCtx, initializationStatus -> {
                    mobileAdsReady = true;
                    Log.i(AdsConfig.LOG_TAG, "MobileAds SDK initialized (App ID: "
                            + AdsConfig.APP_ID + ", Feed: " + AdsConfig.FEED_NATIVE_ID
                            + ", Reels: " + AdsConfig.REELS_NATIVE_ID + ")");
                    AdsConfig.notifyChange();
                    if (onAdsReady != null) onAdsReady.run();
                });
            } catch (Throwable t) {
                Log.e(AdsConfig.LOG_TAG, "MobileAds.initialize failed: " + t.getMessage(), t);
            }
        } else if (mobileAdsReady && onAdsReady != null) {
            onAdsReady.run();
        }
    }
}
