# Live quota diagnosis — investigation, not savings proof

Cloudflare returned telemetry timestamp 2026-11-23 (provider timestamp).

## Measured evidence

- Account daily analytics at 05:00:11 UTC: 369,424 rows read / 5,279 rows written.
- Subsequent snapshot at 05:02:24 UTC: 370,971 / 5,372. These delayed/adaptive account totals are not a five-user breakdown or remaining quota balance.
- 15-minute query analytics: social friendship guard INSERT shape involving both stores: 69,912 reads / 72 writes / 72 executions (~971 reads/execution).
- Duplicate friend-request UPDATE shape: 11,856 reads / zero writes / 24 executions (494 reads/execution).
- Chat membership SELECT: 2,118 reads / 6 executions.
- Separate query-adaptive and account-adaptive datasets must not be equated.

## Production configuration verified, not assumed

Read-only workflow 45115304953 succeeded:
- Deployed FRIEND_GUARD_INDEXED is `true`.
- All six required participant/exception index names exist in production.
- Active deployment reported created_on 2026-11-23T02:48:39.845649Z.
- Schema inspection cost exactly 300 reads / zero writes, changed_db=false.
- No user documents fetched, migration, deployment, application traffic or schedule changes performed.

This rules out an absent flag or absent index names as the simple explanation. It does NOT establish index definitions, actual query plans, exception cardinality, deployed source identity, or which guard predicates dominate the observed cost. Missing-index fallback is not a proven root cause. Next investigation should compare deployed query shape/plans and request lookup indexes before proposing a production change. Preserve legacy authorization semantics and atomic guards.

Evidence:
- https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45115076235
- https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45115076427
- https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45115304953

No measured savings claimed. Previous 11,535/1,495 budget is a model only. PR #36 remains open/unmerged. Signed APK/AAB delivery does not demonstrate backend quota improvement.

## First targeted production fix applied

Source `82f19112bd4516ef9fd80350beb483656ce0d1c3`, workflow
https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45116051702
completed **success**.

Root cause for the legacy duplicate-request cleanup: its field-first COALESCE
expressions do not match the existing raw-first COALESCE / CASE expression
indexes. Production's before plan could seek only `collection=?`, scanning the
request collection even when the pair had no matches.

Applied only additive migration `0088_pending_duplicate_request_seek.sql`.
No Worker deployment, document mutation, cron change, merge, or Android release.
The partial index contains pending requests only. New/changed pending requests
incur index maintenance; this is not a promise of lower writes for every action.

Measured production **read-only aggregate using the exact UPDATE WHERE clause**
with reserved diagnostic identities (not an app request or a real-user UPDATE):

| Probe | Before | After |
|---|---:|---:|
| Empty-pair predicate D1 reads | 499 | 0 |
| D1 writes for predicate | 0 | 0 |
| Query plan | collection-only seek | exact from/to expression seek |

This proves the empty-match scan was removed for that predicate. It is not yet
ordinary-traffic UPDATE telemetry, whole-account savings, or proof that the
larger friendship guard is fixed. Daily cumulative totals cannot fall retroactively.

One-time rollout costs, reported by D1:
- Safety preflight: 10,900 reads / 0 writes; 10,900 sampled rows; no invalid JSON.
- Index creation: 11,243 reads / 333 writes; zero document changes.
- Before predicate: 499 reads / 0 writes. Explain and after predicate: 0 / 0.

Validation in the production rollout workflow: Worker 234 PASS, Python 343 PASS,
schema 2 PASS, migrations 3 PASS, index plans 4 PASS. Tests execute the unchanged
production UPDATE and compare data with/without the index across raw, wrapped,
conflicting, partial envelope and shadowed records. Local VM instructions fell
5,589 -> 1,132; these are NOT billed D1 reads. No Android files changed; prior
Android debug/JVM and signed release gates remain the Android evidence.

Further deployed-source inspection run 45115967160 succeeded and confirmed
indexed guard/source markers and expected friendship index definitions. Earlier
source inspection run 45115869496 failed because `/content` was not the correct
Worker source endpoint; corrected to the script endpoint. No state changed.

## Larger guard: bounded profiling, not another speculative rewrite

Read-only profiling runs 45116187660 and 45116284005 succeeded. Each executed
one capped aggregate preflight (29,931 reported reads); each found 521 canonical
friends / 160 legacy friends, zero exception/malformed rows, and 29,930 total
sampled documents. These diagnostics themselves consume reads and must not be
mistaken for ordinary app traffic. Do not repeatedly rerun full-table preflight.

