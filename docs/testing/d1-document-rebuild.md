# D1 → document store rebuild (posts first, then the other 9)

Option A of the migration: **do not wait for Firestore**. The rows already inside D1
(`cloudflare/migrations/0014_additive_core_schema.sql`) are converted into the
Firestore-shaped document store (`friendbook_cf_documents`, migration `0011`) that
`/v1/docs` and `firebase-post-access.js` already read.

Everything runs inside the deployed Worker/D1. No Firebase call, no data export, no
change to the Android app, and `CLOUDFLARE_DATA_ENABLED` stays as it is.

## Rules

- **`posts` is the test collection.** The other nine stay blocked (HTTP 409) until the
  live posts gate passes: posts rows > 0, document count ≥ row count, **every** posts row
  has a document at `posts/<id>` (exact SQL count, not a sample), and the first three
  posts rows round-trip field by field. A half-finished paging run therefore never
  unlocks the other collections.
- **Dry run by default.** `POST /v1/admin/migrate/documents/rebuild` writes nothing unless
  `{"apply": true}` is sent.
- **Existing documents are kept.** Writes use `INSERT … ON CONFLICT(path) DO NOTHING`,
  so preserved Firestore staging documents are never replaced unless
  `{"overwrite": true}` is sent explicitly.
- **Admin secret only.** `MIGRATION_ADMIN_TOKEN` (Worker secret). Unset → 503,
  wrong/missing token → 401. A Firebase user token is never accepted here.
