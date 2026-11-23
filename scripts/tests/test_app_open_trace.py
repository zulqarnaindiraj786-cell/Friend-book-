from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
CORE = ROOT / 'app/src/main/java/com/friendbook/social/core'


class AppOpenTraceWiring(unittest.TestCase):
    def test_explicit_debug_opt_in_and_network_attempt_boundary(self):
        activity = (ROOT / 'app/src/main/java/com/friendbook/social/SplashActivity.java').read_text()
        self.assertIn('BuildConfig.DEBUG && getIntent().getBooleanExtra("quota_trace", false)', activity)
        trace = (CORE / 'CloudflareRequestTrace.java').read_text()
        self.assertEqual(trace.count('if (!BuildConfig.DEBUG) return;'), 2)
        self.assertIn('COUNTER.begin', trace)
        self.assertNotIn('Authorization', trace)
        self.assertNotIn('request.body', trace)
        self.assertNotIn('request.header', trace)
        self.assertNotIn('newCall', trace)
        http = (CORE / 'CloudflareHttp.java').read_text()
        hook = http.split('.addNetworkInterceptor(chain -> {', 1)[1].split('})', 1)[0]
        self.assertLess(hook.index('CloudflareRequestTrace.record'), hook.index('chain.proceed'))
