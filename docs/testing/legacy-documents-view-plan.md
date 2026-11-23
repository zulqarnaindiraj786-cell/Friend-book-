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

## Batch 1 + 2 result (this pass)

With `readPreferredDocumentJson` and `/v1/docs` canonical-only, 19 tests went red.
Four of the five files are now converted and green in isolation:

| File | Fix |
| --- | --- |
| `call-relay-quota` | mirror-only peer now asserted invisible (fail-closed `null`), canonical peer asserted working |
| `live-pk-room-quota` | read counter retargeted from the `UNION ALL` arm to the single canonical lookup |
| `fcm-push` | recipient `users/bob` gets its canonical twin (`mirrorLegacy`) |
| `http-e2e` | `putDoc` writes both rows, so canonical-only reads see the fixture |

Remaining: `firestore-documents` 13 families. The cause is **not** seeding alone —
the error contract changed:

* a mirror-only document now answers **404** where the old fallback answered 403
  (`existing friendships are read-only…`, `comments and reactions authorize parent…`);
* content ACL (`contentReadAllowed`) authorizes from `readPreferredDocumentJson`,
  so a legacy-only post is filtered out and the query legitimately returns `[]`
  (`bounded posts/reels pages…`, `indexed tag search…`, `Home story expiry…`);
* blanket `mirrorAllLegacy` is wrong: 9 extra tests deliberately seed stale
  mirrors that must stay hidden, and they regress (69/20).

So the 13 need per-test judgement: mirror the rows that stand for "already
backfilled history", keep stale-shadow rows unmirrored, and rewrite the
`403 → 404` assertions to the new contract.

## Batch 3 (firestore-documents): 13 → 9 families

Fixed and verified in isolation:

* `Firestore collection queries and counts…` — `mirrorAllLegacy` after the historical seeds.
* `Firestore-style query cursors…` — same, plus the deletion fixture now clears both stores.
* `existing friendships are read-only…` and `stories load with owner and privacy…` —
  history mirrored, so the participants' reads resolve canonically.

Still red, with the reason each one needs its *own* rewrite (blanket mirroring is
not the answer, it regresses 9 shadow tests):

| Test | Why it still fails |
| --- | --- |
| `bounded posts/reels pages …` | premise is now obsolete: it asserts `shadowChecks === 2` for "100 unknown paths", but with history backfilled there are **no** unknown paths, so the correct expectation is 0 probes |
| `indexed profile and request reads …` | asserts canonical precedence over a legacy copy; precedence is now unreachable |
| `comments and reactions authorize parent …` | parent authorization is canonical-only, so legacy parents answer 404 |
| `story engagement …`, `live room creation …`, `indexed tag search …`, `copied D1 timestamps …`, `Home story expiry …` | same class: legacy-only rows used as authorization sources, plus plan-level counters (`legacyProbes`, `shadowChecks`, `fastCalls`) that assumed the fallback query shape |

Only `app-documents.js` is changed in this pass; everything else stays at the
current green baseline until all nine are converted.
