# Agent Lee — FriendBook consolidated quota investigation handoff

## Owner request

Do one consolidated, evidence-driven app-wide pass. Investigate and fix large unnecessary quota consumers and recurring/duplicate requests without requiring repeated owner prompts. Preserve necessary reads/writes, chat/feed/auth/live, data consistency and security. Do not manufacture extra code changes simply to report progress. Do not claim all unknown bugs are solved.

## Repository and nonnegotiable boundaries

- Repository: `zulqarnaindiraj786-cell/Friend-book-`, workspace `/home/user/Friend-book-`.
- Current branch: `arena/b52bc74d-friend-book`; PR #36 remains open/unmerged. Inspected source HEAD before this handoff: `c9e685f`, clean working tree. Stay on the current branch; inspect actual status before proceeding.
- **NO APK/AAB packaging or installation until the owner explicitly authorizes it later.** Compile/JVM tests are allowed. Do not make APK production a prerequisite for investigating source.
- **Do not merge.** No destructive git cleanup/reset, data deletion, structural view/trigger swap, broad speculative migrations or security bypasses. Preserve the existing 15-minute production cron.
- No legacy-read fallback/UNION ALL workaround; mirror-only documents must remain 404, not 403. Do not add presence/typing polling. Existing historical compatibility code is not permission to expand fallback.
- Normal necessary operations remain. Safe evidenced fixes may proceed autonomously; do not repeatedly request permission. The owner permits necessary quota use for real root-cause investigation, not pointless repeated scans.
- Never print credentials, user IDs/content, raw production SQL/bindings or arbitrary identifiers into diagnostics. Use existing privacy-reviewed analytics tooling. Do not store credentials in code/chat.
- Communicate clearly in Hinglish/Urdu and English. Separate observed production usage, source-level fixes, local replay and installed-build evidence.

## Measured evidence — do not reinterpret as current loops

Authoritative detail: `docs/verification/dominant-write-promotion-attribution.md` and `docs/verification/wider-quota-consumer-review.md`.

Historical rolling-day costs included engineering/maintenance:
- 45,581 writes / 5,078 executions: exact legacy-root promoter INSERT template. Not proof of an app insert loop or exact actor/run.
- 169,534 reads / 9 executions: exact old full canonical GROUP BY coverage query. Source now counts only the 13 consumed roots via indexed seeks, with real SQLite parity/plan/work regression.
- 147,250 reads / 69 executions: exact old retired presence-table OFFSET backup SQL. Historical backup replaced with rowid seeks; regression checks data/checksum parity, negative/sparse rowids and lower local VM work.
- **151,719 reads / 112 executions remains exact-caller-unattributed**. Fixed-vocabulary redaction establishes a bounded legacy-path inventory subquery LEFT JOIN canonical, with COUNT/SUM and sampled/legacy_only aliases. This is an inventory family; exact template/cap/caller was not proven. Do not label it a phone loop or fixed.
- Mirror INSERT and other residual historical shapes remain partially unattributed. Rankings are top-N/truncated; do not subtract diagnostic subtotals from billed totals.

Latest recorded recent analytics run: 45197094544, window ended 2026-11-23T14:42:40Z (provider-reported timestamp). Last 60 minutes: 1,376 executions / 3,057 reads / 47 writes; 26 shapes, not truncated. Engagement summary 1,217 reads / 172 executions; point lookup 592 reads / 593 executions. Five-minute observations unavailable, NOT zero. This hour straddled the recorded backend boundary and was not a completed-day acceptance cohort.

Owner targets are **<=100,000 reads and <=5,000 writes per representative completed day**, not provider plan limits. Do not extrapolate a quiet hour into a daily pass. No qualifying completed-day proof has been established.

## Already implemented — verify, don't redo

