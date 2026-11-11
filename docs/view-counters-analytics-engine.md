# View counters on Cloudflare Analytics Engine: zero D1 writes, one view per user

**Status:** implemented in `cloudflare/view-counters.js` (Worker), `ReelsFragment` (counting),
`FeedAdapter` (Home Feed does nothing), deployed by `.github/workflows/deploy-cloudflare.yml`.
No user data was deleted, reset or migrated. The D1 tables `friendbook_cf_view_counters`,
`friendbook_cf_view_dedupe` and `friendbook_cf_view_last` still exist with all their rows — the view
path simply stopped writing to them.

## The three rules

1. **Every view is one Analytics Engine event.** A view that reaches the Worker is written with
   `env.AE.writeDataPoint({ indexes: [resource], dimensions: [collection, id, uid], metrics: [1] })`.
2. **D1 receives ZERO writes** from the view path — no totals upsert, no per-user stamp, no
   `CREATE TABLE`. It also performs zero reads, with one documented exception below.
3. **ONE view per user per video, forever.** One second of watching is enough; the whole video, a
   loop, or coming back next month adds nothing. Analytics Engine enforces this in the aggregation
   (`uniq(uid)`), so the rule needs no database row at all.

And the Home Feed does no view-counter work whatsoever: no request, no D1, no KV, no AE.

## How a number is produced

| Layer | Role | Cost |
| --- | --- | --- |
| Analytics Engine (`AE`, dataset `friendbook_views`) | Every view event + the real de-duplicated count | 1 event per view; ≤ 1 query per isolate per minute |
| Workers KV (`VIEWS`, key `views:live:v1`) | Snapshot shared across isolates | 1 read per isolate, ≤ 288 writes/day per isolate |
| Worker memory | What a request is actually served from | free |
| D1 | **Not written.** One bounded, read-only seed — only if AE *and* KV are both empty | 0 writes, ≤ 500 rows read once per isolate, only as a last resort |

`served total = max(pre-AE history, AE uniq(uid)) + views counted in this isolate since AE last answered`

* The second term is the bridge over AE's few seconds of ingestion lag, so a view is visible the
  moment it happens instead of a minute later.
* On every AE refresh, whatever AE absorbed is subtracted from that bridge
  (`bridged = sinceAe - (aeBase - previousAeBase)`), so a caught-up answer never double counts and a
  lagging answer never makes a number dip.
* The KV snapshot stores `max(history, AE uniq)` when AE is answering — the de-duplicated truth, never
  one isolate's temporary bridge. Without AE it stores the live total, because then nothing else
  records it.
* A cold isolate reads KV once, then asks AE once. Only if both come back empty does it run the
  read-only seed `SELECT resource, views … ORDER BY views DESC LIMIT 500` and cache it in KV, so the
  numbers users already saw survive a lost KV namespace. That is the only D1 statement left.

### De-dupe across isolates, honestly

Memory settles repeats instantly: a user this isolate has already counted gets `counted: false` with
no storage call at all. If an isolate is brand new (a deploy, a scale-out) and a user restarts their
app and re-watches the same Reel, that isolate cannot know them, so the **live** number may lead by
one until the next AE answer re-bases it. The durable record — AE's `uniq(uid)` and the KV snapshot —
is always exact, and the Android client displays counts monotonically, so a number never visibly
drops. The client also posts a given Reel at most once per app session, which keeps this rare.

## Querying Analytics Engine

Which AE query API an account can use is not ours to pick, so the Worker tries, in order, and
remembers what worked for the life of the isolate:

1. `GET /client/v4/accounts/{id}/analytics_engine/sql?query=…` with
   `SELECT index1 AS resource, uniq(blob3) AS viewers, count() AS plays FROM friendbook_views WHERE
   timestamp >= now() - INTERVAL 90 DAY GROUP BY index1 ORDER BY viewers DESC LIMIT 500`
   (then `uniqExact`, then without the time window, then `count()`), parsed from TSV *or* JSON.
