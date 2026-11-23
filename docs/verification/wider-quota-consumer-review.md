# Wider ranked quota review — implementation and evidence remain separate

Analytics-only run https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45196130992 **SUCCESS**. Rolling 24h ended **2026-11-23T14:37:12Z**, spanning older application versions and engineering work. Query totals **1,093,438 reads / 63,073 writes / 121,260 executions**. Only primary D1 observed in the nontruncated account DB split; SQL shape lists are top-60/truncated. These are not completed UTC-day acceptance or device attribution.

The reporter now places ten read-ranked shapes and six write-ranked shapes before verbose notices (eight compact shape notices plus totals) for a single historical window. This prevents repeatedly examining only the two same historical shapes while GitHub hides other consumers. Multiple recent-window reporting retains two per metric/window. Output uses existing sanitized shape metadata; redacted grammar is bounded further in the priority view. New coverage plus full **397 Python tests PASS**. No D1 SQL or synthetic app traffic was used to obtain this report.

## Observed read ranking

| Rank | Shape family | Executions | Reads | Returned rows |
|---:|---|---:|---:|---:|
| 1 | Exact canonical coverage inventory | 9 | 169,534 | 11,798 |
| 2 | Bounded legacy-only inventory structure; exact caller still unproven | 112 | 151,719 | 112 |
| 3 | SELECT/LIMIT, table not mapped by current fixed vocabulary | 69 | 147,250 | 17,250 |
| 4 | Historical-comment CREATE INDEX | 4 | 77,280 | 0 |
| 5 | Transaction guard with indexed friendship candidate/exception logic | 129 | 73,563 | 0 |
| 6 | Engagement summary with comment previews/counts/reactions | 7,723 | 55,808 | 7,723 |
| 7 | Recognized diagnostic preflight | 1 | 29,931 | 1 |
| 8 | Canonical point lookup with LIMIT | 28,629 | 28,575 | 28,575 |
| 9 | Chat membership SELECT | 867 | 23,251 | 11,197 |
| 10 | Exact legacy coverage inventory | 1 | 21,420 | 20 |

These rows are ranked SQL shapes, not mutually exclusive application routes or user sessions. Necessary point lookups and ACL guards must not be disabled merely because they appear in a top list. Rank 3 remains unidentified; an empty table-label list means not mapped by the reporter, not a query without tables or a zero-cost database. Friendship guard source was inspected: it retains transactional duplicate detection, indexed participant candidates, canonical precedence and historical/malformed exception handling. A costly historical aggregate is not itself proof of an unnecessary guard or a current request loop; no security bypass was introduced.

## Observed write ranking (first four)

- Legacy-root promotion exact template: **45,581 writes / 5,078 executions** (already traced; completed-migration rerun guard is on this branch, not a global disable).
- Mirror INSERT: **3,420 writes / 380 executions**, still unattributed.
- Snapshot-guarded canonical INSERT: **2,266 writes / 298 executions**.
- Mirror upsert: **1,872 writes / 219 executions**.

The root coverage script's broad canonical count was already scoped in source to the 13 consumed roots with plan/parity regressions. It has not been rerun against production merely to create a savings demonstration. Historical index-build and migration costs cannot be erased by changing today's app.

## Outcome and boundaries

This continuation widened investigation, but did not establish a new unnecessary application loop or ship an app fix. Unknown consumers remain unknown. No production Worker deployment, schema migration, index removal, data deletion, APK/AAB, merge, acceptance-threshold change or backend proof-date reset occurred. No claim of 100% completion or automatic future monitoring is made. See `dominant-write-promotion-attribution.md` for exact-match versus structural-family limits and the failed repository-wide workflow-disable permission attempt.
