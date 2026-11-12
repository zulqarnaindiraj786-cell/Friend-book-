# Firebase = authentication only. Everything else lives on Cloudflare.

## What changed on this line

`app/src/main/java/com/example/frienbook/core/cloud/` is now the app's database client: a small
HTTP client for the Worker's `/v1/docs` API, shaped like the SDK it replaced (`Database`,
`CollectionReference`, `Query`, `DocumentReference`, `DocumentSnapshot`, `QuerySnapshot`,
`FieldValue`, `FieldPath`, `Timestamp`, `WriteBatch`, `Transaction`, `SetOptions`,
`ListenerRegistration`) so 283 call sites moved by changing their imports, not their logic.
`Fb.db()` returns it. `firebase-firestore` is gone from `app/build.gradle`; `firebase-auth` stays,
and it is the only Firebase product the app uses.

| Data | Where it lives | How |
| --- | --- | --- |
| Sign-in, ID token | Firebase Auth | the token is also what authenticates every Worker call |
| posts, reels, stories, comments, notifications, friends, requests, chats + messages, users, payments, config | Cloudflare D1 `friendbook_cf_documents` | `core/cloud` → `GET/PUT/PATCH/DELETE /v1/docs/...`, `POST /v1/docs/batch` |
| videos, photos, voice notes | Cloudflare R2 `friendbook` | `core/CloudflareMedia` → `/v1/videos`, `/v1/photos` |
| view counts | Cloudflare Analytics Engine | one event per view, `count(DISTINCT blob3)`, zero D1 writes |
| presence, typing | D1 (`friendbook_cf_presence`, `_typing`) | `core/Presence` → `/v1/presence/...` |
| runtime switches (ads kill switch) | Worker vars | `core/CloudConfig` → `/v1/config` |

## The three product rules, as they hold now

1. **New data goes to Cloudflare.** There is no second store to write to: every create and edit in
   the app is a D1 write. Views are Analytics Engine events and never touch a document.
2. **A user who never updates keeps their data.** Nothing in this migration deletes, resets, prunes
   or rewrites a legacy document - the legacy store is only ever *read*, by
   `cloudflare/legacy-migration.js`, and only with the reading user's own token. Old builds keep
   running against the legacy store exactly as before.
   **Honest consequence:** because new builds no longer dual-write, an old build stops seeing what a
   new build creates. Old-build users lose nothing they already had; they simply stop receiving new
   content until they update. That is the trade the owner's "Firebase se Firestore hatao" instruction
   implies, and it is stated here rather than hidden.
3. **A user who updates gets their history carried over.** `CloudMigration.runOnceAfterUpdate`
   (once per versionCode, flag written before the work) posts `/v1/migrate/legacy`. The Worker reads
   that user's own slices - profile, posts, reels, stories, comments, notifications, requests both
   directions, friendships, chats and the last 50 messages of each - and writes them into D1.
   Bounded at 100 rows/page, 3 pages/slice, 50 chats, 1000 documents per run. Every write is
   `ON CONFLICT(path) DO NOTHING`: a document the user has edited since updating is never replaced by
   the older copy, and a tombstone is never resurrected. A slice the legacy store refuses comes back
   in `errors` with `ok:false` instead of a silent half-migration.

## What the client does not pretend

- A page is 50 rows (the Worker's ceiling), so a larger `limit` is fetched as cursor slices; a list
  of 13 friends is still one request. `limitToLast(n)` reads the newest *n* in the opposite direction
  and reverses them, which is what "the last n in this order" means.
- A snapshot listener is a poller on the Worker's change feed (`since=`), merging by id: changed rows
  update in place, deleted rows leave the list, and a page is never re-downloaded. Chat threads and
  live rooms poll at 2.5 s, feeds and admin tables at 8 s.
- A 404 becomes `exists() == false`; a 403, a 503 and "no network" stay errors, with the Worker's own
  reason as the message. Nothing is retried behind the caller's back and no value is invented.
- `FieldValue` markers travel as `ops`, so increments and array unions are applied by the Worker to
  the stored row (compare-and-swap) and cannot be lost to a second writer. Reactions move the like
  counter with `increment`, not with a number the phone calculated.
- A `WriteBatch`/`Transaction` is one all-or-nothing Worker call of up to 20 operations; a larger
  batch is sent as successive calls, and the first failure is reported rather than papered over.
- `CloudflareDocs.mirror` is now a documented no-op and `CLOUDFLARE_DOCS_MIRROR` is `false`: the write
  it used to duplicate is the write that already happened.

## Guards

`scripts/tests/test_cloudflare_view_counters.py` (32) and `npm test` (83) pin this: no Firebase
database/analytics/storage/config dependency, no `com.google.firebase.firestore` import anywhere in
`app/src`, the carry-over's bounds and its insert-if-absent write, "legacy store is read, never
written", the endpoint sitting behind the identity check, and every query the app runs being on the
Worker's allowlist.

The Play Store line (`com.friendbook.social`) receives this through the controlled port described in
`docs/handoff-social-line-port.md` - same rules, same guards, monotonic versionCode, same signing.
