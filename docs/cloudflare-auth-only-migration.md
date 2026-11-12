# Firebase = authentication only. Everything else lives on Cloudflare.

This line of the app (`com.example.frienbook`) still reads and writes most social data in Firestore.
That ends, collection by collection, with one rule: **Firebase supplies the ID token and nothing
else.** The Cloudflare Worker (`rough-firefly-dd7b`, binding `DB` = D1, `VIDEO_BUCKET` = R2,
`ANALYTICS_ENGINE` = dataset `friendbook_views`) verifies that token and owns the data.

Nothing is deleted, reset or re-imported destructively: the Cloudflare copy is **additive** (merge
upserts, no deletes), Firestore keeps serving reads until a read path is explicitly switched, and
every switch ships behind a build flag so a batch can be turned off without a release.

## Where each thing lives today

| Data | Today | Cloudflare home | Status |
| --- | --- | --- | --- |
| Sign-in, ID token | Firebase Auth | — (stays) | **permanent** |
| Reel/Post view counts | nowhere (never counted on this line) | Analytics Engine events + `count(DISTINCT blob3)` | **batch 1 — live**: `CloudflareViews`, one real second of watching = one view per user, forever |
| New posts & reels documents | Firestore (source of truth) | D1 `friendbook_cf_documents` via `PUT /v1/docs/...` | **batch 1 — live**: mirror on create, fire-and-forget |
| Videos / photos | Firebase Storage / Cloudinary | R2 `friendbook` | Worker endpoints live (`/v1/videos`, `/v1/photos`); client switch = batch 3 |
| Likes / comments / reactions | Firestore | D1 interaction tables (`/v1/engagement/...`) | Worker + migrations live; client switch = batch 2 |
| Posts & reels list reads | Firestore snapshot listeners | `GET /v1/docs?collection=...` | batch 2 (union read, then Firestore off) |
| users / friends / friend_requests / notifications / stories / groups / pages / lives / pokes / official_broadcasts | Firestore | `friendbook_cf_documents` (all in the Worker's allowlist) | batch 3–4: mirror on write, then switch reads |
| chats / messages | Firestore (+ Supabase push gateway) | `friendbook_cf_documents` mirror; push gateway **stays on Supabase** | batch 4 |
| payments / subscriptions / referrals / boosts / reports / feedback / config / mail / events / sessions | Firestore | not mirrored yet (money & safety records stay dual-written until a reviewed batch) | batch 5, reviewed separately |

Firestore usage counted in this line before batch 1: `users` 71 call sites, `posts` 28, `chats` 18,
`friend_requests` 14, `lives` 12, `friends` 12, `comments` 11, `notifications` 10, `reels` 7, …
Each batch shrinks this table and the guards in `scripts/tests/test_cloudflare_view_counters.py`
pin what has already moved, so nothing silently slides back.

## Batch 1 (this change)

- `core/CloudTransport.java` — one bounded HTTP door to the Worker; Firebase appears only as
  `getIdToken(false)`.
- `core/CloudflareViews.java` — POST one real play to `/v1/views/{collection}/{id}` after one second
  of watching; read totals batched (≤50 ids, 60 s cache); totals only ever grow on screen.
- `core/CloudflareDocs.java` — `mirror(collection, id, fields)` PUTs the document to D1 (merge
  upsert, Firestore sentinels sanitized); `list(...)` is ready for the batch-2 read switch.
- `ReelsFragment` — eye glyph + live count on the rail, count scheduled at 1 s, cancelled on pause;
  totals fetched in one request per page. The Home Feed issues **zero** views requests.
- `AddReelActivity` / `CreatePostActivity` — every new Reel/Post is mirrored to D1 the moment the
  Firestore write succeeds; a Worker outage never blocks posting (the next edit re-mirrors).

## Honesty notes

- Until a read path is switched, Firestore remains the source of truth for that collection; the D1
  mirror is a copy, not yet the authority. Claims like "views come from Cloudflare" are true from
  batch 1 because views never existed in Firestore on this line.
- The Worker, its D1 migrations (0001–0013, additive only) and its CI deploy chain are identical to
  the proven production code; `wrangler deploy --dry-run` reports `env.ANALYTICS_ENGINE
  (friendbook_views)` with no warnings.
- Auth never moves: no Firestore credential, no user password, no token ever reaches Cloudflare
  except the short-lived ID token the Worker verifies with Firebase's public keys.