Uninstalled Android source fixes, all included in latest successful Android validation:
1. Reels counter paints hydrate cache only; explicit reads separated from render callbacks.
2. Engagement reads restricted to visible selected Reel, with selected play/attach hydration preserved.
3. Comment-author lookup gate prevents overlapping/repeated missing-profile bind work; cache-first access and targeted row repaint replace whole-list repaint amplification.
4. TickCache cold author flights coalesced, with account/generation and recycled-view guards; no retry timer.
5. Entire overlapping cache-first `Fb.getUser()` chains shared, not merely HTTP overlap. `getUserFresh()` unchanged.
6. CursorPager rechecks lifecycle/account/generation after cached-render callback before full/delta refresh.
7. Active durable push jobs keyed by persisted event identity rather than preview JSON. Registration, unknown actions, KEEP/retry behavior preserved.
8. Foreground duplicate FCM transport hints coalesced within a bounded one-second fixed burst window; calls/new/missing IDs/display/later delivery preserved.

Maintenance fixes:
- Root migration plus related coverage/inventory/user-promotion workflow entry points require manual dispatch and explicit default-false confirmation in this source revision. Global workflow disable failed permission checks; old refs/queued runs are not globally disabled.
- Completed zero-work promoter exits after one inventory instead of empty-page plus repeat inventory. Sentinel/inconsistent inventories fail closed.
- Coverage parser rejects malformed/duplicate/invalid aggregate proof rather than coercing missing counts to zero. Unknown collection names are redacted to aggregate `other`.

Known live backend remains the separately documented `eb6ca89` deployment, run 45190970384. Recent Android/source-only changes are **not installed**, and their production savings are unmeasured. Do not redeploy unchanged backend merely to imply these client changes are live.

## Latest validation and commands

- 413 Python tests PASS: `python3 -m unittest discover -s scripts/tests -q`
- 289 Worker/Node tests PASS: `npm test`
- Network-disabled actual Worker/SQLite replay: 100 synthetic users, concurrency 5, all 48 workloads PASS: `QUOTA_EXPERIMENT_FRIEND_INDEXES=1 node scripts/loadtest/d1_quota_replay.mjs 100 5`. Twenty read-only workload contracts enforce zero changed rows including triggers. This is NOT D1 billing/capacity proof.
- Android compile/JVM workflow 45207597668 SUCCESS at `d13e88f`; runs `testDebugUnitTest`, no APK/AAB packaging. Dependency fetch has bounded timeouts and retained SHA-512 verification. Earlier stalled/canceled runs are not passes.
- No local Android SDK assumed. Use permitted compile/unit-test workflow, not release packaging.

## Required next pass

1. Start with a ranked consumer ledger: production evidence, exact/source attribution confidence, request frequency, per-request cost, necessary vs unnecessary work, installed/deployed status. Use fresh bounded analytics if needed; do not repeat historical reports without a concrete new question.
2. Audit feed/Home, chat/inbox/receipts/calls, notifications, comment/engagement/profile paths, background work/FCM and backend scheduled operations as one pass. Trace UI event -> request -> Worker SQL -> actual plan. SQL shape alone cannot establish a device, actor or repeated client loop.
3. For each proposed change, first reproduce extra work at request level or with a real handler/SQLite fixture. Prefer a regression that fails before the fix and passes after it. A SCAN label alone does not justify another index or security change.
4. Review cumulative client gates for correctness, stale-data/lifecycle/account behavior and missed legitimate updates. Do not keep stacking suppression windows without evidence. Fix any introduced regression before more optimization.
5. Implement related proven fixes in cohesive batches, preserving actual state changes, freshness requirements, ACLs, idempotence and recovery. Keep an explicit unresolved list instead of inventing root causes.
6. Run full relevant tests/compile and safe offline replays, inspect failures, and rerun after changes. Deploy backend only if there is a justified backend change and preserve resources/cron; do not run broad migrations implicitly. Keep Android packaging paused and PR unmerged.
7. Deliver a consolidated findings/fixes/evidence report. If no further large unnecessary consumer is evidenced, say so. Explain exactly what installed-build/representative-day evidence is unavailable; do not promise 100% or unattended work after the session ends.

No additional owner prompt is needed for this investigation within these boundaries.
