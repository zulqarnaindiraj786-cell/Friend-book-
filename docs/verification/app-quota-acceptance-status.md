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
