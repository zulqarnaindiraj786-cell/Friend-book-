# Owner-reported zero-baseline spike

Owner confirms the attached dashboard started at zero immediately after quota reset and reached 20.5k rows read / 504 rows written / 2.44k queries after roughly five minutes of their use. Treat this as a serious reported regression, not evidence of achieved quota acceptance. The screenshot alone does not expose routes or exact UTC start/end.

Fresh analytics-only run **45296106849 SUCCESS**, source 91881a6. Provider-reported end **2026-11-24T00:18:45Z**:

| Lookback | Reads | Writes | Executions |
|---|---:|---:|---:|
| 5m (00:13:45 onward) | 263 | 0 | 166 |
| 15m (00:03:45 onward) | 8,811 | 819 | 2,312 |
| 60m | 9,303 | 855 | 2,524 |

One observed primary database; aggregates/shape lists not flagged truncated. The newest five minutes do not capture the earlier reported activity spike. The screenshot and API totals are **not reconciled**: windows, capture time and possible analytics aggregation delay must not be assumed equal. These analytics are not a reason to dismiss the owner's zero-baseline statement or a device-level trace.

15m leading read shapes:
- Friends compound canonical selection: **14 executions / 2,800 reads / 2,772 rows returned**.
- Users list: **55 executions / 1,273 reads / 1,273 returned**.

15m leading writes:
- Snapshot-guarded canonical insert: **65 executions / 455 writes** (root not attributed by sanitized shape).
- Notification mirror upsert: **31 executions / 174 writes**, writer family document:notifications. Canonical and mirror shapes are not proven one-to-one matched operations.

Source finding: the friends branch of readCollectionRows loads the actor's whole two-position friendship candidate set before the JS exact-pair filters. FriendsFragment.checkCandidates issues batched exact-pair whereIn queries, so this route can repeatedly materialize the same graph while checking small candidate batches. Analytics identify the shape, not which caller produced the fourteen executions. Existing one-position indexes do not prove that adding a counterpart SQL predicate alone reduces scanned rows; a speculative materialization-only patch was discarded before commit. A proper bounded-pair access-plan fix needs indexed-seek and ACL/result-parity evidence, not another returned-row-only saving claim.

No new application patch, APK, deployment or migration in this investigation. Analytics attribution tests **26 passed**. Next priorities are exact-pair friendship query access plans and canonical notification/write attribution, rather than repeating old engagement-only assumptions. Production quota remains **NOT accepted**.
