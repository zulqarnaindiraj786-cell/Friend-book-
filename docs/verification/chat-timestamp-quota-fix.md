# Active write-cost fix: scope the timestamp index to root chats

## What changed

Migration `0090_scope_chat_timestamp_index.sql` replaces the global canonical `(collection_id,update_time DESC)` index with:

`idx_cf_chat_root_updated(parent_path,update_time DESC) WHERE collection_id='chats' AND parent_path='chats'`.

Runtime inventory found the global timestamp order is needed by the root-chat list. Keeping its global form causes unrelated document inserts and applicable timestamp updates to maintain it too. Live-chat anti-spam uses its separate sender/time index, which remains intact.

This is a production schema optimization, **not just a monitoring/reporting change**. No app code or user records were modified. No new APK is required for the backend index change.

## Production rollout proof

[Rollout 45121610265 — PASS](https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45121610265)

Source `65bfb09561764291e47584a7e5767f5648856780`; completed at returned timestamp **2026-11-23T06:03:58Z**.

- The exact partial replacement was built before the old global index was dropped.
- Forced new-index compilation succeeded before retirement.
- The natural root-chat query changed from `SEARCH ... idx_friendbook_cf_firestore_collection_updated (collection_id=?)` to `SEARCH ... idx_cf_chat_root_updated (parent_path=?)`.
- No temporary sorting tree was needed after replacement.
- Live sender/time throttle query plan was unchanged.
- An automatic restore path exists if post-retirement plan validation fails; it was **not needed**.
- Remote metadata file size 59,863,040 bytes was below the conservative 80 MiB index-build guard. This is a coarse file-size safety guard, not a billed-row bound.

Reported one-time SQL costs (not recurring):

| Statement | Reads | Writes |
|---|---:|---:|
| Schema inspection | 294 | 0 |
| Build partial chat index | 20,140 | 1,091 |
| Retire global timestamp index | 591 | 1 |
| All EXPLAIN probes | 0 | 0 |
| **Reported SQL total** | **21,025** | **1,092** |

No additional full-table diagnostic count/preflight was performed. CREATE INDEX itself scans/builds data and consumes quota; it is not a free operation. Historical daily counters will include these rollout costs.

## Actual D1 billing proof, with strict scope

[Isolated billing test 45121843452 — PASS](https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45121843452)

Source `a006464c79be8269962623718f754b61f8eb18d9`.

A unique, disposable table in the same D1 database modeled the old/new timestamp indexes. It contained two synthetic rows per stage, **not application tables or user data**. The table was dropped and its absence was verified.

| Same synthetic insert | Old global index | New partial index |
|---|---:|---:|
| Non-chat row | **3 D1 writes** | **2 D1 writes** |
| Root-chat row | **3 D1 writes** | **3 D1 writes** |

These are **D1 API `rows_written` counters**, not local SQLite opcodes or logical changes. The probe proves the tested index maintenance costs less for the non-chat insert while root-chat writes do not increase. It does **not** prove 33% savings for real notification writes, the whole app, updates, or daily usage: the actual application schema has other indexes and triggers.

Total one-time probe cost, including schema checks and cleanup: **1,490 reads / 19 writes**. `cleanup_verified=true`; `daily_application_savings_proven=false`.

## Validation

- Production rollout CI: **241 Worker tests + 360 Python tests PASS**.
- Latest full local suite after billing-probe tests: **363 Python tests PASS**.
- Schema 2, migration 3, and index-plan 4 tests PASS.
- Real Worker offline replay with 30 synthetic users: 48 workload groups PASS; network disabled.
- Application compile in rollout CI and independent [Worker validation 45121618490](https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45121618490): PASS.
- Billing-probe tests check unique ownership, cleanup after an INSERT failure or lost CREATE response, and rejection of unsafe identifiers. Logical SQLite counts do not falsely pass as D1 billing savings.

A local prototype without `parent_path` as the leading index key was rejected by the plan test because SQLite chose a sort. The corrected migration passed before production execution. Historical owner-index tests now explicitly build their pre-0089 fixture so later index changes cannot contaminate that separate regression; assertions were not removed.

## Remaining limits

**The end-to-end daily quota target is not proven solved.** This is a third targeted production fix with a bounded billing proof, not an unconditional guarantee.

The strict verification workflow now uses **2026-11-23T06:03:58Z** as the latest rollout boundary; it will not pass a partial day or a day that straddles the fixes. This update does not rerun expensive production probes. The old daily-failure report remains a historical snapshot, not a post-0090 measurement.

The traffic classifier recognizes the disposable billing table's query shapes as diagnostics so they are not mislabeled as ordinary app traffic. The isolated monitoring Worker remains observe-only and cannot auto-edit the app, guarantee a quota cap, or retroactively reduce historical counters.

Application Worker/cron unchanged, reporter cron unchanged, PR #36 unmerged, no billing upgrade, no feature cutoff and no Android release in this rollout.
