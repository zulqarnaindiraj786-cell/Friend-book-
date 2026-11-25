# Canonical notification recipient index retirement

## Why this target

Live analytics 45306363373 identified create-only notifications as the leading returned six-hour write shape: 147 executions / 1,029 reported row-writes. This does not establish duplicated events. The safe target is redundant index maintenance, not suppressing genuine notifications or removing ACL checks.

`idx_cf_firestore_inbox_recipient` stores the recipient expression with partial predicate `parent_path='notifications'`. `idx_cf_notif_page` already has the exact same recipient expression as its leftmost key and identical partial predicate, followed by ordered-page keys. Current compatibility notification reads explicitly pin the ordered replacement. Legacy, unread, exception and page indexes are retained.

## Applied state and live proof

- Source ce134c0: migration0093, bounded retirement guard, regression tests and targeted Actions workflow.
- Actions45307291181 SUCCESS: full regression gates followed by live exact-DDL and EXPLAIN gates and idempotent targeted retirement.
- Initial run log download failed with the known blob-host EOF. Added bounded fixed-label notices in e773230 instead of claiming unseen log contents.
- Actions45307401390 SUCCESS: **canonical prefix already absent; replacement verified; no mutation**. Exact replacement DDL and recipient seek verified. Metadata lookup reported295reads/0writes; EXPLAIN reported0reads/0writes. No notification data scanned by these live verification queries.
- The remote script rejects missing/different replacement DDL, different old-index DDL or a non-seek plan. It accepts an already-retired index only after verifying the replacement.
- No full migration replay, application-data deletion, Worker redeploy, APK build or PR merge. Production Worker code remains6dfdebb; schema now confirms the redundant canonical recipient prefix absent.

## Offline correctness and cost evidence

- 299 Worker tests PASS, including actual Worker privacy, count, cursor, wrapped-record and canonical-shadowing equivalence with prefix indexes absent.
- 433 Python tests PASS; schema2/migration3/index-plan4 PASS; actual Worker/local SQLite replay gate PASS.
- 5,000-notification fixture: identical recipient results before/after; actual pinned compatibility request uses recipient SEARCH on idx_cf_notif_page; one fewer SQLite IdxInsert opcode for an ordinary canonical notification insert.
- One fewer index-maintenance opcode is local structural evidence, not a measured Cloudflare billed-write reduction or proof of7→6 production billing.
- Initial unpinned synthetic plan chose the generic parent index, so it was not accepted as proof. The index-plan test now mirrors the current application's explicitly pinned compatibility request. Existing actual Worker tests exercise the unhinted ordered-page routes as well as compatibility/privacy behavior. Missing-schema fallback tests remain and no new fallback/UNION path was added.

## Limits

This closes the reviewed redundant canonical prefix, not every write/read consumer or the owner's post-reset incident. Six-hour analytics is mixed-window, not matched before/after. Fresh real-user writes after retirement and a representative corrected-client day still must establish actual quota savings and <=100k reads/<=5k writes acceptance. Previously verified Android fixes remain outside APK178; no packaging-pause bypass or merge occurred.
