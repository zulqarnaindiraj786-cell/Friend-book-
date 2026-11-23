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

## Rank 3 traced to exact historical backup SQL

Follow-up analytics https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45196783791 **SUCCESS**, window ending **2026-11-23T14:40:52Z**, exact-template attribution confirms **147,250 reads / 69 executions / 17,250 returned rows** match:

`SELECT * FROM friendbook_cf_presence_sessions ORDER BY rowid LIMIT ? OFFSET ?`

This exact template is present in both `checksum_pages` and `write_backup` in `scripts/loadtest/backup_retired_tables.py` at historical commit `e305b41`. The classifier generates only the seven reviewed retired-table templates, requires exact normalized SQL including bind identities, and executes none of them. This proves a historical backup-template match, not a particular actor/run or active app presence polling. Current source replaced OFFSET with rowid seeks in **7f76bb4**; source tests already cover no OFFSET, sparse/negative rowids, checksum equivalence and cursor validation. An additional 5,000-row real SQLite regression now compares the entire historical/current row sets and requires current VM work below half of historical OFFSET work. VM steps are not D1 billed reads; this is prevention evidence, not newly realized production savings.

Privacy diagnostics recognize only reviewed SQL tokens/table names, redact arbitrary identifiers and all values, and preserve only allowlisted quoted identifiers. This continuation made no production app changes or table scans. Rank 3 is no longer unidentified. The 151,719-read inventory's exact caller and other residual costs remain unresolved; no 100% acceptance claim.

## Recent traffic separated from the historical engineering window

Analytics-only workflow now requests **5, 60, 1440 minutes**, preserving privacy, fail-closed handling and no production SQL. Run https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45197094544 **SUCCESS**, end **2026-11-23T14:42:40Z**:

- **5 minutes:** no observations available, totals `null`. This is not zero traffic or a latency guarantee.
- **60 minutes:** **1,376 executions / 3,057 reads / 47 writes**. Twenty-six query shapes, not truncated. One observed primary DB, no observed other-DB reads/writes in the account split.
- Recent largest read shapes: engagement summary **1,217 reads / 172 executions / 172 returned rows**, canonical point lookup **592 reads / 593 executions / 592 returned rows**.
- Recent largest write shape: canonical upsert **40 writes / 10 executions / 10 reads**. Other observed writes are not automatically waste.
- **1440 minutes:** **1,088,002 reads / 63,073 writes**, including the historical maintenance costs identified above; this shape list is truncated.

The recent engagement summary implementation was reviewed: indexed visible totals, blocked-author indexed deductions where required, scalar actor-reaction lookup, and a two-comment preview bound. Its observed cost alone does not demonstrate an unnecessary loop. Necessary ACL reads and real updates remain intact. Recent aggregate data cannot establish installed Android version, screen visibility, representative user activity, or distinguish all callers. It must not be multiplied into a promised daily total or used as a completed-day pass. The latest hour also straddles the deployed fix boundary; it is not an isolated post-fix cohort.

Validation: **283 Worker tests PASS; 401 Python tests PASS; diff check PASS**. Wrangler dry-run produced the Worker bundle and exited with `--dry-run: exiting now.` after the original shell command's collection timeout; no deployment was requested. Generated files stayed under `/tmp`, outside the repository. No app-source change, APK/AAB, migration, merge, or acceptance-boundary change occurred in this continuation. This is useful recent evidence, not a new root-cause fix or 100% completion.

## Related maintenance entry-point hardening

Source audit found three additional related production-maintenance entry points outside the already guarded bulk-root workflow: `canonical-inventory.yml`, `coverage-proof.yml`, and `legacy-user-backfill.yml`. They previously allowed commit-marker push execution on an older named branch, and coverage also allowed unconfirmed manual dispatch. This is an accidental-rerun prevention gap, **not evidence of an active current-branch app loop**.

All three now require **manual workflow dispatch plus explicit default-false `confirm_production_scan`**. User promotion retains seven separately selected preview/apply modes; its account reserves, tests, transactional source protections and noncancelling concurrency remain. The change removes all commit-marker conditions from these three workflows. No workflow was dispatched and no D1 query/write was executed for validation. This protects the reviewed source revision only; it is not a repository-wide workflow disable and cannot remove triggers from old refs or queued runs.

Validation: **402 Python tests PASS**, including trigger/default/gate/mode regressions. All three workflow files also parsed with PyYAML BaseLoader and passed structural checks for manual-only trigger and fail-closed job gate. YAML parsing is not a substitute for future GitHub execution, and no production migration was run merely to test it. App features, SQL, 15-minute cron, APK/AAB pause, unmerged PR and production Worker version remain unchanged. Exact caller of the 151,719-read inventory still unproven.
