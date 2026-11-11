"""Source contract guards: real view counters live in Cloudflare D1, never in Firestore.

Firebase is authentication only for views — the Worker verifies the ID token and stores the
counter in D1. Counting rule (product decision): one second of real watching = one view, and the
same user watching again counts again; the Worker floors it to one counted view per user per second.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / 'app/src/main/java/com/friendbook/social'
CLOUDFLARE = ROOT / 'cloudflare'


def absent(test, token, source, where):
    test.assertFalse(token in source, '%s must not contain %s' % (where, token))


class CloudflareViewCounterGuards(unittest.TestCase):
    def test_worker_exposes_the_views_route_behind_the_token_gate(self):
        worker = (ROOT / 'worker.js').read_text()
        self.assertIn("import { viewCounters } from './cloudflare/view-counters.js';", worker)
        self.assertIn(r'/^\/v1\/views\/(posts|reels)\/([^/]+)$/', worker)
        # /v1/views is part of the authenticated allowlist, never an unauthenticated shortcut.
        self.assertIn('!docs && !views', worker)
        self.assertIn('if (views) return viewCounters(request, env, { uid, token: bearer[1] }', worker)

    def test_no_view_counter_is_written_to_firestore(self):
        for path in sorted(JAVA.rglob('*.java')):
            source = path.read_text()
            for forbidden in ['.update("views"', 'doc.put("views"', '"views", FieldValue.increment']:
                absent(self, forbidden, source, path.name)

    def test_worker_counts_every_rewatch_with_a_one_second_floor(self):
        worker = (CLOUDFLARE / 'view-counters.js').read_text()
        self.assertIn('const MIN_VIEW_GAP_MS = 1000;', worker)
        self.assertIn('friendbook_cf_view_last', worker)
        self.assertIn('ON CONFLICT(resource, uid) DO UPDATE SET last_at = excluded.last_at', worker)
        self.assertIn('WHERE last_at <= ?', worker)
        self.assertIn('views = views + 1', worker)
        self.assertIn('gapMs: MIN_VIEW_GAP_MS', worker)
        # A per-day bucket would silently swallow re-watches, which the product must count.
        absent(self, "toISOString().slice(0, 10)", worker, 'view-counters.js')

    def test_reels_count_a_view_after_one_second_and_on_every_loop(self):
        source = (JAVA / 'reels/ReelsFragment.java').read_text()
        self.assertIn('if (!viewCountedThisPlay && pos >= 1000)', source)
        self.assertIn('countRealView();', source)
        self.assertIn('CloudflareViews.recordView(', source)
        # A native loop or the trim window rewinds the position: that is a new real watch.
        self.assertIn('if (pos + 400 < lastTickPos)', source)
        # Starting from the beginning re-arms the counter; resuming after a pause does not.
        self.assertIn('if (activeMp.getCurrentPosition() < 600)', source)
        # The old "one view per Reel per app session" de-dupe is gone on purpose.
        absent(self, 'sessionViewedReelIds', source, 'ReelsFragment')

    def test_feed_reads_real_totals_from_cloudflare(self):
        source = (JAVA / 'post/FeedAdapter.java').read_text()
        self.assertIn('private void refreshRealViewCounts()', source)
        self.assertIn('CloudflareViews.fetchViews(collection, entry.getValue()', source)
        self.assertIn('CloudflareViews.cached(', source)
        self.assertIn('CloudflareViews.POSTS', source)

    def test_views_client_counts_repeats_and_never_touches_firestore(self):
        gradle = (ROOT / 'app/build.gradle').read_text()
        self.assertIn("buildConfigField 'boolean', 'CLOUDFLARE_VIEWS_ENABLED', 'true'", gradle)
        client = (JAVA / 'core/CloudflareViews.java').read_text()
        self.assertIn('/v1/views', client)
        self.assertIn('BuildConfig.CLOUDFLARE_VIEWS_ENABLED', client)
        # Only an in-flight guard: two racing callbacks collapse, re-watches still count.
        self.assertIn('POSTING.add(key)', client)
        self.assertIn('POSTING.remove(key)', client)
        absent(self, 'RECORDED', client, 'CloudflareViews')
        absent(self, 'com.google.firebase.firestore', client, 'CloudflareViews')
        absent(self, 'addSnapshotListener', client, 'CloudflareViews')

    def test_view_migrations_are_additive_only(self):
        for name in ['0012_additive_view_counters.sql', '0013_additive_view_last_seen.sql']:
            sql = (CLOUDFLARE / 'migrations' / name).read_text().upper()
            self.assertIn('CREATE TABLE IF NOT EXISTS', sql)
            for forbidden in ['DROP ', 'DELETE ', 'UPDATE ', 'ALTER ', 'TRUNCATE']:
                absent(self, forbidden, sql, name)
        self.assertIn('FRIENDBOOK_CF_VIEW_COUNTERS',
                      (CLOUDFLARE / 'migrations/0012_additive_view_counters.sql').read_text().upper())
        self.assertIn('FRIENDBOOK_CF_VIEW_LAST',
                      (CLOUDFLARE / 'migrations/0013_additive_view_last_seen.sql').read_text().upper())


if __name__ == '__main__':
    unittest.main()
