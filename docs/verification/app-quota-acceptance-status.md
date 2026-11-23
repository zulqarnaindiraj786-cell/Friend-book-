# App quota acceptance status — not a 100% certificate

## Latest confirmed fix and rollout

Call signaling reused neither the Worker account-gate snapshot nor the caller profile checked for call authorization. Start additionally reread the caller for display name/photo. Signaling now uses a caller-owned request map: one caller profile read including the gate, and one fresh peer profile read. Start labels reuse the same checked fields. This removes two duplicate caller reads for an ordinary authenticated call start, and one for subsequent signaling transitions. These are statement reductions established by the actual Worker tests, not daily billed savings.

Media renewals deliberately do not receive this map. They still read/check the call ledger, room and both accounts on every authorization operation; active-call, ban, suspension, block and membership revocation remain enforced. There is no cross-request authorization cache, added timer, quota throttle, index or migration.

Actual Worker tests exercise start, accept, invalid actor/transitions, end, stale call IDs, voice/video, refreshed caller name/photo and next-request blocks. Additional helper tests verify concurrent checks share only the supplied map; a new map/default path sees a subsequent block. Existing CallRelay tests retain four logical point-read statements per renewal and fail closed for missing/mirror-only accounts, bans, suspensions, blocks and ended calls. Logical statements are not a daily capacity guarantee.

Production source **03bbf00**, https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45186807660 **SUCCESS**, completed **2026-11-23T13:44:37Z**. Current full suite: **276 Worker / 385 Python tests PASS** through the deployment gate. Schema/migration/index-plan suites, 30-user network-disabled replay, exact production compile, resource/cron preservation and health/auth smoke checks PASS. No Android change, merge or Play upload.

## Evidence ledger across the app

| Area | Evidence available | Not certified by that evidence |
|---|---|---|
| Feed/Home | Authoritative ACL/privacy/block regression fixtures; request dependency reuse and bounded enrichment | Every real device/session's D1 billing |
| Profiles | Scalar/nested literal no-op tests, mirror repair, genuine edits and unauthorized saves | Unknown client payload frequency |
| Chat/messages | Canonical membership ACL, transaction/race/rollback tests, receipt no-ops | Every possible historical encoding at scale |
| Inbox | Adaptive shared seek, cursor/revocation/Unicode/unsafe-integer fixtures; isolated D1 index cost + bounded-read probe | Every query avoiding membership scans; matched user savings |
| Notifications | Recipient/unread compatibility and authorization regression suite | Historical notification volume on every device |
| Calls | Actual API lifecycle plus media-renewal account/revocation tests | Real-phone network/audio/video end-to-end behavior |
| Live/PK | Request-snapshot budgets, status/invite/accept checks and next-request kick/ban/name tests | Real-device broadcast load/capacity |
| Maintenance | Complete-schema scheduled tests; stale/fresh room behavior and independent-task failure isolation | Zero cost: necessary cron/outbox maintenance still runs */15 |
| Push/views | Gateway/mock-provider regressions; AE view path preserved and historical view rows untouched | Actual FCM delivery to every phone or provider availability |
| Other D1 DBs | Latest 5/15/60m account query split observed only primary DB, without truncation | Earlier-day/intermittent/delayed/future other-DB traffic |
| Android candidate | Existing signed 2.10.34 / 176 AAB validated in run 45177136355; Android source unchanged by backend fixes | Play review, listing/Data safety or user installation/version telemetry |
| Daily target | Strict account-wide verifier, unchanged 100k/5k thresholds and preserved FAIL | A passing completed post-fix day: none established |

## Acceptance remains separate from implementation

The last strict check (45183862894) reported 636,015 reads / 14,160 writes for its partial UTC day and FAIL. Later code fixes do not erase those totals. Account totals include maintenance, index builds, other databases and older code in that day. Quiet recent windows do not prove active-user savings, and local tests do not substitute for account analytics.

The new conservative fix boundary is **2026-11-23T13:44:37Z**. First possible entirely post-fix UTC day is 2026-11-24, verifiable only after it completes and analytics is available. This is not an automatic future-run promise. Full-app quota acceptance still needs representative activity and a completed measured day below the user's targets. No workflow threshold has been relaxed, and no unnecessary feature/security removal is used to make a number appear green. Unknown defects or unobserved consumers cannot truthfully be declared 100% absent.


