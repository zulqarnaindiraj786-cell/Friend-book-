"""Source contract guards: real view counters, counted in Worker memory + KV, never in Firestore.

Firebase is authentication only for views — the Worker verifies the ID token and owns the counter.
Counting rule (product decision): ONE view per user per resource, forever. One second of watching is
enough; watching the whole video, looping it or coming back next month adds nothing. The Home Feed
does no view-counter work at all - no request, no D1, no KV - so loading the feed costs nothing.

Storage budget (the reason this design exists): counting or serving a view must cost ZERO D1 row
reads. Live totals sit in Worker memory and in one Workers KV aggregate key; D1 receives at most one
write per resource per hour, and that write is a single upsert that also returns the stored total.
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
        # The flushes need the runtime context so storage work happens after the response is sent.
        self.assertIn('async fetch(request, env, ctx)', worker)
        self.assertIn('new URL(request.url), Date.now, ctx)', worker)

    def test_no_view_counter_is_written_to_firestore(self):
        for path in sorted(JAVA.rglob('*.java')):
            source = path.read_text()
            for forbidden in ['.update("views"', 'doc.put("views"', '"views", FieldValue.increment']:
                absent(self, forbidden, source, path.name)

    def test_view_path_costs_zero_d1_row_reads(self):
        worker = (CLOUDFLARE / 'view-counters.js').read_text()
        # The only SELECT statement allowed in the module is the one-time bounded cold seed
        # (the other SELECT mentions are comments explaining the budget).
        self.assertEqual(worker.count("'SELECT"), 1, 'exactly one SELECT literal may exist')
        self.assertEqual(worker.count('`SELECT'), 0, 'no template-literal SELECT may exist')
        self.assertIn('SELECT resource, views FROM friendbook_cf_view_counters ORDER BY views DESC LIMIT ?', worker)
        self.assertIn('const SEED_LIMIT = 500;', worker)
        self.assertIn('if (state.totals.size === 0 && env?.DB)', worker)
        # Live reads come from memory, refilled from KV once per isolate.
        self.assertIn("const KV_KEY = 'views:live:v1';", worker)
        self.assertIn("await env.VIEWS.get(KV_KEY, { type: 'json', cacheTtl: 60 })", worker)
        self.assertIn('if (state.loaded) return;', worker)
        self.assertIn('state.totals.get(resource) ?? 0', worker)
        # A per-view D1 read (the design that exhausted the free tier) must never come back.
        absent(self, 'SELECT views FROM friendbook_cf_view_counters WHERE resource', worker, 'view-counters.js')
        absent(self, 'withSession(', worker, 'view-counters.js')

    def test_totals_are_written_at_most_once_per_resource_per_hour(self):
        worker = (CLOUDFLARE / 'view-counters.js').read_text()
        self.assertIn('const D1_FLUSH_MS = 3_600_000;', worker)
        self.assertIn('state.dirty.has(resource)', worker)
        self.assertIn('now - (state.lastD1Flush.get(resource) ?? 0) >= D1_FLUSH_MS', worker)
        # One statement, no read-modify-write: the stored total can only go up, and RETURNING makes
        # D1 authoritative again without a SELECT.
        self.assertIn('ON CONFLICT(resource) DO UPDATE SET views = MAX(views, excluded.views)', worker)
        self.assertIn('RETURNING views', worker)
        absent(self, 'views = views + 1', worker, 'view-counters.js')
        absent(self, 'views + ?', worker, 'view-counters.js')
        # The previous design UPDATED the per-user stamp on every view and then read it back; the
        # de-dupe row may only ever be inserted, never updated or selected.
        absent(self, 'ON CONFLICT(resource, uid) DO UPDATE', worker, 'view-counters.js')
        absent(self, 'WHERE last_at <= ?', worker, 'view-counters.js')
    def test_kv_writes_stay_inside_the_free_tier_budget(self):
        worker = (CLOUDFLARE / 'view-counters.js').read_text()
        # One aggregate key, rewritten at most every five minutes: <= 288 writes/day per isolate vs
        # the 1,000/day Workers Free limit, and the throttle lives inside flushKv so no other code
        # path (for example the hourly D1 write) can add an unbudgeted KV write.
        self.assertIn('const KV_FLUSH_MS = 300_000;', worker)
        self.assertIn('if (!force && now - state.lastKvFlushAt < KV_FLUSH_MS) return false;', worker)
        self.assertIn('await flushKv(env, seededAt, true);', worker)
        self.assertIn('const KV_SNAPSHOT_LIMIT = 1000;', worker)
        self.assertIn('await env.VIEWS.put(KV_KEY, JSON.stringify(snapshot))', worker)
        # Without the binding the counters keep working from memory instead of failing the request.
        self.assertIn("storageMode = () => (state.kvUsable ? 'kv+memory' : 'memory')", worker)

    def test_storage_flushes_never_block_the_response(self):
        worker = (CLOUDFLARE / 'view-counters.js').read_text()
        self.assertIn("if (ctx && typeof ctx.waitUntil === 'function') ctx.waitUntil(all);", worker)
        self.assertIn('Promise.allSettled(tasks)', worker)

    def test_worker_counts_one_view_per_user_forever(self):
        worker = (CLOUDFLARE / 'view-counters.js').read_text()
        self.assertIn("const DEDUPE_RULE = 'once-per-user';", worker)
        self.assertIn('dedupe: DEDUPE_RULE', worker)
        self.assertIn('const seenKey = `${resource}|${uid}`;', worker)
        # Memory settles a repeat with no storage call at all.
        self.assertIn('if (state.counted.has(seenKey)) {', worker)
        # Durable de-dupe, checked by a WRITE: the insert itself reports whether the pair was new.
        self.assertIn('INSERT INTO friendbook_cf_view_last(resource, uid, last_at) VALUES (?, ?, ?)', worker)
        self.assertIn('ON CONFLICT(resource, uid) DO NOTHING', worker)
        self.assertIn('Number(result?.meta?.changes ?? 0) === 1', worker)
        self.assertIn('const MAX_COUNTED_ENTRIES = 8000;', worker)
        # A refused de-dupe write must never invent a view.
        self.assertIn('return null;', worker)
        self.assertIn("'Counter busy'", worker)
        # The one-second floor and a per-day bucket are both gone: neither is "one view per user".
        absent(self, 'MIN_VIEW_GAP_MS', worker, 'view-counters.js')
        absent(self, 'gapMs', worker, 'view-counters.js')
        absent(self, 'state.lastSeen', worker, 'view-counters.js')
        absent(self, "toISOString().slice(0, 10)", worker, 'view-counters.js')
    def test_kv_namespace_is_provisioned_by_ci_before_deploy(self):
        script = (CLOUDFLARE / 'ensure-views-kv.mjs').read_text()
        self.assertIn('friendbook-views', script)
        self.assertIn('/storage/kv/namespaces', script)
        self.assertIn('[[kv_namespaces]]', script)
        self.assertIn('binding = "VIEWS"', script)
        self.assertIn("const BINDING = 'VIEWS';", script)
        # The binding must land before the R2 anchor: the deploy fallback truncates wrangler.toml
        # there when the token has no R2 scope, and a binding after it would be silently dropped.
        self.assertIn('# Existing owner-provided bucket.', script)
        # A missing token or a refused API call must never fail the release: memory-only still works.
        self.assertIn('::warning::', script)
        workflow = (ROOT / '.github/workflows/deploy-cloudflare.yml').read_text()
        self.assertIn('node cloudflare/ensure-views-kv.mjs', workflow)
        self.assertLess(workflow.index('node cloudflare/ensure-views-kv.mjs'),
                        workflow.index('npm run deploy'), 'KV must be bound before the deploy')

    def test_reels_count_one_view_and_never_show_a_lower_number(self):
        client = (JAVA / 'core/CloudflareViews.java').read_text()
        self.assertIn('COUNTS.put(key, known != null ? Math.max(known.intValue(), views) : views);', client)
        reels = (JAVA / 'reels/ReelsFragment.java').read_text()
        # One second of real watching is enough to count this user's one view.
        self.assertIn('if (!viewCountedThisPlay && pos >= 1000)', reels)
        self.assertIn('countRealView();', reels)
        # ... and a loop, a re-watch or a restart never posts (or counts) a second one.
        self.assertIn('private static final java.util.Set<String> SESSION_COUNTED_VIEWS =', reels)
        self.assertIn('if (!SESSION_COUNTED_VIEWS.add(key)) return;', reels)
        # The displayed number only ever grows.
        self.assertIn('total -> viewedReel.views = Math.max(viewedReel.views, Math.max(0, total))', reels)
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

    def test_home_feed_does_zero_views_work(self):
        feed = (JAVA / 'post/FeedAdapter.java').read_text()
        home = (JAVA / 'main/HomeFragment.java').read_text()
        for name, source in (('FeedAdapter', feed), ('HomeFragment', home)):
            # No views client, no views endpoint: loading or scrolling the Home Feed must not cost a
            # D1 read, a D1 write, a KV read, a KV write or even an HTTP request.
            absent(self, 'CloudflareViews', source, name)
            absent(self, '/v1/views', source, name)
        # The eye/view chip is gone from the featured Reels card, not merely hidden.
        absent(self, 'firstPostReelViewsTv', feed, 'FeedAdapter')
        absent(self, 'refreshRealViewCounts', feed, 'FeedAdapter')
        # Post Insights still shows a real number: the post's own stored value, with no API call.
        self.assertIn('int viewCount = Math.max(0, p.views);', feed)
        # Everything else on the card is untouched - it still rotates to a fresh viral Reel.
        self.assertIn('private Models.Reel pickFeaturedReel()', feed)
        self.assertIn('public void setFeaturedReels(java.util.List<Models.Reel> reels)', feed)
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
