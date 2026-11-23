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

## Post-install complaint: distinguish diagnostics from application traffic

User reports installation did not visibly change usage. No device-side trace,
installed version confirmation, or matching dashboard screenshot/time range is
available. Do not dismiss the report as merely a cumulative-counter issue.

Fresh **GraphQL-only** runs on source `f426347ad4d524f248946970e0c9663ae76fbf63`:
- Query windows: https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45117760693 **PASS** (all six jobs).
- Account daily: https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45117760730 **PASS**.
- The account workflow explicitly skipped Worker/schema inspection with the
  `[analytics-only]` marker. No D1 SQL, application request, mutation or deployment
  was executed by these runs.

Provider-returned timestamps (not an assumption about the user's local date):
- At 2026-11-23T05:26:19 UTC, account-adaptive daily total 469,779 reads / 6,379 writes.
- Query-adaptive 05:21:17–05:26:17 UTC: **30 reads / 0 writes / 18 executions**.
- Query-adaptive 05:11:18–05:26:18 UTC: **38,147 reads / 58 writes / 1,011 executions**.
- Query-adaptive latest 60m: 143,258 reads / 2,452 writes / 7,004 executions.

Important attribution correction: the 15m dataset includes a 29,931-read capped
friendship diagnostic CTE plus 5,649 reads across three original full-guard SELECT
executions (1,883 each). Those are our administrative diagnostic shapes:
**35,580 of 38,147 query-adaptive reads (~93%)** in that specific window, not
ordinary app query shapes. The remaining 2,567 reads are not a device attribution
or guaranteed app-only remainder. Account analytics can lag and differ from query
analytics; do not subtract these values from account billing or claim all account
growth is explained. The latest 5m window is low-activity and cannot prove the
installed app is fixed or establish comparative savings.

Prevent diagnostic overhead from recurring accidentally:
- Friendship profiling CLI now refuses to run without `--allow-production-scans`,
  before any subprocess/network action.
- Its workflow requires BOTH the existing profiling marker and a separate
  `[allow-production-scans]` marker.
- Original full-scan baseline is additionally excluded unless separately opted
  in at the CLI; the workflow does not enable it.
- Ordinary quota checks should use GraphQL analytics only. Never rerun table
  preflight merely to reassure the user or obtain a fresh daily number.

All **347 Python tests PASS**, including three new opt-in regression tests.
Inline window-analytics Python syntax check PASS; seven account-analytics
validation tests PASS; actual remote GraphQL calls PASS. No Android/Worker
business logic changed in this follow-up. Prior Worker compile/tests remain the
application-code evidence. No new measured end-to-end quota guarantee.

## Autonomous follow-up without requiring user technical input

User explicitly asked us to investigate independently rather than require a
screenshot or technical details. Added `quota_traffic_report.py` and the opt-in
`quota-traffic-report.yml` workflow. It calls **only Cloudflare GraphQL**, never
D1 SQL, application endpoints, profiling subprocesses, migrations or deployment.
No recurring schedule was created and no Worker cron was modified.

Run https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45118338726
on source `7cf411641565fd2d423f9028318afbf81935cede` **PASS**. Sanitized JSON is
available as the `sanitized-quota-traffic-report` Actions artifact (7-day retention).
The report emits only fixed-vocabulary query categories, operation/root labels,
aggregates and timestamps—no raw SQL, bindings, IDs or document values. A subsequent
local-only refinement adds fixed-vocabulary table labels; it does not run another
remote report automatically.

Provider-returned query-adaptive data ending **2026-11-23T05:31:47Z**:

| Window | Reads | Writes | Executions | Caveat |
|---|---:|---:|---:|---|
| Latest 5m | unavailable | unavailable | unavailable | no observations returned; NOT zero usage |
| Latest 15m | 2,520 | 58 | 990 | 22 returned shapes, not a device trace |
| Latest 60m | 142,828 | 2,452 | 6,826 | includes older activity and diagnostic work |

The 15m window begins 05:16:47 UTC. Its observed top-shape read subtotal was 2,520
under `application_or_unattributed`; schema inspection contributed zero reads.
Largest shapes were comment-related SELECTs: 1,534/128 executions and 344/86;
path lookups 290/290. The largest write shape was 30 writes/10 executions.
No matched-session baseline, device identity/version, or full-day savings claim
is established. Do not extrapolate these windows into a guaranteed daily cap.

The 60m top-60 read shapes include 29,931 known diagnostic-preflight reads,
6,651 known diagnostic-probe reads, and 1,209 schema-inspection reads. The shape
list hit the 60-group limit and may be incomplete. Its category subtotals must
NOT be subtracted from separate account billing or treated as complete app totals.
Read-sorted and write-sorted shape samples are independent, not added together.

All **352 Python tests and 235 Worker tests PASS** locally after the follow-up,
including fail-closed handling of missing/invalid analytics, diagnostic exclusion
without hiding actual friendship transaction writes, top-N truncation labels and
privacy checks. Prior Worker compile run 45116966062 remains the unchanged business
code's compile evidence. PR #36 remains OPEN/unmerged; no APK rebuild or production
mutation this turn. There is no always-running monitor or auto-remediation process:
the reusable report is invoked explicitly, so don't claim background monitoring.

## Strict guarantee/proof request: acceptance explicitly fails

Added `verify_quota_target.py`, six regression tests, and
`quota-target-proof.yml`. Report: `docs/verification/quota-target-proof.md`.
Workflow 45119704081 on source3ab635d completed with **failure at the actual daily
acceptance step, exit2**; this is an observed budget failure, not broken tests.
Worker235/Python358, offline10/30/100-user replays and dry-run compile all passed
in preceding CI steps. Proof JSON artifacts uploaded successfully despite the
budget failure. No continue-on-error or masked exit status.

GraphQL-only account analytics queried at returned timestamp
2026-11-23T05:45:34.691857Z: Nov21=3,269,075 reads/111,664 writes;
Nov22=2,260,402/105,706; partial Nov23=470,482/6,383. Historical days precede
latest fixes; current day straddles them. Fix rollout completion timestamp
05:19:12Z verified from Actions45116959466 job metadata. No fully post-fix UTC
day is available. No guarantee/savings percentage inferred. Daily target100k/5k
is an acceptance target, not a verified provider subscription limit.

Production verdict remains FAIL and future_quota_guarantee=false. No production
SQL, mutations, application traffic, deployments or schedule changes this turn.

## User-requested Cloudflare Worker reporting

Deployed separate `friendbook-quota-monitor`; latest deployment45120921972
sourcea51375267ecb0693cc25c11f57626060db8a3691 PASS. Details and exact limits:
`docs/verification/quota-monitor.md`. Worker240/Python358 and both Worker
compilations PASS. Remote metadata verifies only two encrypted secrets, no app
storage bindings, and exactly15m cron. HTTPhealth200, unauth report401 and
authenticated report200 verified in the successful run. Prior attempts failed
smoke checks; not passed off as success. All changes are on the current branch.

Live returned report05:57:26.510Z: account daily470,485reads/6,383writes,
TARGET_EXCEEDED; recent15m3reads/0writes/3executions. No savings extrapolation.
Monitor is observe_only, automatic_fix_actions=[], future_quota_guarantee=false.
It logs aggregate reports every15m as configured; no cron invocation was directly
observed in this deployment proof. It does not automatically repair source or
quota. Main Worker/data/cron unchanged, PR36 unmerged. Reporter itself executes
no D1 SQL or app requests; ordinary Workers/API/logging usage still applies.

Runtime secrets encrypted; prefer dedicated analytics-read credential. Deployment
fallback reuses existing connected Cloudflare credential, whose scopes are NOT
proven read-only. This limitation is explicit in operator documentation. No secret
values exposed, persisted in Git or included in report artifacts.
