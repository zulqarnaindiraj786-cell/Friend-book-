# Option 2 — legacy documents table → compatibility view (spike result)

Goal: make `friendbook_cf_documents` stop being a second store, so that
**1 Read = 1 Read** and every `UNION ALL` / `NOT EXISTS` fallback arm becomes
dead weight that can be deleted.

## Verified mechanism (node:sqlite, real migration files)

```sql
ALTER TABLE friendbook_cf_documents RENAME TO friendbook_cf_documents_archive;

INSERT OR IGNORE INTO friendbook_cf_firestore_documents
  (path, parent_path, collection_id, doc_id, owner_uid, document_json, create_time, update_time)
SELECT path, collection,
       substr(collection, length(rtrim(collection,
              'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-')) + 1),
       doc_id, owner_uid, document_json, updated_at, updated_at
FROM friendbook_cf_documents_archive;

CREATE VIEW friendbook_cf_documents AS
  SELECT path, parent_path AS collection, doc_id, owner_uid,
         document_json, update_time AS updated_at
  FROM friendbook_cf_firestore_documents;
```

Plus `INSTEAD OF INSERT / UPDATE / DELETE` triggers on the view that write
canonical storage only (the INSERT trigger carries its own
`ON CONFLICT(path) DO UPDATE` so plain inserts upsert).

What the spike proved, all executed against the real `0011` + `0014` schemas:

| Operation through the view | Result |
| --- | --- |
| `INSERT ... VALUES (6 positional values)` | OK → canonical row, correct `parent_path` / `collection_id` |
| repeated `INSERT` of same path | OK → upserted (trigger body) |
| `INSERT OR REPLACE` | OK |
| `UPDATE ... SET document_json, updated_at` | OK → canonical `document_json` / `update_time` |
| `DELETE ... WHERE path` | OK → canonical row removed |
| archive backfill with nested path `chats/r/messages/m` | `collection_id = 'messages'` (rtrim trick) |

## Hard constraints found

1. `INSERT ... ON CONFLICT(...) DO UPDATE` against a **view** fails with
   `cannot UPSERT a view`. The 18 legacy-targeting upserts in
   `cloudflare/firestore-documents.js` must drop the clause (the view trigger
   already upserts). Canonical upserts (using `update_time`) stay as they are.
2. **Ordering**: 30+ migrations create `INDEX` / `TRIGGER ... ON
   friendbook_cf_documents`. They must all run *before* the rename+view step,
   so the swap has to be the last migration applied.
3. `UPDATE friendbook_cf_documents AS a SET ...` (friend-request acceptance,
   `firestore-documents.js` ~1990) needs checking against the view — aliasing a
   view in UPDATE is the first thing to re-test after the bootstrap change.

## Why the naive attempt failed

The test suite bootstraps its schema per file: 20 test files, 2 different
styles (a `appSchema` constant, or a `for (const file of [...])` list). Appending
the swap to the 0011 read puts the view in place *before* migrations 0017–0086
run, and those try to create indexes/triggers on the (now) view → schema errors
in ~60 tests.

## Next step (the actual work)

Introduce `cloudflare/test/fixtures/schema.js`:

```js
export function createTestDb(extra = []) {
  const sql = new DatabaseSync(':memory:');
  sql.exec(resourcesSchema);            // all migrations in order
  for (const f of extra) sql.exec(readMigration(f));
  sql.exec(readMigration('0087_legacy_documents_view.sql')); // swap last
  return sql;
}
```

then point the 20 test files at it, land migration `0087`, drop the 18 UPSERT
tails, and only after the suite is green start deleting the 83 `UNION ALL` /
`NOT EXISTS` arms (they become no-ops the moment the view is in place).

## Measured blast radius (every attempt reverted to keep the suite at 234/234)

| Attempt | Change | Result |
| --- | --- | --- |
| A. Point reads canonical-only (`readPreferredDocumentJson` + `readRootStorageRow`) | 2 functions | **32 red** |
| B. Legacy table → compatibility view + `INSTEAD OF` triggers | migration + 18 UPSERT tails stripped | mechanism OK, **60+ red** (30+ migrations build index/trigger on the table, so the swap must be last) |
| C. Bidirectional mirror triggers (canonical ⇄ legacy, no drift possible) | migration only | mechanism OK — heal, c→l insert/update/delete, l→c insert/update/delete all verified — **81 red** because 81 tests deliberately seed drift |
| D. `/v1/docs` + `readPreferredDocumentJson` canonical-only alone | 1 file | **19 red** in 5 files (`firestore-documents` 13, `http-e2e` 3, `call-relay-quota` 1, `fcm-push` 1, `live-pk-room-quota` 1) |
| D+. D plus blanket `autoMirror` on those 5 files | test seeding | worse (35 red) — the fixtures build their own D1 wrappers |

Conclusion: the production change is one function; **the work is the test contract**.
The suite is written around drift between the two stores (precedence, dual-store
guards, "legacy-only" rows). Step 1 has to be a per-file conversion, not a
mechanical patch.

### Suggested order

1. `call-relay-quota`, `fcm-push`, `live-pk-room-quota` — one test each, seed the canonical twin.
2. `http-e2e` (3 comment tests) + the 4 `comment-api.js` union sites.
3. `firestore-documents` — 13 test families, the bulk.
4. Only then delete the 75 arms in `firestore-documents.js` and re-run the 5-user budget.
