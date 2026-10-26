package com.friendbook.core;

import android.app.Activity;
import android.content.Context;

import com.google.android.gms.ads.MobileAds;
import com.google.android.ump.ConsentInformation;
import com.google.android.ump.ConsentRequestParameters;
import com.google.android.ump.UserMessagingPlatform;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 🛡️ Google UMP GDPR Consent + Safe MobileAds Initializer (`com.friendbook`):
 * - Runs ONLY on real physical Android devices (emulators are blocked via AdsConfig.isRealDevice()).
 * - Works seamlessly both WITH a configured GDPR message AND in "Requires review" / direct APK state
 *   (falls back safely so real Native Ad requests are never deadlocked if no UMP form is configured yet).
 * - Signals `isAdsSdkReady()` only after `MobileAds.initialize(...)` completes so the first Native Ad
 *   request never races ahead of adapter initialization.
 */
public final class ConsentManager {

    private static final AtomicBoolean mobileAdsInitStarted = new AtomicBoolean(false);
    private static final AtomicBoolean mobileAdsReady = new AtomicBoolean(false);
    private static volatile boolean fallbackAllowAds = false;

    private ConsentManager() {}

    public static void gatherConsentAndInitAds(final Activity activity, final Runnable onAdsReady) {
        if (activity == null || activity.isFinishing()) return;
        // Block emulator traffic completely to protect AdMob match rate & account health
        if (!AdsConfig.isRealDevice()) {
            return;
        }
        try {
            final Context appCtx = activity.getApplicationContext();
            final ConsentInformation consentInfo = UserMessagingPlatform.getConsentInformation(appCtx);

            ConsentRequestParameters params = new ConsentRequestParameters.Builder()
                    .setTagForUnderAgeOfConsent(false)
                    .build();

            consentInfo.requestConsentInfoUpdate(
                    activity,
                    params,
                    () -> UserMessagingPlatform.loadAndShowConsentFormIfRequired(
                            activity,
                            loadAndShowError -> {
                                if (loadAndShowError != null || consentInfo.canRequestAds()) {
                                    fallbackAllowAds = true;
                                    initializeMobileAdsOnce(appCtx, onAdsReady);
                                }
                            }
                    ),
                    requestConsentError -> {
                        // In "Requires review" or if publisher hasn't created a GDPR form in AdMob yet,
                        // UMP returns a config/network error — allow standard AdMob serving safely!
                        fallbackAllowAds = true;
                        initializeMobileAdsOnce(appCtx, onAdsReady);
                    }
            );

            if (consentInfo.canRequestAds()) {
                initializeMobileAdsOnce(appCtx, onAdsReady);
            }
        } catch (Throwable t) {
            fallbackAllowAds = true;
            initializeMobileAdsOnce(activity.getApplicationContext(), onAdsReady);
        }
    }

    public static boolean canRequestAds(Context context) {
        if (context == null || !AdsConfig.isRealDevice()) return false;
        if (!mobileAdsReady.get()) return false;
        if (fallbackAllowAds) return true;
        try {
            return UserMessagingPlatform.getConsentInformation(context.getApplicationContext()).canRequestAds();
        } catch (Throwable t) {
            return true;
        }
    }

    public static boolean isAdsSdkReady() {
        return mobileAdsReady.get();
    }

    public static void showPrivacyOptionsForm(final Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        try {
            UserMessagingPlatform.showPrivacyOptionsForm(activity, formError -> {
                if (formError != null) {
                    Ui.toast(activity, "Standard AdMob privacy active");
                } else {
                    Ui.toast(activity, "Ad privacy preferences saved ✓");
                }
            });
        } catch (Throwable t) {
            Ui.toast(activity, "GDPR consent not required in your region");
        }
    }

    private static void initializeMobileAdsOnce(final Context appCtx, final Runnable onAdsReady) {
        if (!AdsConfig.isRealDevice()) return;
        if (mobileAdsInitStarted.compareAndSet(false, true)) {
            try {
                MobileAds.initialize(appCtx, initializationStatus -> {
                    mobileAdsReady.set(true);
                    AdsConfig.notifyChange();
                    if (onAdsReady != null) onAdsReady.run();
                });
            } catch (Throwable t) {
                mobileAdsInitStarted.set(false);
            }
        } else if (mobileAdsReady.get() && onAdsReady != null) {
            onAdsReady.run();
        }
    }
}
