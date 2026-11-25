from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
CORE = ROOT / 'app/src/main/java/com/friendbook/social/core'


class AdSocialReadGateWiring(unittest.TestCase):
    def test_on_bind_failures_are_gated_and_account_scoped(self):
        source = (CORE / 'AdsConfig.java').read_text()
        hydrate = source.split('private static void hydrateAdSocial(', 1)[1].split('public static ', 1)[0]
        self.assertIn('AD_SOCIAL_READS.begin(actor, docId', hydrate)
        self.assertEqual(hydrate.count('AD_SOCIAL_READS.finish(Fb.uid(), attempt,'), 3)
        self.assertIn('Code.PERMISSION_DENIED', hydrate)
        self.assertNotIn('AD_POST_FETCHED', source)
        self.assertIn('if (AD_SOCIAL_READS.scope(Fb.uid())) AD_POST_CACHE.clear()', source)
        self.assertIn('hydrateAdSocial(c, slotKey, onHydrated, false)', source)
        self.assertEqual(source.count('hydrateAdSocial(c, slotKey, onUpdated, true)'), 2)
        # Genuine missing-parent behavior was not removed to manufacture savings.
        self.assertIn('document(docId).set(seed, SetOptions.merge())', hydrate)
        gate = (CORE / 'AdSocialReadGate.java').read_text()
        self.assertNotIn('postDelayed', gate)
        self.assertNotIn('schedule', gate)
