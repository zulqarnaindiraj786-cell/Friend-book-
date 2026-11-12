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
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / 'app/src/main/java/com/example/frienbook'
CLOUDFLARE = ROOT / 'cloudflare'


def absent(test, token, source, where):
    test.assertFalse(token in source, '%s must not contain %s' % (where, token))


def queries_text():
    """doc-queries.js as text: guards that assert on the SQL a list query builds read it here."""
    return (CLOUDFLARE / 'doc-queries.js').read_text()


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
        self.assertIn('env.ANALYTICS_ENGINE.writeDataPoint({', worker)
        self.assertIn('indexes: [resource.slice(0, 128)]', worker)
        self.assertIn('dimensions: [collection, id.slice(0, 128), String(uid).slice(0, 128)]', worker)
        # A counted view carries metric 1 and a repeat carries metric 0, because this AE SQL API has
        # no distinct-count aggregate: sum(double1) is viewers, count() is plays.
        self.assertIn('metrics: [counted ? 1 : 0],', worker)
        self.assertIn('writeViewEvent(env, collection, id, resource, uid, now, counted);', worker)
        self.assertIn("const AE_DATASET = 'friendbook_views';", worker)
        # The count is AE's uniq(uid): one view per user per video, forever, with no D1 row.
        # The count is AE's own distinct count of uids: exact across isolates, forever, no D1 row.
        self.assertIn('count(DISTINCT blob3) AS viewers', worker)
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
    def test_the_ae_queries_use_the_syntax_the_real_dataset_accepted(self):
        worker = (CLOUDFLARE / 'view-counters.js').read_text()
        report = (CLOUDFLARE / 'report-views-ae.mjs').read_text()
        # Comments are excluded from the bans on purpose: they quote the raw refusal AE answered with,
        # and that sentence is the whole reason the syntax looks the way it does.
        code = '\n'.join(line for line in worker.splitlines() if not line.lstrip().startswith('//'))
        rcode = '\n'.join(line for line in report.splitlines() if not line.lstrip().startswith('//'))
        # Raw answers from the live dataset, published on the pull request:
        #   `INTERVAL 90 DAY`   -> HTTP 422 "sql parser error: Expected literal string, found: 90"
        #   `uniq(blob3)`       -> HTTP 422 "unknown function call: UNIQ"
        #   `uniqExact(blob3)`  -> HTTP 422 "unknown function call: UNIQEXACT"
        #   `uniqHLL12(blob3)`  -> HTTP 422 "unknown function call: UNIQHLL12"
        #   `count(DISTINCT blob3)` -> HTTP 200 (resource String, viewers UInt64)
        #   `sum(double1)`      -> HTTP 200 (viewers Float64, plays UInt64)
        self.assertIn("+ ` WHERE timestamp >= now() - INTERVAL '${AE_WINDOW_DAYS}' DAY GROUP BY index1`", worker)
        self.assertNotIn('INTERVAL ${AE_WINDOW_DAYS} DAY', worker)
        absent(self, 'uniqExact', code, 'view-counters.js queries')
        absent(self, 'uniqExact', rcode, 'report-views-ae.mjs queries')
        # The viewer count is AE's own distinct count of uids - exact across isolates and forever.
        self.assertEqual(worker.count('count(DISTINCT blob3) AS viewers'), 2)
        self.assertEqual(worker.count('sum(double1) AS viewers'), 2)
        # Exact dedupe first, the metric-based sum second, and no uniq() family at all: AE refuses it.
        self.assertLess(worker.index('count(DISTINCT blob3)'), worker.index('sum(double1)'))
        absent(self, 'uniq(blob3) AS viewers', code, 'view-counters.js queries')
        absent(self, 'uniqHLL12', code, 'view-counters.js queries')
        self.assertIn('Math.round(viewers)', worker, 'sum() answers as a float; viewers are whole')
        # A plain count() of events counts PLAYS - every rewatch - so it must never be a viewer query.
        absent(self, 'SELECT index1 AS resource, count() AS viewers', code, 'view-counters.js')
        # The report probes the Worker's own queries plus every alternative, judged separately.
        self.assertIn("INTERVAL '${WINDOW_DAYS}' DAY", report)
        self.assertIn('const DISTINCT_PROBE_INDEXES = [1, 2];', report)
        self.assertIn('const SUM_PROBE_INDEXES = [3, 4];', report)
        self.assertIn('count(DISTINCT blob3)\\` accepted (AE de-duplicates each uid itself)', report)
        # GraphQL is walked instead of guessed: the guess answered {"data":{"__type":null}}.
        self.assertIn('{ __schema { queryType { name } } }', report)
        self.assertIn("for (const wanted of ['viewer', 'accounts', null]) {", report)
        # `accounts` arrives as [Type!], so one ofType level cannot reach the type name.
        self.assertIn('ofType { name kind ofType { name kind ofType { name kind } } }', report)
        self.assertIn('for (let depth = 0; depth < 4 && node; depth++) {', report)
        absent(self, '__type(name: "Viewer")', rcode, 'report-views-ae.mjs')

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
        self.assertIn('binding = "ANALYTICS_ENGINE"', config)
        self.assertNotIn('binding = "AE"', config, 'a stale binding name points at nothing')
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
        self.assertIn("const BINDING = 'ANALYTICS_ENGINE';", script)
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
        self.assertIn('{ __schema { queryType { name } } }', report)
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

    def test_the_analytics_engine_binding_name_matches_in_every_file(self):
        # The owner created the dataset in the dashboard as `friendbook_views` bound to
        # `ANALYTICS_ENGINE`. A binding name that differs between the config and the code means the
        # Worker writes no events and reads no counts while every test still passes - so the name is
        # taken from wrangler.toml and every consumer is checked against it.
        import re
        config = (ROOT / 'wrangler.toml').read_text()
        block = re.search(r'\[\[analytics_engine_datasets\]\]([\s\S]*?)(?=\n\[|\Z)', config)
        self.assertIsNotNone(block, 'the Analytics Engine binding must exist')
        binding = re.search(r'binding = "([^"]+)"', block.group(1)).group(1)
        dataset = re.search(r'dataset = "([^"]+)"', block.group(1)).group(1)
        self.assertEqual(dataset, 'friendbook_views', 'the dataset the owner created')
        self.assertEqual(binding, 'ANALYTICS_ENGINE', 'the binding the owner created')
        worker = (CLOUDFLARE / 'view-counters.js').read_text()
        self.assertIn(f'env.{binding}.writeDataPoint({{', worker)
        self.assertIn(f'if (!env?.{binding} || !env?.CF_AE_TOKEN', worker)
        self.assertIn(f'ae: Boolean(env.{binding}),', (ROOT / 'worker.js').read_text())
        self.assertIn(f"const BINDING = '{binding}';", (CLOUDFLARE / 'ensure-views-ae.mjs').read_text())
        self.assertIn(f'env.{binding} = {{ writeDataPoint:', (CLOUDFLARE / 'test/view-counters.test.js').read_text())
        absent(self, 'env.AE.', worker, 'view-counters.js')

    def test_health_reports_the_view_bindings_without_exposing_data(self):
        worker = (ROOT / 'worker.js').read_text()
        self.assertIn("const views = { ae: Boolean(env.ANALYTICS_ENGINE), kv: Boolean(env.VIEWS), "
                      "r2Snapshot: !env.VIEWS && Boolean(env.VIDEO_BUCKET), "
                      "aeReadToken: Boolean(env.CF_AE_TOKEN) };", worker)
        self.assertIn('return json({ ok: row?.connected === 1, views }', worker)

    def test_reels_count_one_view_and_never_show_a_lower_number(self):
        client = (JAVA / 'core/CloudflareViews.java').read_text()
        # A number the user already saw can never be dragged down by a colder Worker isolate.
        self.assertIn('COUNTS.put(key, known != null ? Math.max(known.intValue(), views) : views);', client)
        reels = (JAVA / 'reels/ReelsFragment.java').read_text()
        # One second of real watching is enough to count this user's one view - in Cloudflare.
        self.assertIn('void scheduleViewCount() {', reels)
        self.assertIn('}, 1000);', reels)
        self.assertIn('CloudflareViews.recordView(CloudflareViews.REELS, r.id, total -> {', reels)
        # ... and the same user is never sent twice in one session; Analytics Engine de-duplicates
        # each uid forever anyway, so the durable rule needs no database row at all.
        self.assertIn('private static final Set<String> SESSION_COUNTED_VIEWS = new HashSet<>();', reels)
        self.assertIn('if (!SESSION_COUNTED_VIEWS.add(r.id)) return;', reels)
        # The displayed number only ever grows, and it is read in ONE batched request per page.
        self.assertIn('int shownViews = (int) Math.max(r.views, seenViews == null ? 0 : seenViews.intValue());', reels)
        self.assertIn('CloudflareViews.fetchViews(CloudflareViews.REELS, ids, totals -> {', reels)
        self.assertIn('r.views = Math.max(r.views, total);', reels)
        # Pausing or leaving the screen cancels the pending count: a view that was not watched is
        # never posted.
        self.assertIn('VIEW_TICK.removeCallbacksAndMessages(null);', reels)

    def test_home_feed_does_zero_views_work(self):
        feed = (JAVA / 'post/FeedAdapter.java').read_text()
        home = (JAVA / 'main/HomeFragment.java').read_text()
        for name, source in (('FeedAdapter', feed), ('HomeFragment', home)):
            # No views client, no views endpoint: loading or scrolling the Home Feed must not cost a
            # D1 read, a D1 write, a KV read, a KV write or even an HTTP request.
            absent(self, 'CloudflareViews', source, name)
            absent(self, '/v1/views', source, name)
            # And Firestore never carries a views counter either.
            absent(self, '"views"', source, name)

    def test_views_client_is_cloudflare_only_and_gated_by_build_flags(self):
        gradle = (ROOT / 'app/build.gradle').read_text()
        self.assertIn("buildConfigField 'boolean', 'CLOUDFLARE_VIEWS_ENABLED', 'true'", gradle)
        self.assertIn("buildConfigField 'boolean', 'CLOUDFLARE_DOCS_MIRROR', 'true'", gradle)
        client = (JAVA / 'core/CloudflareViews.java').read_text()
        self.assertIn('/v1/views', client)
        self.assertIn('BuildConfig.CLOUDFLARE_VIEWS_ENABLED', client)
        # Only an in-flight guard: two racing callbacks collapse into one request.
        self.assertIn('POSTING.add(key)', client)
        self.assertIn('POSTING.remove(key)', client)
        # Firebase appears as authentication only - never as a data source in the views client.
        absent(self, 'com.google.firebase.firestore', client, 'CloudflareViews')
        absent(self, 'addSnapshotListener', client, 'CloudflareViews')
        transport = (JAVA / 'core/CloudTransport.java').read_text()
        self.assertIn('getIdToken(false)', transport)
        absent(self, 'FirebaseFirestore', transport, 'CloudTransport')

    def test_new_documents_are_mirrored_to_cloudflare_d1(self):
        docs = (JAVA / 'core/CloudflareDocs.java').read_text()
        self.assertIn('CloudTransport.BASE + "/v1/docs"', docs)
        # Firestore sentinels are not JSON: a server timestamp becomes the client clock, anything
        # else is dropped instead of leaking Firestore internals to the Worker.
        self.assertIn('if (s.contains("serverTimestamp")) v = System.currentTimeMillis();', docs)
        reel = (JAVA / 'reels/AddReelActivity.java').read_text()
        post = (JAVA / 'post/CreatePostActivity.java').read_text()
        self.assertIn('CloudflareDocs.mirror("reels", x.getId(), m);', reel)
        self.assertIn('CloudflareDocs.mirror("posts", x.getId(), m);', post)
        # The mirror is additive and fire-and-forget: a Worker outage can never block posting.
        self.assertIn('catch (Throwable ignored) {', docs)
        absent(self, 'DELETE', docs, 'CloudflareDocs')

    def test_update_carries_existing_user_data_to_cloudflare_without_touching_the_old_copy(self):
        mig = (JAVA / 'core/CloudMigration.java').read_text()
        # Once per versionCode, and the flag advances BEFORE the work so launches cannot double-run.
        self.assertIn('sp.edit().putInt("version", BuildConfig.VERSION_CODE).apply();', mig)
        # Bounded slices: the bridge can never become a full-database scrape on a phone.
        self.assertIn('private static final int SLICE_LIMIT = 100;', mig)
        # Additive only: the bridge READS Firestore and WRITES Cloudflare - never the other way, so a
        # user who never updates keeps the old app and the old data, untouched, forever.
        for forbidden in ['.delete(', '.update(', '.set(', 'FieldValue']:
            absent(self, forbidden, mig, 'CloudMigration')
        main = (JAVA / 'main/MainActivity.java').read_text()
        self.assertIn('CloudMigration.runOnceAfterUpdate(this);', main)

    def test_every_new_write_travels_to_cloudflare_with_the_legacy_write(self):
        sites = {
            'core/Notify.java': 'CloudflareDocs.mirror("notifications", ref.getId(), n)',
            'post/PostDetailActivity.java': 'CloudflareDocs.mirror("comments", ref.getId(), m)',
            'reels/ReelCommentsActivity.java': 'CloudflareDocs.mirror("comments", ref.getId(), m)',
            'post/FeedAdapter.java': 'CloudflareDocs.mirror("stories", ref.getId(), s)',
            'story/AddStoryActivity.java': 'CloudflareDocs.mirror("stories", ref.getId(), m)',
            'friends/FriendsFragment.java': 'CloudflareDocs.mirror("friend_requests", ref.getId(), m)',
            'chat/ChatActivity.java': 'CloudflareDocs.mirror("messages", chatId + "~" + msgRef.getId(), msgMirror)',
            'core/Fb.java': 'CloudflareDocs.mirror("users", uid, fields);',
            'reels/AddReelActivity.java': 'CloudflareDocs.mirror("reels", x.getId(), m);',
            'post/CreatePostActivity.java': 'CloudflareDocs.mirror("posts", x.getId(), m);',
        }
        for rel, needle in sites.items():
            self.assertIn(needle, (JAVA / rel).read_text(), rel)
        # Dual-write on purpose: the legacy Firestore write stays at every site while old builds
        # exist, so an old-app user still sees everything a new-app user creates.
        self.assertIn('Fb.db().collection("notifications").add(n)', (JAVA / 'core/Notify.java').read_text())
        self.assertIn('Fb.db().collection("comments").add(m)', (JAVA / 'post/PostDetailActivity.java').read_text())

    def test_reads_are_cloudflare_first_with_the_legacy_store_as_backfill(self):
        reels = (JAVA / 'reels/ReelsFragment.java').read_text()
        home = (JAVA / 'main/HomeFragment.java').read_text()
        self.assertIn('CloudflareDocs.list("reels", 50, arr -> {', reels)
        self.assertIn('CloudflareDocs.list("posts", 50, arr -> {', home)
        # Union only: a Cloudflare document joins the page, it never replaces or removes a legacy one.
        self.assertIn('if (cr == null || cr.id == null || !have.add(cr.id)) continue;', reels)
        self.assertIn('if (cp == null || cp.id == null || !have.add(cp.id)) continue;', home)

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

    # ---------------- Firebase is authentication only: media, presence, switches ----------------

    def test_media_lives_on_cloudflare_r2_and_never_on_firebase_storage(self):
        gradle = (ROOT / 'app/build.gradle').read_text()
        absent(self, 'firebase-storage', gradle, 'app/build.gradle')
        media = (JAVA / 'core/CloudflareMedia.java').read_text()
        # The upload only counts when the Worker itself confirms R2 and the bucket's own origin.
        self.assertIn('"/v1/videos"', media)
        self.assertIn('"/v1/photos"', media)
        self.assertIn('if (!"r2".equals(provider) || !url.startsWith(ORIGIN + "/"))', media)
        self.assertIn('https://pub-88ab0a56178742af95cbbf62df152a67.r2.dev', media)
        # Content-addressed ids: the same bytes can never be stored twice.
        self.assertIn('MessageDigest.getInstance("SHA-256")', media)
        self.assertIn('connection.setFixedLengthStreamingMode(bytes.length)', media)
        # Every media call site goes through it - no Cloudinary client and no Firebase Storage helper.
        self.assertFalse((JAVA / 'core/Cloudinary.java').exists(), 'the Cloudinary client must be gone')
        fb = (JAVA / 'core/Fb.java').read_text()
        absent(self, 'FirebaseStorage', fb, 'Fb.java')
        absent(self, 'public static Task<String> upload(', fb, 'Fb.java')
        for name in ['reels/AddReelActivity.java', 'post/CreatePostActivity.java', 'story/AddStoryActivity.java',
                     'chat/ChatActivity.java', 'pay/PayRequestActivity.java', 'post/PostDetailActivity.java',
                     'profile/EditProfileActivity.java']:
            source = (JAVA / name).read_text()
            self.assertIn('CloudflareMedia.upload', source, '%s must upload through CloudflareMedia' % name)
            for forbidden in ['uploadOrFirebase', 'Cloudinary.upload']:
                absent(self, forbidden, source, name)

    def test_presence_and_typing_run_on_cloudflare_d1_with_no_realtime_database(self):
        gradle = (ROOT / 'app/build.gradle').read_text()
        absent(self, 'firebase-database', gradle, 'app/build.gradle')
        presence_worker = (CLOUDFLARE / 'presence-realtime.js').read_text()
        # D1 is the source of truth, so a contact on another isolate sees the same fact.
        self.assertIn('INSERT INTO friendbook_cf_presence(uid, online, visible, last_seen, updated_at)', presence_worker)
        self.assertIn('ON CONFLICT(uid) DO UPDATE SET', presence_worker)
        self.assertIn('INSERT INTO friendbook_cf_typing(chat_id, uid, typing, updated_at)', presence_worker)
        self.assertIn('SELECT uid FROM friendbook_cf_typing WHERE chat_id = ? AND typing = 1 AND updated_at > ?',
                      presence_worker)
        # Staleness is enforced by the Worker, never by a client that forgot to sign off.
        self.assertIn('PRESENCE_TTL_MS = 300_000', presence_worker)
        self.assertIn('TYPING_TTL_MS = 8_000', presence_worker)
        # A heartbeat over plain HTTPS exists for clients without a WebSocket.
        self.assertIn("if (path === '/v1/presence/me')", presence_worker)
        self.assertIn("typeof body.online !== 'boolean'", presence_worker)
        client = (JAVA / 'core/Presence.java').read_text()
        absent(self, 'com.google.firebase.database', client, 'Presence.java')
        absent(self, 'Fb.rtdb()', client, 'Presence.java')
        self.assertIn('CloudTransport.BASE + "/v1/presence"', client)
        self.assertIn('body.put("online", show)', client)
        self.assertIn('"/contact/" + enc(uid)', client)
        self.assertIn('"/typing/" + enc(chatId)', client)
        for path in sorted(JAVA.rglob('*.java')):
            source = path.read_text()
            for forbidden in ['FirebaseDatabase', 'Fb.rtdb()', 'ServerValue.TIMESTAMP', 'ValueEventListener']:
                absent(self, forbidden, source, path.name)

    def test_runtime_switches_come_from_the_worker_not_from_firebase(self):
        gradle = (ROOT / 'app/build.gradle').read_text()
        absent(self, 'firebase-config', gradle, 'app/build.gradle')
        for path in sorted(JAVA.rglob('*.java')):
            source = path.read_text()
            for forbidden in ['FirebaseRemoteConfig', 'remoteconfig', 'FirebaseAnalytics', 'firebase.analytics']:
                absent(self, forbidden, source, path.name)
        worker = (ROOT / 'worker.js').read_text()
        config = (CLOUDFLARE / 'app-config.js').read_text()
        # The switch answers before the auth gate: it is public booleans, so an anonymous client and a
        # signed-in client see the same kill-switch state.
        self.assertIn("if (path === '/v1/config') return appConfig(request, env);", worker)
        self.assertLess(worker.index("if (path === '/v1/config')"), worker.index('if (!env.FIREBASE_PROJECT_ID)'))
        self.assertIn("adsEnabled: env.ADS_ENABLED !== 'false'", config)
        self.assertIn("'Cache-Control': 'public, max-age=300'", config)
        client = (JAVA / 'core/CloudConfig.java').read_text()
        self.assertIn('CloudTransport.BASE + "/v1/config"', client)
        self.assertIn('AdsConfig.setRemoteAllowed(ads)', client)
        self.assertIn('REFRESH_MS = 60_000L', client)
        main = (JAVA / 'main/MainActivity.java').read_text()
        self.assertIn('com.example.frienbook.core.CloudConfig.refresh();', main)

    # ---------------- Cloudflare D1 is the app database: queries, access, atomic ops ----------------

    def test_the_document_store_answers_real_queries_not_just_whole_collections(self):
        queries = queries_text()
        documents = (CLOUDFLARE / 'app-documents.js').read_text()
        # Filters, ordering, keyset pages, counts and a change feed - the shapes the app asks for.
        self.assertIn(r"/^(eq|has|in|gt|gte|lt|lte)\[([A-Za-z_][A-Za-z0-9_]{0,39})\]$/.exec(key)", queries)
        self.assertIn("const idIn = key === 'inId';", queries)
        self.assertIn("if (!spec.order.includes(orderField)) throw new QueryError", queries)
        # Each filter kind has its own allowlist: a field must be declared before it can be matched.
        self.assertIn('if (!spec.eq.includes(field)) throw new QueryError', queries)
        self.assertIn('if (!spec.has.includes(field)) throw new QueryError', queries)
        self.assertIn('throw new QueryError(`Cannot compare ${field}`);', queries)
        self.assertIn('LIMIT ?', queries)
        self.assertIn('SELECT COUNT(*) AS total FROM', queries)
        self.assertIn("const since = params.get('since');", queries)
        # Inclusive: two writes in one millisecond are indistinguishable, so the feed delivers at
        # least once and the listener de-duplicates. A strict > would lose the second one.
        self.assertIn('updated_at >= ?', queries)
        # A cursor carries the order value AND the path, so a page can neither skip nor repeat a row.
        self.assertIn('p: path });', queries)
        self.assertIn('AND path > ?', queries)
        # Everything is bounded: no request can ask for the whole table.
        self.assertIn('MAX_LIMIT = 50', queries)
        self.assertIn('MAX_IN = 30', queries)
        self.assertIn('MAX_FILTERS = 4', queries)
        self.assertIn('COUNT_CAP = 5_000', queries)
        self.assertIn('MAX_DOC_BYTES = 95_000', queries)
        # Values are always bound, never concatenated into SQL.
        absent(self, '${raw}', queries, 'doc-queries.js')
        absent(self, '${value}', queries, 'doc-queries.js')
        self.assertIn('bind(...built.binds)', documents)

    def test_private_documents_are_scoped_by_the_worker_never_by_the_client(self):
        access = (CLOUDFLARE / 'doc-access.js').read_text()
        documents = (CLOUDFLARE / 'app-documents.js').read_text()
        # A list on a private collection is filtered to the caller by the Worker itself.
        self.assertIn('export function listScope(collection, uid, admin)', access)
        self.assertIn("clauses.push('owner_uid = ?');", access)
        # A comparison on a text field would silently misorder, so only numeric fields may be ranged.
        self.assertIn('if (!isNumericField(field)) throw new QueryError', queries_text())
        self.assertIn('json_each(json_extract(document_json', access)
        self.assertIn("if (isAdminOnlyRead(collection)) return { sql: ' AND 0', binds: [] };", access)
        self.assertIn('const scope = listScope(collection, uid, admin);', queries_text())
        # Chat messages are gated by their parent chat, and a thread must be named.
        self.assertIn("chatId filter required", documents)
        # A nested collection (chats/<chatId>/messages) is gated by its parent, and a list of it is
        # never cacheable, because the parent's membership decides who may read a single row.
        self.assertIn('const cacheable = !listParent && isPublicRead(leaf) && !filtered;', documents)
        self.assertIn('await canReadNested(db, listParent, listParentId, collection, {}, uid, admin)', documents)
        self.assertIn('async function canWriteNested(db, parentCollection, parentId, collection, uid, admin)', documents)
        # Reading a public parent never means writing into it: only named children are writable.
        self.assertIn("'lives/chat', 'posts/reactions', 'reels/reactions', 'events/going',", documents)
        self.assertIn('const NESTED_WRITABLE = new Set([', documents)
        self.assertIn("if (leaf === 'sessions') return parentCollection === 'users' && parentId === uid;", documents)
        self.assertIn('async function isChatParticipant(db, chatId, uid, admin)', documents)
        # Admin comes from the token email or a stored user document, never from the request.
        self.assertIn('export async function resolveAdmin(db, uid, email, env)', documents)
        self.assertIn("$.isAdmin'", documents)
        self.assertIn('email.toLowerCase() === OWNER_EMAIL', documents)
        # Only unfiltered public lists are cacheable, so one caller's page is never served to another.
        self.assertIn('isPublicRead(leaf) && !filtered', documents)

    def test_document_deletes_are_tombstones_and_no_row_is_ever_dropped(self):
        documents = (CLOUDFLARE / 'app-documents.js').read_text()
        self.assertIn("$.deleted', json('true')", documents)
        self.assertIn('deletedAt', documents)
        self.assertIn('deleted: true, kept: true', documents)
        self.assertIn("$.deleted'), 0) = 0", queries_text())
        # Nowhere in the Cloudflare layer may a document row be destroyed.
        for path in sorted(CLOUDFLARE.rglob('*.js')):
            source = path.read_text()
            for forbidden in ['DELETE FROM friendbook_cf_documents', 'DROP TABLE', 'TRUNCATE']:
                absent(self, forbidden, source, path.name)

    def test_counters_and_arrays_are_atomic_instead_of_read_modify_write(self):
        queries = queries_text()
        documents = (CLOUDFLARE / 'app-documents.js').read_text()
        self.assertIn('export function applyOps(doc, ops, now)', queries)
        for op in ['ops.stamp', 'ops.set', 'ops.inc', 'ops.add', 'ops.remove', 'ops.unset']:
            self.assertIn(op, queries)
        # A double tap must not store the same uid twice, and an increment must not lose a writer.
        self.assertIn('if (!list.some(item => item === value)) list.push(value);', queries)
        self.assertIn('WHERE path = ? AND document_json = ?', documents)
        self.assertIn('Concurrent write, please retry', documents)
        self.assertIn('for (let attempt = 0; attempt < 4; attempt += 1)', documents)
        # The server stamps its own clock; a client timestamp is never trusted for "now".
        self.assertIn('out[field(name)] = now;', queries)

    def test_migrated_firestore_documents_stay_readable_and_queryable(self):
        queries = queries_text()
        documents = (CLOUDFLARE / 'app-documents.js').read_text()
        # A historical row wraps values as {fields:{name:{stringValue|integerValue|...}}}; a filter that
        # only read the plain shape would hide migrated history from the app.
        self.assertIn("$.fields.${field}", queries)
        self.assertIn('.integerValue', queries)
        self.assertIn('.stringValue', queries)
        self.assertIn('.timestampValue', queries)
        self.assertIn('.arrayValue.values', queries)
        self.assertIn('export function toPlainDocument(rawJson, docId)', documents)
        self.assertIn('fromFirestoreFields(parsed.fields)', documents)

    def test_money_moderation_and_profile_writes_keep_their_old_rules(self):
        access = (CLOUDFLARE / 'doc-access.js').read_text()
        # Creating a payment, poke, gift, live room, event or page is bound to the caller's own uid.
        self.assertIn("pokes: 'from', payments: 'userId'", access)
        self.assertIn("const PENDING_ONLY_CREATE = new Set(['payments', 'boosts', 'subscriptions']);", access)
        self.assertIn("payments: ['status', 'approvedBy', 'approvedAt'", access)
        self.assertIn("users: ['isAdmin', 'banned', 'isModerator', 'deactivated', 'tickLevel', 'stars'],", access)
        # A normal user can never lift a ban or grant admin, and only an admin moves money on.
        self.assertIn("key === 'status'", access)
        self.assertIn("if (method === 'delete') return OWNER_MAY_DELETE.has(collection);", access)
        # Collaborative counters stay open on lives/events/pages, exactly like the old rules allowed.
        self.assertIn("lives: ['viewers', 'stars'", access)
        self.assertIn("const guarded = ['isAdmin', 'banned', 'isModerator', 'deactivated', 'tickLevel'];", access)

    def test_every_query_the_app_runs_is_allowed_by_the_worker(self):
        """The allowlist is derived from the app, not from taste: a missing field is a broken screen.

        The Android sources are scanned for every clause that follows a collection() call in the same
        statement, and each field it names must be declared for that collection in doc-access.js. This
        is the guard that stops the data layer from being migrated one screen at a time and silently
        losing a filter along the way.
        """
        access = (CLOUDFLARE / 'doc-access.js').read_text()
        block = access[access.index('export const QUERYABLE = {'):]
        block = block[:block.index('};')]
        allowed = {}
        for line in block.split('\n'):
            entry = re.match(r"\s*([a-zA-Z_]+):\s*\{\s*eq:\s*\[([^\]]*)\],\s*has:\s*\[([^\]]*)\],\s*order:\s*\[([^\]]*)\]", line)
            if not entry:
                continue
            name = entry.group(1)
            allowed[name] = set()
            for group in (2, 3, 4):
                for field in re.findall(r"'([A-Za-z_]+)'", entry.group(group)):
                    allowed[name].add(field)
        self.assertTrue(allowed, 'QUERYABLE allowlist could not be parsed')

        starts = re.compile(r'collection\("([a-zA-Z_]+)"\)')
        clauses = re.compile(
            r'\.(?:whereEqualTo|whereIn|whereArrayContains|whereGreaterThan|whereGreaterThanOrEqualTo'
            r'|whereLessThan|whereLessThanOrEqualTo|orderBy)\("([a-zA-Z_]+)"')
        missing = []
        for path in sorted((ROOT / 'app' / 'src' / 'main' / 'java').rglob('*.java')):
            if path.name in ('CloudStore.java', 'CloudMigration.java'):
                continue
            source = path.read_text()
            for match in starts.finditer(source):
                collection = match.group(1)
                end = source.find(';', match.end())
                statement = source[match.end():end if end > 0 else match.end() + 400]
                for field in clauses.findall(statement[:400]):
                    if collection not in allowed:
                        missing.append('%s: collection %s is not queryable' % (path.name, collection))
                    elif field not in allowed[collection]:
                        missing.append('%s: %s.%s' % (path.name, collection, field))
        self.assertEqual([], sorted(set(missing)),
                         'the Worker would refuse these queries, so a screen would show nothing')



if __name__ == '__main__':
    unittest.main()