2. `POST /client/v4/graphql` — the `viewer.accounts.<dataset>` query with
   `dimensions { name count uniq }`.

Reads need `CF_AE_TOKEN` (an **encrypted Worker secret**, never a plaintext `--var`) and
`CF_ACCOUNT_ID`. Without them the Worker still writes every event and still counts in KV + memory,
reporting `"storage": "kv+memory"` — and still writing nothing to D1.

Because the exact AE answer shape can only be confirmed against the real account, each deploy runs
`cloudflare/report-views-ae.mjs`, which fetches the deployed `/health` (proving which bindings
shipped) plus the raw SQL and GraphQL responses, and refreshes a single marked comment on the open
pull request. That comment is the verification record.

## Fallbacks — a refused binding must never break a release

* `cloudflare/ensure-views-ae.mjs` inserts `[[analytics_engine_datasets]] binding = "AE"
  dataset_name = "friendbook_views"` **before** the `# Existing owner-provided bucket.` anchor,
  because the deploy step's R2-scope fallback truncates `wrangler.toml` there.
* The deploy step retries progressively: full config → without R2 → without Analytics Engine →
  without either. Each retry only edits the CI working copy; the repo keeps the real configuration.
* `/health` reports `views: { ae, kv, aeReadToken }` (booleans only), so a deploy can be checked
  without authenticating.

## Contract

```
POST /v1/views/{posts|reels}/{id}  -> { resource, views, counted, dedupe: "once-per-user", storage, event }
GET  /v1/views/{posts|reels}/{id}  -> { resource, views, storage }
GET  /v1/views?collection=reels&ids=a,b,c -> { collection, views: { id: n }, storage }   (<= 50 ids)
GET  /v1/views?collection=reels&top=25    -> { collection, views: { id: n }, storage }   (cap 50)
```

`storage` is `ae+kv+memory`, `kv+memory` or `memory` — whatever this isolate can really use.
`event` is `analytics-engine` when the view event was accepted, `none` when there is no AE binding.
Every route requires a verified Firebase ID token; Firebase stores no view data.

## Budget comparison

For 1,000,000 view POSTs from 100,000 people (200,000 of them first-time views of a resource):

| | v2.8.74 (D1 per view) | v2.8.76 (D1 de-dupe + hourly totals) | now (Analytics Engine) |
| --- | --- | --- | --- |
| D1 row reads | 1,000,000 | 0 | **0** (plus ≤ 500 once per isolate only if AE *and* KV are empty) |
| D1 rows written | 2,000,000 | 200,000 + hourly totals | **0** |
| AE events | – | – | 1,000,000 (one per view request) |
| AE queries | – | – | ≤ 1,440/day per isolate |
| KV reads / writes | 0 | 1 per isolate / ≤ 288 per day | 1 per isolate / ≤ 288 per day |
| Counted views | 1,000,000 | 200,000 | **200,000** (`uniq(uid)`) |
| Home Feed cost | 1 batched read per load | 0 | **0** |

## Tests

* `cloudflare/test/view-counters.test.js` — 12 tests with a fake AE binding, a fake KV, a stubbed AE
  query API and a D1 that counts statements: one event per view with **zero D1 statements prepared**,
  one view per user across ten watches, AE `uniq` re-basing a cold isolate, the bridge never dipping
  or double counting, one AE query per minute deferred to `ctx.waitUntil`, GraphQL-only accounts,
  no-AE fallback, the read-only last-resort seed, the KV throttle, batched/top reads, fail-closed
  validation and auth, and the deployed route plus `/health` bindings through `createWorker`.
* `scripts/tests/test_cloudflare_view_counters.py` — source guards: no `INSERT`, `UPDATE`, `DELETE`,
  `CREATE TABLE`, `.run()`, `ON CONFLICT` or `ensureSchema` may appear in `view-counters.js`; the AE
  binding must sit before the R2 anchor; CI must bind AE, store the token as a secret and report raw
  answers; the Home Feed must contain no views client at all.
