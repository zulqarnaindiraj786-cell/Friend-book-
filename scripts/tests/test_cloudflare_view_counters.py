"""Source contract guards: real view counters live in Cloudflare D1, never in Firestore.

Firebase is authentication only for views — the Worker verifies the ID token and stores the
counter in D1. These guards keep that split from silently regressing.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / 'app/src/main/java/com/friendbook/social'
CLOUDFLARE = ROOT / 'cloudflare'


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
                self.assertNotIn(forbidden, source, path.name)

    def test_reels_count_views_through_the_worker(self):
        source = (JAVA / 'reels/ReelsFragment.java').read_text()
        self.assertIn('CloudflareViews.recordView(', source)
        self.assertIn('sessionViewedReelIds.add(reel.id)', source)
        self.assertIn('total -> viewedReel.views = Math.max(0, total)', source)

    def test_feed_reads_real_totals_from_cloudflare(self):
        source = (JAVA / 'post/FeedAdapter.java').read_text()
        self.assertIn('private void refreshRealViewCounts()', source)
        self.assertIn('CloudflareViews.fetchViews(collection, entry.getValue()', source)
        self.assertIn('CloudflareViews.cached(', source)
        self.assertIn('CloudflareViews.POSTS', source)

    def test_views_client_is_enabled_and_never_touches_firestore(self):
        gradle = (ROOT / 'app/build.gradle').read_text()
        self.assertIn("buildConfigField 'boolean', 'CLOUDFLARE_VIEWS_ENABLED', 'true'", gradle)
        client = (JAVA / 'core/CloudflareViews.java').read_text()
        self.assertIn('/v1/views', client)
        self.assertIn('BuildConfig.CLOUDFLARE_VIEWS_ENABLED', client)
        self.assertIn('RECORDED.add(key)', client)
        self.assertNotIn('com.google.firebase.firestore', client)
        self.assertNotIn('addSnapshotListener', client)

    def test_view_migration_is_additive_only(self):
        sql = (CLOUDFLARE / 'migrations/0012_additive_view_counters.sql').read_text().upper()
        for token in ['CREATE TABLE IF NOT EXISTS FRIENDBOOK_CF_VIEW_COUNTERS',
                      'CREATE TABLE IF NOT EXISTS FRIENDBOOK_CF_VIEW_DEDUPE',
                      'CREATE INDEX IF NOT EXISTS IDX_FRIENDBOOK_CF_VIEW_COUNTERS_VIEWS']:
            self.assertIn(token, sql)
        for forbidden in ['DROP ', 'DELETE ', 'UPDATE ', 'ALTER ', 'TRUNCATE']:
            self.assertNotIn(forbidden, sql)


if __name__ == '__main__':
    unittest.main()