## Subsequent engagement write audit

Reproduced an absent-reaction clear creating a new NULL tombstone for both posts and comments: the behavioral regression failed before the fix with total_changes 7 versus expected 5 (two extra inserted rows across the two operations). Source **65661d0** makes the check atomic in each INSERT SELECT, using current primary-key existence checks rather than a request-local skip that could race. Existing active reactions still clear and existing NULL tombstones remain idempotent. Non-null reactions, counters, comment acknowledgement and explicit legacy unknown-timestamp transitions retain their prior behavior. No DELETE, migration or historical cleanup was added.

A real local SQLite regression covers initial and repeated absent clears, like/love/unlike/wow/unlike, post and comment acknowledgements, unauthorized access, nonexistent comments, and existing-NULL no-ops. A separate test checks an unknown historical reaction still transitions its timestamp on explicit clear and becomes a no-op on retry. **Zero SQLite changes is not proof of zero D1 reads or billed writes**: the primary-key existence checks still have read cost and no isolated production billing probe was run for this change. No frequency or daily savings estimate is asserted.

Deployment https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45187476253 **SUCCESS**, completed **2026-11-23T13:48:30Z**. **278 Worker / 385 Python tests PASS**, schema/migration/index-plan tests and 30-user network-disabled replay PASS; exact production compile, resource/cron preservation and health/auth smoke checks PASS. Latest conservative proof boundary is now **2026-11-23T13:48:30Z** (superseding the call-only boundary above); first possible complete post-fix UTC day remains Nov 24. Strict daily FAIL has not been relabeled. PR remains unmerged, Android unchanged.


## Push/background continuation

FCM device registration already guards unchanged user/token upserts; no new change was needed there. Auditing the broadcast drain exposed a separate reliability defect: a synchronous/async D1 expiry DELETE or lease UPDATE failure escaped a worker loop, retiring that slot and rejecting Promise.all before other recipients necessarily completed. The scheduled wrapper masks the rejection but cannot restore the lost slot or guarantee outstanding work finishes.

Source **f4cfb96** isolates each row's lease/expiry/discard failure, keeps draining the existing bounded due page, and waits for all workers. No new reads, scans, timer, retry policy, claim store, fanout, concurrency or lease changes were introduced. A failed row is not deleted as a workaround; existing leases/backoff and expiry remain authoritative. This is a delivery-reliability correction, not a claimed D1 quota saving: more healthy queued recipients can now finish in that run.

Real-schema fault injection uses 12 due recipients and poisoned UPDATE/DELETE triggers. Before the fix, 2 of 3 cases failed (claim and expired cleanup); after the fix all 3 pass, alongside scheduled failure isolation. Tests verify the 11 healthy recipients finish before resolution, only the poisoned job remains, and that job is recoverable/expirable after removing the fault. Existing send/delete failure handling remains intact.

Deployment https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45188052011 **SUCCESS**, completed **2026-11-23T13:51:50Z**. **281 Worker / 385 Python tests PASS**, schema/migration/index-plan checks, 30-user network-disabled replay, exact production compile, bindings/resources/cron preservation and health/auth checks PASS. No migration, Android change or merge.

Analytics-only report https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45188051866 **SUCCESS**; windows ended **2026-11-23T13:51:23Z**:

| Window | Primary D1 reads | Primary D1 writes | Executions |
|---|---:|---:|---:|
| 5 minutes | 3 | 0 | 5 |
| 15 minutes | 8 | 0 | 11 |
| 60 minutes | 7,622 | 194 | 2,636 |

All three account-wide database splits observed only the primary database, were not truncated, and observed other-database subtotals were 0/0. These are quiet/delayed windows, not representative activity or completed-day acceptance; the hour spans versions and engineering work, and the report ends before this final rollout completed. The existing strict daily FAIL is unchanged. Latest conservative proof boundary is **2026-11-23T13:51:50Z**, superseding earlier boundaries above. Nothing here certifies that every application behavior or unknown device consumer is defect-free.

