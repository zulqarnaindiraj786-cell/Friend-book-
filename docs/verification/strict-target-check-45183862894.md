# Strict quota target verification — run 45183862894

Run: https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45183862894

Source 489ee5a. Analytics queried_at: 2026-11-23T13:26:33.640599+00:00. Latest fix boundary: 2026-11-23T13:24:18+00:00.

**Overall strict verdict: FAIL.** Failure is preserved; no thresholds, boundaries or results were relaxed.

| UTC date | Account reads | Account writes | Complete day | Entirely after latest fixes | Daily target result |
|---|---:|---:|---|---|---|
| 2026-11-21 | 3,269,075 | 111,664 | Yes | No | FAIL |
| 2026-11-22 | 2,260,402 | 105,706 | Yes | No | FAIL |
| 2026-11-23 | 636,015 | 14,160 | No | No | FAIL |

User acceptance is <=100,000 reads and <=5,000 writes/day, not Cloudflare plan limits. The reported current-day total exceeds those targets by 536,015 reads and 9,160 writes. This is delayed/adaptive account-wide daily analytics, including all databases and maintenance/index/probe work. Do not subtract unlike statement/probe metrics or label these totals exclusively Android app usage.

The current UTC day straddles earlier versions and the final rollout. Its strict FAIL does not isolate the new runtime's traffic or prove that its post-fix rate still exceeds target. It also cannot establish acceptance. There is no measured completed day entirely after the final fix boundary in this report. The first eligible full UTC day is 2026-11-24, after that day completes at 2026-11-25T00:00:00Z and analytics is available; this is an eligibility statement, not a promise of automatic future execution or a guarantee of passing.

CI Worker/Python regression step, offline actual-Worker replays at 10/30/100 users, production dry-run compilation and evidence artifact upload all PASS. Only the strict daily analytics target step FAILS. The artifact `quota-target-proof` retains JSON evidence despite failure.

Verification performed no production D1 SQL, synthetic app requests, deployment, migration, PR merge or Android rebuild. The live runtime remains fb5c3d7 from successful deployment 45183463849.
