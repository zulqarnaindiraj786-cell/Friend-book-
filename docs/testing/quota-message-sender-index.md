# Removing the unneeded message sender index

Migration 0079 drops `idx_cf_message_sender`, a partial index maintained for every canonical document whose `collection_id` is `messages`, including nested chat messages. The Worker scopes chat document queries by `parent_path` and applies general sender filtering in its existing query logic; the index has no parent-path prefix and is not referenced by an `INDEXED BY` query. This migration does not remove messages or change authorization, ordering, push or Android code.

## Local verification

- Real Worker with all preceding migrations and 1,000 synthetic messages across rooms: ordinary and sender-filtered pages, a cursor and a non-member request produced identical results and the same 19 captured SELECT plans before and after removal. The unauthorized request returned 403. The ordered page and parent-path indexes remained in use. The automated test also checks repeatable removal and restoration.
- Real Worker with local Miniflare D1 and the deployed schema: the same normal direct send went from **4 reads / 12 writes / 5 statements** to **4 / 11 / 5**. A direct document receipt-style update went from **1 / 4** to **1 / 3**. The update measurement is a direct SQL fixture, not a complete Android receipt workload.
- In the small empty-schema fixture, local `DROP INDEX` reported 236 rows read and zero rows written; this does not predict production DDL charges or elapsed time. D1 billing and index maintenance can differ by workload and deployment; these are local D1 metadata, not account-wide billed totals. No production test messages were generated.
- Full local validation: 183 Worker tests, 9 migration/plan tests, 280 script tests and the 1,000-user offline replay (63 recorded query plans, no plan errors) passed. These cover other normal workloads but cannot prove every possible external query or actual device behavior.

## Rollback

If a verified production query unexpectedly needs this index, recreate it with a controlled migration, after considering index build cost and available quota:

```sql
CREATE INDEX IF NOT EXISTS idx_cf_message_sender
  ON friendbook_cf_firestore_documents(json_extract(document_json,'$.senderId'),create_time DESC)
  WHERE collection_id='messages';
```

Rebuilding can scan existing data and consume quota; do not perform it merely to test the rollback on production.
