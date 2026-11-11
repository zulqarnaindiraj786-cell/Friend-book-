# View counters: zero D1 row reads, one view per user, Home Feed costs nothing

**Status:** implemented in `cloudflare/view-counters.js` (Worker), `ReelsFragment` (counting) and
`FeedAdapter` (Home Feed), deployed by `.github/workflows/deploy-cloudflare.yml`.
No user data was deleted, reset or migrated to get here.

## The two rules this design must satisfy

1. **Cost:** counting or showing a view must cost **zero D1 row reads**. The Home Feed must cost
   **nothing at all** — no views request, no D1 read/write, no KV read/write.
2. **Counting:** **ONE view per user per video, forever.** One second of watching is enough;
   watching the whole video, looping it, or coming back next month adds nothing. Views stay real —
   nothing is synthesized, and no counter lives in Firestore (Firebase is auth only).

## What each screen does

| Screen | Views request | D1 read | D1 write | KV read | KV write |
| --- | --- | --- | --- | --- | --- |
| **Home Feed** (posts, PYMK, featured Reels card, ads) | none | 0 | 0 | 0 | 0 |
| **Reels tab**, first second of a Reel nobody in this isolate saw | 1 POST | 0 | 1 de-dupe row (0 rows if that user already counted) | 0 | ≤ 1 per 5 min, shared |
| **Reels tab**, same Reel again (loop, re-watch, re-scroll) | none (client skips it) | 0 | 0 | 0 | 0 |

The featured Reels card on the Home Feed no longer shows the 👁 number, and the code that read it is
deleted — not hidden. `FeedAdapter` contains no views client call at all, which a source guard
enforces. Post Insights still shows a real number: the post's own stored `views` value, already in
memory with the document, with no network call. The card still rotates to a fresh viral Reel
(`pickFeaturedReel()` scores stored model fields).

## Where a view lives

| Layer | What it holds | Cost |
| --- | --- | --- |
| Worker memory (`state.totals`, `state.counted`) | Live totals + "this uid already counted" cache | free |
| Workers KV (`VIEWS`, one key `views:live:v1`) | Aggregate snapshot shared across isolates | 1 read per isolate, ≤ 288 writes/day per isolate |
| D1 `friendbook_cf_view_last` | One row per (resource, uid) = that user's ONE view | 1 row per **first-time** viewer, 0 rows for repeats, **0 reads** |
| D1 `friendbook_cf_view_counters` | Permanent totals | ≤ 1 write per resource per hour, **0 reads** |

* **De-dupe without reading:** the check *is* the write —
  `INSERT INTO friendbook_cf_view_last(resource, uid, last_at) VALUES (?,?,?)
  ON CONFLICT(resource, uid) DO NOTHING`. `meta.changes === 1` means this user had never counted for
  this resource, so the view counts; `0` means D1 already had the pair, so nothing is added and no
  row is written. A refused write returns 503 `Counter busy` — a view is never invented.
* **Memory first:** once an isolate has settled a (resource, uid) pair it answers repeats from memory
  with no storage call at all. The cache is bounded (`MAX_COUNTED_ENTRIES = 8000`, oldest dropped
  first); an eviction only costs one no-op insert later, never a double count.
* **Totals:** served from memory. A cold isolate does **one KV read**; only when KV is empty (first
  deploy, evicted namespace) does it run **one** bounded seed
  `SELECT resource, views … ORDER BY views DESC LIMIT 500` and write that seed straight back to KV.
* **Hourly totals write:** one statement per resource per hour, only for resources actually counted
  (`state.dirty`): `INSERT … ON CONFLICT(resource) DO UPDATE SET views = MAX(views, excluded.views)
  … RETURNING views`. No read-modify-write, the stored total can never go down, and `RETURNING`
  refreshes memory without a SELECT. A brand-new resource is written at once; a refused write stays
  dirty for the next hour.
* **KV write:** the aggregate key is rewritten at most every 5 minutes, throttled inside `flushKv`
  itself so no other path can add an unbudgeted write (≤ 288/day per isolate keeps three concurrent
  isolates inside the Workers Free limit of 1,000 KV writes/day).
* **Flushes never block a response:** they go to `ctx.waitUntil`, so the client gets its number while
  storage catches up. The de-dupe insert *is* awaited, because it decides whether to count.

If the KV namespace cannot be created (missing token scope, refused API call) the deploy continues
and the Worker reports `"storage": "memory"`. Counting and de-dupe still work through D1 and still
cost **zero row reads**; only cross-isolate snapshot sharing is lost.

## Budget comparison

For 1,000,000 view POSTs from 100,000 people, of which 200,000 are first-time views:

| | v2.8.74 (D1 per view) | v2.8.75 (KV totals, re-watches counted) | now |
| --- | --- | --- | --- |
| D1 row reads | 1,000,000 | 0 | **0** |
| D1 rows written | 2,000,000 | ≤ 24,000/day | 200,000 one-time de-dupe rows + ≤ 24,000/day totals |
| Counted views | 1,000,000 | 1,000,000 | **200,000** (one per user per video) |
| Home Feed cost | 1 batched read per load | 1 batched read per load | **0 requests** |

Reads were the exhausted limit (5,000,000/day); they are now zero on every path. Writes stay far
inside the 100,000/day free allowance because the rule itself counts far fewer views, and a repeat
watcher writes nothing.

## Provisions in CI

`cloudflare/ensure-views-kv.mjs` runs before `npm run deploy`: it reuses or creates the KV namespace
`friendbook-views` and inserts `[[kv_namespaces]] binding = "VIEWS"` into `wrangler.toml`. The block
goes **before** the `# Existing owner-provided bucket.` anchor because the deploy step's R2-scope
fallback truncates `wrangler.toml` there; a binding after it would be dropped from the redeploy. Any
failure prints a `::warning::` and exits 0 — provisioning KV must never fail a release.

## Contract

```
POST /v1/views/{posts|reels}/{id}  -> { resource, views, counted, dedupe: "once-per-user", storage, d1Pending }
GET  /v1/views/{posts|reels}/{id}  -> { resource, views, storage }
GET  /v1/views?collection=reels&ids=a,b,c -> { collection, views: { id: n }, storage }   (<= 50 ids)
GET  /v1/views?collection=reels&top=25    -> { collection, views: { id: n }, storage }   (cap 50)
```

Every route requires a verified Firebase ID token; Firebase stores no view data. Unknown ids answer
`0`, so the Android client keeps displayed counts monotonic (`CloudflareViews.remember`,
`ReelsFragment.countRealView`): a number a user has already seen never goes down.

## Tests

* `cloudflare/test/view-counters.test.js` — 12 tests on node:sqlite + a fake KV that classifies every
  statement: zero SELECTs while counting/serving, one view per user forever (including a cold isolate
  a month later, refused with no read), hourly totals write with MAX semantics, KV reseed, bounded
  seed when KV is empty, memory-only mode, KV throttle, `waitUntil` deferral behind a gated fixture,
  validation/auth fail-closed, migration repeatability, and the deployed route through `createWorker`.
* `scripts/tests/test_cloudflare_view_counters.py` — source guards: the Home Feed must contain no
  views client or endpoint at all, the de-dupe must be `ON CONFLICT … DO NOTHING` (never an update,
  never a select), the one-second floor must stay gone, and totals must stay on the hourly window.