## Largest-cost evidence continuation (no new app quota-fix claim)

The previous CI report exposed only 5-minute shapes: GitHub's per-step notice cap hid later-window details, and artifact download was unavailable in this environment. Reporter source **d2427c5** now reserves six compact notices (top two shapes for read/write ranking in each of the 5/15/60-minute windows) before verbose detail. Data remains sanitized; no raw SQL, bindings, tokens, device attribution or new application queries are emitted. Added a regression for coverage, output bounds, privacy and ordering; **386 Python tests PASS**. Worker source is unchanged from the last 281-test green deployment.

Analytics-only run https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45188572461 **SUCCESS**, end **2026-11-23T13:54:32Z**. Actual totals: 5m **7/0**, 15m **13/0**, 60m **7,623/194** reads/writes. Account database splits observed only primary D1 without truncation, not a full-day other-database exclusion.

Largest observed 60m read shapes:
- Engagement/comment/reaction summary SELECT: **2,639 reads / 303 executions**, 303 returned rows (about 8.71 billed reads per execution in this mixed window).
- Schema-inspection SELECT: **1,473 reads / 5 executions**.

Largest observed 60m write shapes:
- Canonical-comment INSERT with collision guard: **96 writes / 16 executions**, 32 reads, no returned rows. This groups an SQL shape, not a known device or actor. Associated index/trigger work may be necessary; six billed writes per insert does not by itself prove waste.
- Recognized diagnostic billing-probe INSERT: **20 writes / 10 executions**.

These windows include engineering/probe traffic and multiple earlier versions. Do not extrapolate them to five active users or claim a matched savings percentage. Engagement summary source was reviewed against the observed shape: it already uses resource totals, a single actor reaction lookup, blocked-author indexed subtraction, and a two-comment preview. No new unnecessary work was established by those aggregate numbers; no speculative cache/security removal/index rebuild was shipped. Repeated FCM registration already has a user/token no-op guard. Android listener code remains push/on-demand rather than a demonstrated timer leak.

This continuation improves visibility into actual large consumers; it does **not** solve or certify the outstanding full-day acceptance. The deployment/proof boundary remains unchanged at 2026-11-23T13:51:50Z. No app deployment or migration was required for this analytics-only change; PR remains unmerged. No background future monitoring is promised.


## Historical comment page overfetch (backend-only)

Source **eb6ca89** fixes a reproduced budget mismatch: the canonical comment page used requested size+1 but called historical pagination with its default 21 even for a ten-comment request. Historical paging now receives the same size+1 (11 for ten rows, still 21 for twenty). Each of the three normal indexed routes therefore fetches at most 11 instead of 21 candidates for a ten-row page. Exception buckets and compatibility scan rules are unchanged; this does not establish a universal billed-row cap or remove the legacy compatibility loop.

Regression seeds 120 historical comments split across postId/post_id/resource routes, checks all 12 pages for exact descending ordering, no duplicates/omissions and correct final cursor, then checks ascending delta pagination. Captured route results were up to 21 each before the fix (test FAIL), now at most 11 (PASS). This reduces materialized candidate rows, not a claimed measured daily D1 savings figure. Existing mixed canonical/historical, privacy, promotion/removal and cursor tests remain green. Exact count source was also inspected: no generic SQL count replacement was made because projected privacy/authoritative ACLs still determine visible counts.

Deployment https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45190970384 **SUCCESS**, completed **2026-11-23T14:08:59Z**; **282 Worker / 388 Python tests PASS**, full replay/production compile/resource and cron preservation/health/auth rollout gates PASS. No migration or APK/AAB was built. Signed build 45190232081 was cancelled per owner instruction; later signed workflows 45190565449 and 45190970451 were skipped. Android source replay fix remains unshipped to devices; current declared Android version remains 2.10.34/176. Do not attribute its savings to production.

Latest backend proof boundary is now **2026-11-23T14:08:59Z**. Full-day acceptance remains unverified and the strict daily FAIL is unchanged. No completed-day proof, newly identified high-volume runaway consumer, or 100% completion claim is implied by this bounded overfetch correction. Future signed APK/AAB creation requires explicit new owner authorization, not just quota-source changes.
