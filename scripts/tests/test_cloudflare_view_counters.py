"""Source contract guards: views live in Cloudflare Analytics Engine, never in D1 or Firestore.

Firebase is authentication only for views - the Worker verifies the ID token and owns the counter.
Counting rule (product decision): ONE view per user per resource, forever. One second of watching is
enough; watching the whole video, looping it or coming back next month adds nothing. The Home Feed
does no view-counter work at all - no request, no D1, no KV - so loading the feed costs nothing.

Storage contract (the reason this design exists): every view is ONE Analytics Engine event, the
count is AE's uniq(uid), and D1 receives ZERO writes from the view path (and zero reads, except one
bounded read-only seed when both AE and KV are empty). Worker memory + one KV aggregate key serve
requests so nothing waits on a query.
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

    def test_view_path_reads_d1_only_as_a_bounded_last_resort(self):
        worker = (CLOUDFLARE / 'view-counters.js').read_text()
        # The only SELECT in the module is the emergency seed, and it runs only when Analytics Engine
        # and KV both came back empty - so a normal view costs no D1 statement at all.
        self.assertEqual(worker.count("'SELECT"), 1, 'exactly one SELECT literal may exist')
        self.assertIn('SELECT resource, views FROM friendbook_cf_view_counters ORDER BY views DESC LIMIT ?', worker)
        self.assertIn('const SEED_LIMIT = 500;', worker)
        self.assertIn('if (state.totals.size === 0 && env?.DB)', worker)
        # Live reads come from memory, refilled from Analytics Engine and the snapshot store.
        self.assertIn("const KV_KEY = 'views:live:v1';", worker)
        self.assertIn("await env.VIEWS.get(KV_KEY, { type: 'json', cacheTtl: 60 })", worker)
        self.assertIn("const snapshotStore = (env) => (env?.VIEWS ? 'kv' : (env?.VIDEO_BUCKET ? 'r2' : null));", worker)
        self.assertIn('const store = snapshotStore(env);', worker)
        self.assertIn('if (state.loaded) return;', worker)
        self.assertIn('state.totals.get(resource) ?? 0', worker)
        absent(self, 'SELECT views FROM friendbook_cf_view_counters WHERE resource', worker, 'view-counters.js')
        absent(self, 'withSession(', worker, 'view-counters.js')
    def test_d1_receives_zero_writes_from_the_view_path(self):
        worker = (CLOUDFLARE / 'view-counters.js').read_text()
        # Not one write statement of any kind: no totals upsert, no per-user stamp, no schema
        # creation. Analytics Engine holds the events, KV holds the live snapshot.
        for forbidden in ['INSERT', 'UPDATE ', 'DELETE ', 'CREATE TABLE', 'CREATE INDEX',
                          'db.batch', 'ensureSchema', '.run()', 'RETURNING views',
                          'views = views + 1', 'ON CONFLICT', 'D1_FLUSH_MS', 'state.dirty']:
            absent(self, forbidden, worker, 'view-counters.js')
        self.assertNotIn('friendbook_cf_view_last', worker)
    def test_kv_writes_stay_inside_the_free_tier_budget(self):
        worker = (CLOUDFLARE / 'view-counters.js').read_text()
        # One aggregate key, rewritten at most every five minutes: <= 288 writes/day per isolate vs
        # the 1,000/day Workers Free limit, throttled inside flushKv so no other path can add one.
        self.assertIn('const KV_FLUSH_MS = 300_000;', worker)
        self.assertIn('if (!force && now - state.lastKvFlushAt < KV_FLUSH_MS) return false;', worker)
        self.assertIn('await flushKv(env, seededAt, true);', worker)
        self.assertIn('const KV_SNAPSHOT_LIMIT = 1000;', worker)
        self.assertIn('await env.VIEWS.put(KV_KEY, JSON.stringify(snapshot))', worker)
        # The snapshot stores AE's de-duplicated truth (plus pre-AE history), never this isolate's
        # temporary bridge, and only falls back to the live total when AE is not answering.
        self.assertIn('return state.aeUsable ? historical : Math.max(historical, state.totals.get(resource) ?? 0);', worker)
        self.assertIn("state.kvUsable ? (state.snapshotStore === 'r2' ? 'r2' : 'kv') : null,", worker)
        self.assertIn('if (!snapshotStore(env)) return false;', worker)
        self.assertIn('if (snapshotStore(env)) await flushKv(env, seededAt, true);', worker)

    def test_the_r2_snapshot_fallback_never_touches_a_video(self):
        worker = (CLOUDFLARE / 'view-counters.js').read_text()
        # When Workers KV cannot be bound to the Worker, one small counters object in the existing R2
        # bucket keeps the numbers from collapsing to zero on every new isolate.
        self.assertIn("const R2_SNAPSHOT_KEY = 'views/live/v1.json';", worker)
        self.assertIn('await env.VIDEO_BUCKET.put(R2_SNAPSHOT_KEY, JSON.stringify(snapshot),', worker)
        self.assertIn("const object = await env.VIDEO_BUCKET.get(R2_SNAPSHOT_KEY);", worker)
        # It lives under its own prefix, cannot reach a video key, and nothing in the module deletes.
        self.assertTrue(worker.index("'views/live/v1.json'") < worker.index('env.VIDEO_BUCKET.put'))
        absent(self, 'VIDEO_BUCKET.delete', worker, 'view-counters.js')
        absent(self, "'videos/", worker, 'view-counters.js')
        absent(self, '.delete(', worker.replace('state.counted.delete(', '').replace('state.sinceAe.delete(', ''),
               'view-counters.js')
    def test_storage_flushes_never_block_the_response(self):
        worker = (CLOUDFLARE / 'view-counters.js').read_text()
        self.assertIn("if (ctx && typeof ctx.waitUntil === 'function') ctx.waitUntil(promise);", worker)
        self.assertIn('defer(ctx, Promise.allSettled(tasks));', worker)
    def test_every_view_is_one_analytics_engine_event_and_one_view_per_user(self):
        worker = (CLOUDFLARE / 'view-counters.js').read_text()
        # One event per view: the resource is AE's index (its grouping key), and collection, id and
        # uid travel as blobs so uniq(blob3) can enforce the rule at aggregation time.
        self.assertIn('env.AE.writeDataPoint({', worker)
        self.assertIn('indexes: [resource.slice(0, 128)]', worker)
        self.assertIn('dimensions: [collection, id.slice(0, 128), String(uid).slice(0, 128)]', worker)
        self.assertIn('metrics: [1]', worker)
        self.assertIn("const AE_DATASET = 'friendbook_views';", worker)
        # The count is AE's uniq(uid): one view per user per video, forever, with no D1 row.
        self.assertIn('uniq(blob3) AS viewers', worker)
        self.assertIn("const DEDUPE_RULE = 'once-per-user';", worker)
        self.assertIn('dedupe: DEDUPE_RULE', worker)
        self.assertIn('const seenKey = `${resource}|${uid}`;', worker)
        self.assertIn('const settled = state.counted.has(seenKey);', worker)
        self.assertIn('const MAX_COUNTED_ENTRIES = 8000;', worker)
        # AE answers within a minute at most, deferred so a response never waits for it, and the
        # in-memory bridge is reduced by whatever AE absorbed (no dip, no double count).
        self.assertIn('const AE_REFRESH_MS = 60_000;', worker)
        self.assertIn('const absorbed = Math.max(0, (state.aeBase.get(resource) ?? 0) - previous);', worker)
        self.assertIn('const bridged = Math.max(0, (state.sinceAe.get(resource) ?? 0) - absorbed);', worker)
        # Both AE query APIs are supported, because which one an account can use is not ours to pick.
        self.assertIn('/analytics_engine/sql?query=', worker)
        self.assertIn('https://api.cloudflare.com/client/v4/graphql', worker)
        # A refused AE must never invent a view or fall back to writing D1.
        self.assertIn("event: eventWritten ? 'analytics-engine' : 'none'", worker)
        absent(self, 'MIN_VIEW_GAP_MS', worker, 'view-counters.js')
        absent(self, 'gapMs', worker, 'view-counters.js')
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

    def test_analytics_engine_is_bound_before_the_r2_anchor(self):
        config = (ROOT / 'wrangler.toml').read_text()
        self.assertIn('[[analytics_engine_datasets]]', config)
        self.assertIn('binding = "AE"', config)
        # wrangler 4 reports `dataset_name` as an unexpected field, drops it, and names the dataset
        # after the binding - so every event would land in a dataset called "AE" and every query for
        # `friendbook_views` would answer "unknown field". The key must be `dataset`.
        self.assertIn('dataset = "friendbook_views"', config)
        self.assertNotIn('dataset_name', config)
        self.assertIn('CF_ACCOUNT_ID = "92f3d04598822de2e49e14561218d090"', config)
        # Before the R2 anchor, or the R2-strip deploy fallback would truncate it away.
        self.assertLess(config.index('[[analytics_engine_datasets]]'),
                        config.index('# Existing owner-provided bucket.'))
        # Still exactly one D1 binding, still the owner's own database, untouched.
        self.assertEqual(config.count('[[d1_databases]]'), 1)

    def test_ci_binds_ae_stores_the_read_token_and_reports_raw_answers(self):
        script = (CLOUDFLARE / 'ensure-views-ae.mjs').read_text()
        self.assertIn("const BINDING = 'AE';", script)
        self.assertIn("const DATASET = 'friendbook_views';", script)
        self.assertIn('dataset = \"${DATASET}\"', script)
        self.assertNotIn('dataset_name = \"${DATASET}\"', script)
        # It also repairs a config an older run wrote with the wrong key.
        self.assertIn("if (/^dataset_name\\s*=/m.test(text))", script)
        self.assertIn("const ANCHOR = '# Existing owner-provided bucket.';", script)
        self.assertIn('process.exit(0)', script, 'provisioning must never fail a release')
        report = (CLOUDFLARE / 'report-views-ae.mjs').read_text()
        self.assertIn('<!-- friendbook-views-ae-report -->', report)
        self.assertIn('/analytics_engine/sql?query=', report)
        self.assertIn("const API = 'https://api.cloudflare.com/client/v4';", report)
        self.assertIn('fetch(`${API}/graphql`', report)
        # The report must show WHY a binding is missing, not only that it is: the raw output of every
        # deploy attempt, the token's own scope answer, the KV namespace answer and the real GraphQL
        # field name of the dataset (discovered by introspection, never assumed).
        self.assertIn("/tmp/deploy-logs", report)
        # The refusal wrangler prints is at the END of its output, so the log is clipped from the tail.
        self.assertIn('const fenceTail = (text, max = 2500)', report)
        self.assertIn('fenceTail(attempt.log)', report)
        self.assertIn('DEPLOY_ATTEMPT_USED', report)
        self.assertIn('/user/tokens/verify', report)
        self.assertIn('/storage/kv/namespaces', report)
        self.assertIn('__type(name: \"Viewer\")', report)
        self.assertIn('Analytics Engine > Edit', report)
        # Editing a comment is /repos/{o}/{r}/issues/comments/{id}; addressing it under the issue
        # number answers HTTP 404 and the pull request silently keeps the previous run's evidence.
        self.assertIn('${pull.owner}/${pull.name}/issues/comments/${id}', report)
        self.assertNotIn('${base}/${match.id}', report)
        self.assertIn('process.exit(0)', report)
        workflow = (ROOT / '.github/workflows/deploy-cloudflare.yml').read_text()
        self.assertIn('pull-requests: write', workflow)
        # Refreshing the report is an issue-comment edit: without this scope the API answers 404 and
        # the pull request keeps showing the PREVIOUS run's evidence as if nothing had changed.
        self.assertIn('issues: write', workflow)
        self.assertIn('node cloudflare/ensure-views-ae.mjs', workflow)
        self.assertIn('npx wrangler secret put CF_AE_TOKEN', workflow, 'the AE token is a secret, never a --var')
        self.assertIn('node cloudflare/report-views-ae.mjs', workflow)
        self.assertLess(workflow.index('node cloudflare/ensure-views-ae.mjs'),
                        workflow.index('npm run deploy'), 'AE must be bound before the deploy')
        self.assertLess(workflow.index('npm run deploy'),
                        workflow.index('node cloudflare/report-views-ae.mjs'), 'and reported after it')
        # A refused AE binding must never break a release, and must never cost another binding: the
        # fallbacks remove ONE block each (the old truncation at the R2 anchor silently deleted the KV
        # binding and the cron triggers that come after it).
        self.assertIn("re.sub(r'# Every view is one Analytics Engine event[\\s\\S]*?\\[\\[analytics_engine_datasets\\]\\][^\\[]*', '', text)", workflow)
        self.assertIn("re.sub(r'\\[\\[analytics_engine_datasets\\]\\][^\\[]*', '', text)", workflow)
        self.assertIn("re.sub(r'# Existing owner-provided bucket[\\s\\S]*?\\[\\[r2_buckets\\]\\][^\\[]*', '', text)", workflow)
        self.assertNotIn('p.write_text(text[:idx])', workflow, 'no truncation fallback may remain')
        for attempt in ['attempt full \"$VARS_R2_ON\"', 'attempt no-ae \"$VARS_R2_ON\"',
                        'attempt no-r2 \"$VARS_R2_OFF\"', 'attempt no-ae-no-r2 \"$VARS_R2_OFF\"']:
            self.assertIn(attempt, workflow)
        # Each attempt keeps its raw wrangler output for the report, and records which one shipped.
        self.assertIn('mkdir -p /tmp/deploy-logs', workflow)
        self.assertIn('echo \"DEPLOY_ATTEMPT_USED=$label\" >> \"$GITHUB_ENV\"', workflow)
        self.assertIn('cp wrangler.toml /tmp/wrangler.toml.shipped', workflow)
        self.assertIn('cp /tmp/wrangler.toml.bak wrangler.toml', workflow,
                      'the repo config is always restored, whatever shipped')

    def test_health_reports_the_view_bindings_without_exposing_data(self):
        worker = (ROOT / 'worker.js').read_text()
        self.assertIn("const views = { ae: Boolean(env.AE), kv: Boolean(env.VIEWS), "
                      "r2Snapshot: !env.VIEWS && Boolean(env.VIDEO_BUCKET), "
                      "aeReadToken: Boolean(env.CF_AE_TOKEN) };", worker)
        self.assertIn('return json({ ok: row?.connected === 1, views }', worker)

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
