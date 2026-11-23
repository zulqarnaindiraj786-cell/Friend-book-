# Inbox document materialization and direct-room write follow-up

## Runtime changes

Common root-chat inbox queries now use a membership-first SQL candidate query ordered by the same derived activity timestamp. Only page-sized full documents cross into the Worker. Authoritative stored membership is checked before filling the visible limit; stale entries are skipped/refilled. Four refill batches cap repeated sorts before returning to the existing compatibility evaluator. Missing index/table, unusual IDs, complex filters/order/cursors and mismatched timestamp semantics retain that evaluator. Native numeric, typed timestamp, zero-fallthrough and timestamp truncation behavior match the existing decoder. No mirrors/UNION are introduced.

The SQLite query still reads/sorts the user's matching membership candidates; it is **not** an indexed activity seek or a demonstrated D1 read reduction. A 240-room fixture materializes 10 full documents for a 10-room page rather than all 240. Historical/unsupported queries may still load the full inbox. This is bounded common-path Worker materialization, not a universal bounded-scan claim.

Existing canonical direct-room writes now guard both document JSON and revision inside the canonical upsert. The canonical and mirror statements remain in one D1 transaction. This removes the temporary guard INSERT and DELETE (four top-level statements become two in the regression fixture). Group authority checks, new-room/orphan-history guards and genuine mirror writes remain unchanged. Concurrency failures and mirror failures cannot acknowledge or commit stale data. Physical billed writes also depend on indexes/triggers; two fewer top-level statements are not a measured billing result.

## Tests

- 258 Worker tests PASS.
- 375 Python tests PASS.
- Schema, migration and query-plan suites PASS (2/3/4 tests).
- New inbox coverage: 240 rooms, ascending/descending, tie-break/value/deleted-anchor cursors, stale memberships, next-request revocation, timestamp variants and Unicode compatibility.
- New direct-room coverage: two-statement canonical/mirror transaction, outsider rejection, membership race, revision-only race, and mirror-trigger failure rollback.

## Remaining evidence

Deployment and post-rollout measurements must be recorded separately. Caller-level attribution of historic mirror shape totals remains unresolved; no necessary consistency write was removed based solely on SQL counts. A complete post-rollout day meeting 100k reads / 5k writes is still required. No merge or Play upload is performed.
