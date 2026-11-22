# Avoid duplicate canonical unread-notification index

Two canonical partial indexes had the same recipient key and `isRead=false` predicate. `idx_cf_notif_unread` also carries ordered creation-time fields for bounded notification pages; `idx_cf_inbox_unread` is the older recipient-only prefix. Both were maintained on each new unread canonical notification.

Simply dropping the shorter index is **not** safe for quota: SQLite can choose the recipient-only all-notifications index for the older unbounded unread fallback, scanning read notifications. Compatibility Worker `712e1fb` first pins that fallback to `idx_cf_notif_unread`, with a fallback to the original query if that index is absent. Bounded pages, recipient authorization, canonical shadowing and legacy/wrapped data paths remain unchanged. Legacy unread indexes are retained because wrapped records use different predicates. Migration 0081 then removes only `idx_cf_inbox_unread`.

## Local verification

- Miniflare D1, 2,000 synthetic notifications with 1,000 for the tested recipient and 100 unread: the old indexed generic unread query reported **101 rows read**; the pinned replacement reported **101**, before and after the drop. Dropping the old index without the hint caused the planner to choose the all-recipient index and report **1,001 reads**. Each returned the same 100 rows. This is a synthetic direct SQL measurement, not an account-wide billed total.
- With the real Worker, local Miniflare reported create-only unread notification **1 read / 19 writes / 4 statements** before dropping the old index and **1 / 18 / 4** after. Marking it read was **4 / 12 / 4** in both cases; duplicate create remained **2 / 0 / 2** and returned 409. No FCM push was sent. The small-schema local `DROP INDEX` reported 234 reads / zero writes; actual live migration cost and elapsed time are unknown.
- A real-Worker SQLite regression used 1,000 canonical, mirrored and legacy notifications plus a wrapped historical record. It checked standard and fallback pages, cursors/deltas, counts, unread/read filtering and another recipient's privacy before and after removal, the pinned seek plan, repeatable migration and fallback when the new index is missing. An existing schema test confirms the legacy unread index remains.
- 189 Worker tests, nine schema/plan tests and 280 script tests passed. The offline replay ran 35,489 synthetic requests with 63 query plans and no EXPLAIN errors; its unchanged SQLite statement/changed-row results do not include D1 index write accounting. There was no production fake traffic or new Android bundle.

## Rollout and rollback

Compatibility Worker deployment 44986601115 and validation 44986601140 succeeded before the migration. Android verification 44986601144 hit a Robolectric Maven-fetch IOException; Android source was unchanged, and the migration deployment must run a fresh verification.

Roll back the Worker to `712e1fb` or a newer compatible version. Before running older Worker code that does not pin the replacement index, recreate the old index in a controlled migration, allowing for index-build quota and time:

```sql
CREATE INDEX IF NOT EXISTS idx_cf_inbox_unread
  ON friendbook_cf_firestore_documents(json_extract(document_json,'$.toUserId'))
  WHERE parent_path='notifications' AND json_type(document_json,'$.isRead')='false';
```

Even with the compatibility Worker deployed first, a pre-compatibility request still in flight during the index drop could use the recipient-only plan and temporarily read more rows. Older external SQL consumers may also choose different plans. The change does not guarantee unlimited quota or zero duplicate reads.