Same reserved nonexistent pair on production, zero writes:
- Malformed exception probe: 2 reads.
- Indexed candidate aggregate: 8 reads.
- Indexed duplicate guard: 10 reads, allowed=1.
- Original full duplicate guard: 1,883 reads, allowed=1.

This confirms the indexed path can be cheap on this data. It was already present
in production; **do not attribute its advantage to migration 0088**. A pair with
many relationships may cost more. Do not weaken transactional checks or legacy
error/shadow semantics on the assumption that an index is missing.

Follow-up adaptive analytics run 45116284090, latest 15m: indexed guard shape
2,496 reads / 24 executions (104 per execution). Earlier 69,912/72 observations
are not a controlled before/after: windows, users, and operation mix differ.
Latest 60m still includes the earlier expensive events. Analytics now labels
indexed candidate and malformed-guard shapes explicitly. No claim of whole-account
or write-budget compliance follows from these samples.

No further production mutation beyond the one pending-request index. Remaining
work: ordinary-traffic cleanup UPDATE telemetry after the index, write attribution
by action/index/trigger, and comparable app-session rates excluding rollout and
diagnostic SQL. Keep PR unmerged and cron */15 unchanged.

## Second targeted production fix: canonical owner-index write amplification

Source `fbce88e8931927cec98bc4c6c3ea45cce2e02e1e`.
Production rollout https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45116959466
and independent Worker compile/validation
https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45116966062
both **PASS**. PR #36 remains OPEN, mergedAt=null.

Applied only migration `0089_remove_redundant_global_owner_index.sql`:
removed `idx_friendbook_cf_firestore_owner`, an owner-only global index maintained
on canonical inserts/deletes and applicable owner-assignment updates. Runtime
path-based authorization uses the primary key. Live chat uses the more selective
`idx_cf_live_chat_sender_time(parent_path,owner_uid,create_time DESC)` partial
index, which remains intact. The global collection/update index remains intact
because the real chat list uses it; it must NOT be dropped as supposedly unused.

Production verification compiled but did not execute an INSERT. Compiled
`IdxInsert` opcode count (including all trigger programs) changed 60 -> 59.
This demonstrates removal of an index-maintenance operation, **not** 60/59 billed
writes or a measured percentage/daily saving. Both scoped live throttle query
plans were identical before/after, as were path authorization and chat list
plans. No user rows were read or mutated by these EXPLAIN probes.

Reported one-time retirement cost: **591 reads / 1 write**, document changes=0.
Schema-inspection statements have their own small metadata read costs; the 591
number is the DROP statement alone, not the whole workflow. No Worker deploy,
Android release, schedule changes, table deletion or application-data deletion.
Rollback index definition is recorded in the migration (rebuilding costs quota).

Validation: **235 Worker tests, 344 Python tests**, schema 2, migrations 3 and
index-plan 4 tests; Worker dry-run bundle; 48-workload offline replay all PASS.
The replay initially failed because its comment fixture seeded mirror-only rows
while the app intentionally reads canonical comments only. Corrected fixtures
to canonical rows and added a newer mirror-only sentinel that must stay hidden.
No read fallback or UNION arm was introduced. Network is disabled in the replay.
Retry workloads for receipts, story views, profile merges, saved posts, friend
requests, reactions, comments, FCM registration, notification read/create all
retain zero logical row changes in this local replay. These are not D1 billing
measurements or proof every possible retry is free.

First rollout attempt 45116935402 failed in local tests before reaching any
production mutation: Python SQLite reused a cached EXPLAIN after the schema
change. Fixed by disabling statement caching in the local regression and using
stage-specific EXPLAIN text in the production probe. Latest gates above passed;
failed gates were not bypassed.

## Remaining acceptance gap (explicit)

There are now two targeted production quota fixes, not a proven end-to-end
quota guarantee. No matched ordinary-usage session or full UTC-day follow-up
proves <=100k reads / <=5k writes. Historical cumulative counters cannot fall
retroactively, and aggregate account usage also includes administrative work.
Unlimited traffic cannot be promised a fixed quota while preserving all writes.
Further changes must be justified against current action mix, measured production
costs and a defined usage budget—not repeated expensive diagnostic table scans.
Signed Android 2.10.33/175 is unchanged by these schema fixes; its previous signed
build proof remains applicable. This turn did not rebuild Android or publish Play.
