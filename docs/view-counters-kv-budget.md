# View counters: KV + Worker memory, D1 once an hour (zero D1 row reads)

**Status:** implemented in `cloudflare/view-counters.js`, deployed by
`.github/workflows/deploy-cloudflare.yml`. No user data was deleted, reset or migrated to get here.

## Why this exists

The first real-counter design stored every view in D1. One view cost **1 D1 row read** (the per-user
`friendbook_cf_view_last` stamp, `WHERE last_at <= ?`) plus **2 row writes**. D1's free tier allows
5,000,000 row reads/day, and a Reels feed that counts one view per second of watching burns through
that quickly — the reads were exhausted, so counters started failing.

The counting rule did **not** change: one second of real watching = one view, and the same user
watching again counts again. Only the storage did. Views are still real; nothing is synthesized.

## Where a view lives now

| Layer | What it holds | Cost |
| --- | --- | --- |
| Worker memory (`state.totals`) | The live total every request is served from | free |
| Workers KV (`VIEWS`, one key `views:live:v1`) | Aggregate snapshot shared across isolates | 1 read per isolate, ≤ 288 writes/day per isolate |
| D1 (`friendbook_cf_view_counters`) | Permanent history | **0 reads per view**, ≤ 1 write per resource per hour |

* **Counting a view:** memory increment. No D1 statement at all.
* **Serving a view** (single, batched `ids`, or `top`): memory read. No D1 statement at all.
* **Cold isolate:** one KV read of the aggregate key. Only when KV holds nothing (first deploy, or an
  evicted namespace) does the Worker run **one** bounded seed —
  `SELECT resource, views … ORDER BY views DESC LIMIT 500` — and write that seed straight back to KV,
  so it never has to read D1 again.
* **D1 write:** at most one per resource per hour, and only for resources that were actually counted
  (`state.dirty`). It is a single statement, `INSERT … ON CONFLICT(resource) DO UPDATE SET
  views = MAX(views, excluded.views) … RETURNING views`: no read-modify-write, the stored total can
  never go down, and `RETURNING` refreshes memory without a SELECT. A brand-new resource is written
  at once so a first Reel cannot be lost. A refused write keeps the resource dirty for the next hour.
* **KV write:** the aggregate key is rewritten at most every 5 minutes, throttled inside `flushKv`
  itself so no other path can add an unbudgeted write. ≤ 288 writes/day per isolate keeps three
  concurrent isolates inside the Workers Free limit of 1,000 KV writes/day.
* **Flushes never block a response:** they are handed to `ctx.waitUntil`, so the client gets its
  number while storage catches up. `worker.js` therefore passes its `ctx` into `viewCounters`.

If the KV namespace cannot be created (missing token scope, refused API call) the deploy continues
and the Worker reports `"storage": "memory"`. Counting still works and still costs **zero D1 row
reads**; only cross-isolate sharing is lost.

## Budget comparison

For 1,000,000 counted views across ~1,000 distinct resources:

| | old (D1 per view) | now (KV + memory) |
| --- | --- | --- |
| D1 row reads | 1,000,000 | 0 (plus ≤ 500 once per isolate, only when KV is empty) |
| D1 row writes | 2,000,000 | ≤ 24,000/day (1 per resource per hour) |
| KV reads | 0 | 1 per isolate |
| KV writes | 0 | ≤ 288/day per isolate |

The old design exceeded both D1 free limits; the new one stays under all of them.

## Provisions in CI

`cloudflare/ensure-views-kv.mjs` runs before `npm run deploy`: it reuses or creates the KV namespace
`friendbook-views` and inserts `[[kv_namespaces]] binding = "VIEWS"` into `wrangler.toml`. The block
is inserted **before** the `# Existing owner-provided bucket.` anchor because the deploy step's
R2-scope fallback truncates `wrangler.toml` at that anchor; a binding placed after it would be
silently dropped from the redeploy. Any failure prints a `::warning::` and exits 0 — a missing KV
binding must never fail a release.

## Contract (unchanged for clients)

```
POST /v1/views/{posts|reels}/{id}  -> { resource, views, counted, gapMs, storage, d1Pending }
GET  /v1/views/{posts|reels}/{id}  -> { resource, views, storage }
GET  /v1/views?collection=reels&ids=a,b,c -> { collection, views: { id: n }, storage }   (<= 50 ids)
GET  /v1/views?collection=reels&top=25    -> { collection, views: { id: n }, storage }   (cap 50)
```

Every route requires a verified Firebase ID token; Firebase stores no view data. Unknown ids answer
`0`, so the Android client keeps displayed counts monotonic
(`CloudflareViews.remember`, `FeedAdapter.refreshRealViewCounts`, `ReelsFragment.countRealView`):
a number a user has already seen never goes down.

## Tests

* `cloudflare/test/view-counters.test.js` — 12 tests on node:sqlite + a fake KV, counting every
  statement: zero SELECTs while counting/serving, one D1 write per resource per hour, MAX semantics,
  KV reseed on a cold isolate, one bounded seed when KV is empty, memory-only mode, the one-second
  floor, the KV throttle, `waitUntil` deferral behind a gated fixture, validation/auth fail-closed,
  migration repeatability, and the deployed route through `createWorker`.
* `scripts/tests/test_cloudflare_view_counters.py` — source guards so the D1-per-view design (and any
  per-view SELECT) cannot come back.
