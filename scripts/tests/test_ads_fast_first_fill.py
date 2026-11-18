"""Source contract guards for fast first-fill with the queue architecture.

No retry tight loops, no premature SDK requests: the first ad request may only fire once the real
MobileAds init callback runs (or the bounded one-shot fallback flushes the waiters), and every
failure path is a single bounded refill, never a spin.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / 'app/src/main/java/com/friendbook/social'


class AdsFastFirstFillGuards(unittest.TestCase):
    def test_pools_boot_from_application_oncreate(self):
        app = (JAVA / 'App.java').read_text()
        self.assertIn('AdsConfig.warmUpPools(this)', app)
        self.assertIn('ConsentManager.ensureInitialized(this)', app)
        consent = (JAVA / 'core/ConsentManager.java').read_text()
        # The SDK-init handshake kicks the queue manager exactly once per process.
        self.assertIn('AdsConfig.warmUpPools(appCtx);', consent)

    def test_first_request_waits_for_the_real_sdk_initialization(self):
        ads = (JAVA / 'core/AdsConfig.java').read_text()
        queue = (JAVA / 'core/AdQueueManager.java').read_text()
        consent = (JAVA / 'core/ConsentManager.java').read_text()
        # AdQueueManager must NOT boot MobileAds itself — ConsentManager owns the single init.
        self.assertNotIn('import com.google.android.gms.ads.MobileAds;', queue)
        self.assertIn('ConsentManager.runWhenSdkInitialized(appContext, this::startPreloading)', queue)
        self.assertIn('public static boolean isAdsSdkInitialized()', consent)
        self.assertIn('private static final long SDK_BOOT_PRELOAD_FALLBACK_MS = 4_000L;', consent)
        self.assertIn('markReadyAndFlush(appCtx, true)', consent)
        # The 900ms unblock still exists so no UI waiter can hang, but it no longer fires ads early.
        self.assertIn('markReadyAndFlush(appCtx, false), 900L', consent)
        self.assertIn('flushSdkReadyCallbacks();', consent)
        self.assertIn('sdkInitCompleted.compareAndSet(false, true)', consent)

    def test_failure_recovery_is_bounded_not_a_tight_loop(self):
        queue = (JAVA / 'core/AdQueueManager.java').read_text()
        # Exactly one short bounded cooldown before the next single-ad attempt.
        self.assertIn('FAIL_COOLDOWN_MS = 5_000L;', queue)
        self.assertIn('mainHandler.postDelayed(() -> {', queue)
        # Loads are issued strictly one ad at a time, guarded by in-flight counters.
        self.assertIn('feedLoading.getAndSet(true)', queue)
        self.assertIn('reelsLoading.getAndSet(true)', queue)
        self.assertIn('feedInFlight.incrementAndGet()', queue)
        self.assertIn('reelsInFlight.incrementAndGet()', queue)

    def test_queue_refills_keep_three_ads_ready(self):
        queue = (JAVA / 'core/AdQueueManager.java').read_text()
        ads = (JAVA / 'core/AdsConfig.java').read_text()
        self.assertIn('private static final int QUEUE_CAPACITY = 3;', queue)
        self.assertIn('scheduleFeedRefill();', queue)
        self.assertIn('scheduleReelsRefill();', queue)
        self.assertIn('AdQueueManager.getInstance().initialize(context);', ads)
        self.assertIn('public static synchronized void warmUpPools(final Context context)', ads)
        self.assertIn('AdQueueManager.getInstance().getFeedQueueSize()', ads)
        self.assertIn('AdQueueManager.getInstance().getReelsQueueSize()', ads)


if __name__ == '__main__':
    unittest.main()
