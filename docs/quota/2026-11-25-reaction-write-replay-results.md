# Reaction write replay results

Date: 2026-11-25

The new `cloudflare/test/reaction-write-budget.test.js` test ran in GitHub Actions workflow run [45600565147](https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45600565147), and the full Cloudflare Worker validation job passed.

## Local SQLite fixture results

| Workload | SQL statements | Returned rows | Changed rows including triggers |
|---|---:|---:|---:|
| First Like | 4 | 1 | 2 |
| Repeat same Like | 4 | 1 | 0 |
| Change Like to Love | 4 | 1 | 3 |
| Clear reaction | 4 | 1 | 2 |
| Legacy unknown-history transition | 4 | 1 | 3 |
| First Like with notification | 5 | 1 | 3 |
| Duplicate Like + notification retry | 5 | 1 | 0 |

These are **SQLite fixture measurements**, not Cloudflare D1 `rows_written` or `rows_read` billing values. Trigger behavior, D1 implementation, account plan, and production workload must be correlated separately.

## What this establishes

- Repeating an identical desired reaction state is a true no-op in the fixture: zero changed rows, including triggers.
- Repeating the same compact Like + notification request is also a true no-op: the inbox event is not duplicated.
- Actual changes and explicit legacy timestamp repair do write rows; those paths must not be disabled.
- SQL statement count is not a write-row count. Four statements did not imply four changed rows.

## Follow-up

Do not lower the conservative daily reaction-write assumption based only on this local fixture. First collect a representative Android action mix (new reactions, repeats, changes, clears, and notifications) and correlate it with D1 billed rows for the exact same time window. Preserve the legacy timestamp correction and notification idempotency behavior.
