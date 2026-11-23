# Quota target proof — FAIL, not guaranteed solved

> Historical verification snapshot from before migration 0090. See [latest timestamp-index fix and billing proof](chat-timestamp-quota-fix.md). The latest rollout boundary is now 2026-11-23T06:03:58Z; no complete post-0090 day is proven here.

## Result

**Production daily target: FAIL. Code validation: PASS.**

The acceptance target used in this project is **100,000 reads / 5,000 writes per UTC day**. It is not asserted to be the account's Cloudflare plan limit.

Evidence: [strict verification run 45119704081](https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45119704081), source `3ab635d8f9459c1c17d3b437d700ca88d6b53778`.

The workflow intentionally exited **2** on target failure. Tests were not bypassed; the failed production acceptance was not turned green.

## Actual production evidence

Cloudflare account-adaptive daily analytics, queried at provider/runner timestamp **2026-11-23T05:45:34.691857Z**:

| Returned UTC day | Reads | Writes | Daily target comparison | Comparable full post-fix day? |
|---|---:|---:|---|---|
| 2026-11-21 | 3,269,075 | 111,664 | FAIL | No, before fixes |
| 2026-11-22 | 2,260,402 | 105,706 | FAIL | No, before fixes |
| 2026-11-23, incomplete | 470,482 | 6,383 | FAIL | No, contains earlier activity |

For the incomplete returned day, the target is exceeded by **370,482 reads / 1,383 writes**. Index changes cannot retroactively remove that usage.

These are delayed/adaptive **whole-account** totals: all databases and administrative work are included. They are not device attribution, an app-only cost, a transactional quota balance, or proof of the account's configured plan limit. Do not compare historical full days with today's partial day as a savings percentage.

The latest targeted index rollout completed **2026-11-23T05:19:12Z**, independently confirmed by its Actions job metadata. No complete UTC day wholly after those fixes is available in this report. A partial day under target would remain UNVERIFIED rather than PASS.

## Validation performed

| Check | Outcome |
|---|---|
| Worker regression tests | 235 PASS |
| Python regression tests | 358 PASS |
| Real Worker offline replay: 10 synthetic users | PASS, 48 workload groups |
| Real Worker offline replay: 30 synthetic users | PASS, 48 workload groups |
| Real Worker offline replay: 100 synthetic users | PASS, 48 workload groups |
| Worker dry-run compilation | PASS; not deployed |
| Production daily acceptance target | **FAIL** |

All replays disable network access and use local SQLite, real Worker handlers and real migrations. Eleven duplicate/retry workload groups retain zero logical row changes at each tested size. This does not establish D1 billed read/write counts, capacity, or that every possible retry is free.

The `quota-target-proof` Actions artifact contains the three offline JSON reports and `production-daily-verdict.json` (7-day retention). Production checks used GraphQL only: **no D1 SQL, user-row changes, diagnostic scans, application requests or deployments**.

## Fixes already active, with limited claims

1. Exact pending friend-request cleanup index: the read-only empty-pair predicate measured **499 -> 0 D1 reads**. This is not a whole-account savings measurement.
2. Redundant global owner index retired: production compiled inserts have one fewer index-maintenance opcode; required auth/live/chat query plans were preserved. Billed daily write savings remain unverified.
3. Expensive friendship diagnostics require explicit scan opt-in; the original full-scan baseline is separately opt-in and omitted from its workflow.

No application feature was silently disabled. PR #36 remains unmerged. No new Android release, billing upgrade, quota cutoff or Worker cron change was performed by this verification.

## Guarantee boundary

**No unconditional future quota guarantee is supported by this evidence. The quota task remains unresolved against its end-to-end acceptance target.**

The verifier fails closed: missing analytics stays UNVERIFIED, an incomplete day cannot pass, a day straddling the rollout cannot prove post-fix compliance, and observed target excess stays FAIL. Even a later complete day within target would be labeled OBSERVED_WITHIN_TARGET—not a promise about unlimited future traffic.
