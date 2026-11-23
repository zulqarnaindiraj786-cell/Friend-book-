# Remove unscoped comment author index

Migration 0080 drops `idx_cf_comment_author(uid,created_at DESC)`. The application reads comments by resource or by resource plus ID; blocked-author Feed counts explicitly use the separate `idx_cf_comment_resource_author(resource,uid)`. The reply and page indexes remain. The migration deletes no comments and changes no authorization, counts, notifications, reactions or Android code. No production synthetic comments were created.

## Local verification

- On the preceding full local migration schema, Miniflare D1 and the real interaction handler reported **7 reads / 10 writes / 5 statements** for a new comment before, and **7 / 9 / 5** after dropping the index. An identical retry was **4 / 0 / 2** in both cases. These are synthetic local D1 metadata, not production billed usage.
- The automated real-handler test populated 120 canonical comments, blocked authors, removed comments, replies and old/duplicate legacy comments. It compared pages, before/after cursors, single lookups, Feed engagement counts and reactor reads, including blocked author privacy and all captured SELECT plans before/after. The resource/author, comment-page and reply indexes remained usable. It additionally tested create, duplicate retry with zero changes, conflicting retry, allowed and blocked replies, edit and remove after migration; the visible count stayed correct. The migration is idempotent, and local index restoration preserved read results.
- 184 Worker tests, 9 schema/plan tests and 280 script tests passed. An offline real-Worker replay executed 33,491 synthetic requests, recorded 63 query plans and had no plan errors. SQLite returned/changed-row numbers do not measure billed D1 index writes.
- A drop against the small local schema reported 236 D1 rows read / zero rows written; this does not bound live migration cost, elapsed time or future account-wide savings. External SQL consumers and real devices are not completely represented by these tests.

## Rollback

If a verified production consumer requires the old author-only seek, use a controlled migration to recreate the index:

```sql
CREATE INDEX IF NOT EXISTS idx_cf_comment_author
  ON friendbook_cf_comments(uid,created_at DESC);
```

Rebuilding scans existing data and consumes quota; do not rebuild solely to test rollback on production.
