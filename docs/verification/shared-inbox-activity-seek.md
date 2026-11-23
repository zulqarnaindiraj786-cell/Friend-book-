# Adaptive shared inbox activity seek

## Alternative to rejected per-member activity fanout

A single partial expression index orders canonical root-chat paths by the same derived inbox activity expression, then document ID. It does not create per-user rows or triggers, replace the membership table, remove existing indexes, or change stored documents. The index is restricted by `path='chats/'||doc_id`; nested messages and non-chat documents are excluded.

The runtime uses two plans:

1. Cold/small/unknown inboxes keep the membership-first query. Its existing D1 `meta.rows_read` is observed; there is no extra COUNT query.
2. When a previous sort was sufficiently costly, a shared-index head may be tried. Head size is at most 64, at most floor(previous measured reads / 4), and never below the requested page size. Index, canonical row, membership and ACL work have conservative per-candidate headroom in this planning heuristic. It is not a billing guarantee or daily forecast.

The shared query checks source-1 membership in the same statement and authorizes each candidate against current canonical room membership before filling the page. It returns a page only when enough authorized results exist or the global cursor-bounded stream is exhausted. Otherwise the exact membership query supplies the page. A failed/sparse/unsupported seek is disabled for that actor for ten minutes to avoid repeated bounded-probe overhead. Hints expire, are bounded to 512 actors per DB binding, and contain numeric costs only, not documents or membership permissions. Stale hints affect performance only.

Value cursors use expression-index range bounds. Unicode tie ambiguity, nonstandard paths, unsafe-integer activity/cursors, missing indexes and unsupported specs preserve compatibility evaluation. SQL returns activity as REAL to avoid local SQLite unsafe-integer conversion errors, while the shared ORDER BY/range predicates use the exact indexed expression. This preserves JS-rounded activity ties through the compatibility path.

## Actual D1 evidence before runtime deployment

Index safety run: https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45181654683 SUCCESS, completion 2026-11-23T13:13:18Z, source 666fa94.

Isolated disposable-table billing test (not whole-app message billing):

| Update | Additional billed rows written from shared index |
|---|---:|
| Direct chat activity | +1 |
| 50-participant group activity | +1 |
| Root chat metadata, unchanged activity | 0 |
| Profile | 0 |
| Nested message | 0 |

Both probe stages used D1 `meta.rows_written`, not local total_changes. Cleanup confirmed. There is no member-count-dependent write fanout. Activity updates are not free: +1 index write remains a deliberate tradeoff.

One-time index CREATE: 20,429 reads / 1,096 writes / `changes=0`; no user DML. Total first safety operation including probes: 21,630 reads / 1,144 writes. Only the exact reviewed index was installed; broad migration history was not swept.

Second verification: https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45182159492 SUCCESS, source dcafb03. Aggregate-only bounded probe over the live ordered index examined 64 candidates with **64 D1-reported reads / 0 writes**. It forced canonical-document and membership expression evaluation but returned only aggregates, never documents/UIDs. The synthetic actor had no matching memberships, so this is not a measured authenticated user's filled inbox page or matched-session savings. Ordered EXPLAIN uses the new index without a temporary sort. Reverification operation total: 1,269 reads / 48 writes, disposable table cleanup confirmed.

## Local full regressions

271 Worker / 382 Python tests PASS, schema/migration/index-plan suites PASS, 30-user network-disabled Worker replay PASS. New coverage includes:

- 1,000-room dense fixture: bounded shared 64-document head replaces full membership candidate sorting after an expensive-cost hint, with identical page/cursors.
- Index ordering/range plans without temporary sorting.
- Small/cold inbox: no added count/probe query; actual query metadata warms the next plan.
- Sparse head: exact page plus cooldown; no repeated shared attempt.
- Revocation on the next request despite a warm hint.
- Missing index, Unicode ties, unsafe JSON-integer ties/cursors and historical timestamp compatibility.
- Bounded/expiring actor hints, adaptive budget and binding/actor isolation.

## Acceptance limits

This fixes the expensive/dense inbox path with a bounded adaptive alternative; it does **not** promise every query can avoid a membership scan. Sparse/unsupported/cold cases still legitimately use that plan. A shared attempt may add bounded overhead on its first failure. Production query analytics must establish how often the faster plan is selected and the real workload's read/write balance. Full-day <=100k reads / <=5k writes remains unverified; index build/probe usage is included in today's consumption, not hidden as app savings. No merge, Android rebuild or Play upload is part of this work.