- **Nothing is deleted, ever.** Source tables are only read. Firebase is untouched.

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/v1/admin/migrate/documents/plan` | per-collection row counts, doc counts, pending, gate |
| `POST` | `/v1/admin/migrate/documents/rebuild` | convert one page (`collection`, `apply`, `overwrite`, `limit`, `after`) |
| `GET` | `/v1/admin/migrate/documents/verify?collection=posts&sample=40` | counts + field-by-field round trip |

These live inside the existing migration API, so they use its authentication unchanged:
`Authorization: Bearer <MIGRATION_ADMIN_TOKEN>`, where the token must be at least 32
characters. No token configured → 503, missing header → 401, wrong token → 403. A Firebase
user token is never accepted. `worker.js` needed no routing change: `/v1/admin/migrate/*`
was already dispatched before Firebase token verification.

`limit` defaults to 30 rows per request so a Workers **Free** account stays inside the
50-queries-per-invocation D1 limit. Paid accounts can pass up to 100. The response
returns a keyset `nextCursor`; the runner script loops until it is `null`.

## Field mapping

| Rule | Example |
|---|---|
| snake_case → camelCase | `author_photo` → `authorPhoto`, `is_admin` → `isAdmin` |
| document id | `posts.id`, `users.uid`; users also get `uid`, every doc gets `id` |
| JSON text columns are parsed | `media_urls` → array, `reactions_map` → object |
| NULL/invalid JSON uses the column default | array columns → `[]`, object columns → `{}` (counted as a warning) |
| 0/1 columns become real booleans | `archived`, `banned`, `is_read`, `forwarded` … |
| `updatedAt` | the row's own timestamp (`created_at`, chats: `last_message_time`), so `/v1/docs` ordering is deterministic and a re-run is idempotent |
| `owner_uid` | `author_id` / `uid` / `sender_id` / `admin_id` / `from_uid` / `to_user_id` / `created_by` |
| unknown columns | kept as-is under their camelCase name, so an added column is never dropped |
| document paths | flattened to `collection/id` (comments and messages are addressed by their own id), the same one-level shape `/v1/docs` and the existing staging documents use |

Booleans matter for authorization: `firebase-post-access.js` checks Firestore
`booleanValue` for `banned`, `deactivated`, `archived`, so leaving them as `1/0` would
silently deny or allow the wrong users.

## Collections (10)

| # | Collection | Source table | Owner column | Gate |
|---|---|---|---|---|
| 1 | `posts` | `posts` | `author_id` | test collection |
| 2 | `users` | `users` | `uid` | needs posts gate |
| 3 | `comments` | `comments` | `author_id` | needs posts gate |
| 4 | `reels` | `reels` | `author_id` | needs posts gate |
| 5 | `stories` | `stories` | `author_id` | needs posts gate |
| 6 | `chats` | `chats` | `created_by` | needs posts gate |
| 7 | `messages` | `messages` | `sender_id` | needs posts gate |
| 8 | `groups` | `groups` | `admin_id` | needs posts gate |
| 9 | `friend_requests` | `friend_requests` | `from_uid` | needs posts gate |
| 10 | `notifications` | `notifications` | `to_user_id` | needs posts gate |

`active_calls` and `user_settings` are intentionally not converted: calls are live
signalling, not Firestore documents, and `/v1/me` uses `friendbook_cf_profiles`.

## Runbook

1. **Secret already in place.** The rebuild uses the same `MIGRATION_ADMIN_TOKEN` (≥ 32
   characters) that the other `/v1/admin/migrate/*` routes already require, so nothing new
   is needed if those work. Until it exists every rebuild route answers 503.
2. **Check the plan.** `python3 scripts/admin/rebuild_documents.py plan` — this prints
   the row count per table. If the total rows are 2775 and `posts` is the largest, the
   counts match what we expected; if not, stop and compare before converting.
3. **Dry run posts.** `python3 scripts/admin/rebuild_documents.py run posts` — reads and
   maps every post, writes nothing. `converted` must equal `scanned` unless there are
   oversized/invalid rows, which are listed in `errors`.
4. **Apply posts.** `python3 scripts/admin/rebuild_documents.py run posts --apply`.
   `skippedExisting` counts documents that were already there (kept). Only pass
   `--overwrite` if those preserved documents are known to be stale.
5. **Verify posts.**
   `python3 scripts/admin/rebuild_documents.py verify posts --sample 40` → `mismatchCount`
   must be 0, `documentRows` ≥ `sourceRows`, `sourceRowsWithoutDocument` must be 0. Then open the app feed / `/v1/docs?collection=posts`
   and confirm real posts render (author, text, media, counters, timestamps).
6. **Only after that**, run the remaining nine:
   `python3 scripts/admin/rebuild_documents.py run all --apply`. The gate is checked on
   every request; a failing gate exits with code 2 and nothing is written.
7. **Verify each collection** with `verify <collection>` and re-run `plan` — `pending`
   should be 0 everywhere.

GitHub Actions: `.github/workflows/rebuild-d1-documents.yml` (workflow_dispatch,
`collection` + `mode` + typed `APPLY` confirmation). It is only listed once it exists on
the default branch, and it never runs on push.

## How this relates to the other migration scripts on this line

- `scripts/d1_coverage.py` measures overlap between the two shapes, per collection
  (`only_in_table` is exactly the work this rebuild recovers). It stays read-only.
- `scripts/plan_missing_documents.py` plans Firestore → D1 copies of documents D1 lacks.
  This rebuild is the other direction: D1 tables → documents, no Firestore at all.
- `migration-api.js` `mirrorDocuments()` writes documents while migrating from Firestore and
  only replaces an existing document when the incoming copy is newer. The rebuild does not
  reuse that guard: it never replaces an existing document unless `overwrite` is explicit,
  because the source rows here are the same data the app has been writing, not an older copy.

## Before the first apply

Take a D1 export/Time Travel bookmark. The rebuild never deletes documents, but a
per-collection rollback would have to delete documents, and a rebuild-written document
is not distinguishable from an app-written document with the same path. Prefer
`--overwrite` only with a backup in hand.

Rollback for a single collection (only with a backup, never blindly):

```sql
DELETE FROM friendbook_cf_documents
WHERE collection = 'posts'
  AND path IN (SELECT 'posts/' || id FROM posts);
```

## Limits and open points

- Documents larger than 95,000 bytes are skipped and reported (`document-too-large`);
  they need a separate reviewed path, matching the existing `/v1/docs` PUT limit.
- `comments` documents are stored but are **not** added to the `/v1/docs` allowlist, so
  the public read surface is unchanged.
- The rebuild does not mark `friendbook_cf_interaction_resources` rows `verified`;
  interaction cutover keeps its own gate.
- `CLOUDFLARE_DATA_ENABLED` and both D1 interaction switches are untouched, and Firebase
  data is neither read nor deleted by this step.
