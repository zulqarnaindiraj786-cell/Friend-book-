"""Source contract guards: real view counters, counted in Worker memory + KV, never in Firestore.

Firebase is authentication only for views — the Worker verifies the ID token and owns the counter.
Counting rule (product decision): one second of real watching = one view, and the same user watching
again counts again; the Worker floors it to one counted view per (resource, uid) per second, in memory.

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

    def test_d1_is_written_at_most_once_per_resource_per_hour(self):
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
        # The per-user D1 stamp of the previous design cost a read and a write on every single view.
        absent(self, 'ON CONFLICT(resource, uid)', worker, 'view-counters.js')
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

    def test_worker_counts_every_rewatch_with_a_one_second_floor(self):
        worker = (CLOUDFLARE / 'view-counters.js').read_text()
        self.assertIn('const MIN_VIEW_GAP_MS = 1000;', worker)
        self.assertIn('const seenKey = `${resource}|${uid}`;', worker)
        self.assertIn('now - (state.lastSeen.get(seenKey) ?? 0) >= MIN_VIEW_GAP_MS', worker)
        self.assertIn('gapMs: MIN_VIEW_GAP_MS', worker)
        self.assertIn('const MAX_SEEN_ENTRIES = 8000;', worker)
        # The floor lives in memory; a per-day bucket would silently swallow re-watches.
        absent(self, "toISOString().slice(0, 10)", worker, 'view-counters.js')
        absent(self, 'friendbook_cf_view_dedupe', worker.replace(
            'CREATE TABLE IF NOT EXISTS friendbook_cf_view_dedupe', ''), 'view-counters.js')

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

    def test_displayed_view_counts_never_go_down(self):
        # Views must be seen growing: a resource the Worker has not counted yet reports 0, and that
        # zero must never replace a higher number the user already saw.
        client = (JAVA / 'core/CloudflareViews.java').read_text()
        self.assertIn('COUNTS.put(key, known != null ? Math.max(known.intValue(), views) : views);', client)
        feed = (JAVA / 'post/FeedAdapter.java').read_text()
        self.assertIn('if (total != null && total.intValue() > r.views) {', feed)
        self.assertIn('Math.max(Math.max(0, featured.views), cloudViews != null ? cloudViews.intValue() : 0)', feed)
        self.assertIn('Math.max(Math.max(0, p.views), stored != null ? stored.intValue() : 0)', feed)
        reels = (JAVA / 'reels/ReelsFragment.java').read_text()
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
