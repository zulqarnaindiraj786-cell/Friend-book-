# Wider ranked quota review — implementation and evidence remain separate

Analytics-only run https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45196130992 **SUCCESS**. Rolling 24h ended **2026-11-23T14:37:12Z**, spanning older application versions and engineering work. Query totals **1,093,438 reads / 63,073 writes / 121,260 executions**. Only primary D1 observed in the nontruncated account DB split; SQL shape lists are top-60/truncated. These are not completed UTC-day acceptance or device attribution.

The reporter now places ten read-ranked shapes and six write-ranked shapes before verbose notices (eight compact shape notices plus totals) for a single historical window. This prevents repeatedly examining only the two same historical shapes while GitHub hides other consumers. Multiple recent-window reporting retains two per metric/window. Output uses existing sanitized shape metadata; redacted grammar is bounded further in the priority view. New coverage plus full **397 Python tests PASS**. No D1 SQL or synthetic app traffic was used to obtain this report.

## Observed read ranking

| Rank | Shape family | Executions | Reads | Returned rows |
|---:|---|---:|---:|---:|
| 1 | Exact canonical coverage inventory | 9 | 169,534 | 11,798 |
| 2 | Bounded legacy-only inventory structure; exact caller still unproven | 112 | 151,719 | 112 |
| 3 | SELECT/LIMIT, table not mapped by current fixed vocabulary | 69 | 147,250 | 17,250 |
| 4 | Historical-comment CREATE INDEX | 4 | 77,280 | 0 |
| 5 | Transaction guard with indexed friendship candidate/exception logic | 129 | 73,563 | 0 |
| 6 | Engagement summary with comment previews/counts/reactions | 7,723 | 55,808 | 7,723 |
| 7 | Recognized diagnostic preflight | 1 | 29,931 | 1 |
| 8 | Canonical point lookup with LIMIT | 28,629 | 28,575 | 28,575 |
| 9 | Chat membership SELECT | 867 | 23,251 | 11,197 |
| 10 | Exact legacy coverage inventory | 1 | 21,420 | 20 |

These rows are ranked SQL shapes, not mutually exclusive application routes or user sessions. Necessary point lookups and ACL guards must not be disabled merely because they appear in a top list. Rank 3 remains unidentified; an empty table-label list means not mapped by the reporter, not a query without tables or a zero-cost database. Friendship guard source was inspected: it retains transactional duplicate detection, indexed participant candidates, canonical precedence and historical/malformed exception handling. A costly historical aggregate is not itself proof of an unnecessary guard or a current request loop; no security bypass was introduced.

## Observed write ranking (first four)

- Legacy-root promotion exact template: **45,581 writes / 5,078 executions** (already traced; completed-migration rerun guard is on this branch, not a global disable).
- Mirror INSERT: **3,420 writes / 380 executions**, still unattributed.
- Snapshot-guarded canonical INSERT: **2,266 writes / 298 executions**.
- Mirror upsert: **1,872 writes / 219 executions**.

The root coverage script's broad canonical count was already scoped in source to the 13 consumed roots with plan/parity regressions. It has not been rerun against production merely to create a savings demonstration. Historical index-build and migration costs cannot be erased by changing today's app.

## Outcome and boundaries

This continuation widened investigation, but did not establish a new unnecessary application loop or ship an app fix. Unknown consumers remain unknown. No production Worker deployment, schema migration, index removal, data deletion, APK/AAB, merge, acceptance-threshold change or backend proof-date reset occurred. No claim of 100% completion or automatic future monitoring is made. See `dominant-write-promotion-attribution.md` for exact-match versus structural-family limits and the failed repository-wide workflow-disable permission attempt.

## Rank 3 traced to exact historical backup SQL

Follow-up analytics https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45196783791 **SUCCESS**, window ending **2026-11-23T14:40:52Z**, exact-template attribution confirms **147,250 reads / 69 executions / 17,250 returned rows** match:

`SELECT * FROM friendbook_cf_presence_sessions ORDER BY rowid LIMIT ? OFFSET ?`

This exact template is present in both `checksum_pages` and `write_backup` in `scripts/loadtest/backup_retired_tables.py` at historical commit `e305b41`. The classifier generates only the seven reviewed retired-table templates, requires exact normalized SQL including bind identities, and executes none of them. This proves a historical backup-template match, not a particular actor/run or active app presence polling. Current source replaced OFFSET with rowid seeks in **7f76bb4**; source tests already cover no OFFSET, sparse/negative rowids, checksum equivalence and cursor validation. An additional 5,000-row real SQLite regression now compares the entire historical/current row sets and requires current VM work below half of historical OFFSET work. VM steps are not D1 billed reads; this is prevention evidence, not newly realized production savings.

Privacy diagnostics recognize only reviewed SQL tokens/table names, redact arbitrary identifiers and all values, and preserve only allowlisted quoted identifiers. This continuation made no production app changes or table scans. Rank 3 is no longer unidentified. The 151,719-read inventory's exact caller and other residual costs remain unresolved; no 100% acceptance claim.

## Recent traffic separated from the historical engineering window

Analytics-only workflow now requests **5, 60, 1440 minutes**, preserving privacy, fail-closed handling and no production SQL. Run https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45197094544 **SUCCESS**, end **2026-11-23T14:42:40Z**:

- **5 minutes:** no observations available, totals `null`. This is not zero traffic or a latency guarantee.
- **60 minutes:** **1,376 executions / 3,057 reads / 47 writes**. Twenty-six query shapes, not truncated. One observed primary DB, no observed other-DB reads/writes in the account split.
- Recent largest read shapes: engagement summary **1,217 reads / 172 executions / 172 returned rows**, canonical point lookup **592 reads / 593 executions / 592 returned rows**.
- Recent largest write shape: canonical upsert **40 writes / 10 executions / 10 reads**. Other observed writes are not automatically waste.
- **1440 minutes:** **1,088,002 reads / 63,073 writes**, including the historical maintenance costs identified above; this shape list is truncated.

The recent engagement summary implementation was reviewed: indexed visible totals, blocked-author indexed deductions where required, scalar actor-reaction lookup, and a two-comment preview bound. Its observed cost alone does not demonstrate an unnecessary loop. Necessary ACL reads and real updates remain intact. Recent aggregate data cannot establish installed Android version, screen visibility, representative user activity, or distinguish all callers. It must not be multiplied into a promised daily total or used as a completed-day pass. The latest hour also straddles the deployed fix boundary; it is not an isolated post-fix cohort.

Validation: **283 Worker tests PASS; 401 Python tests PASS; diff check PASS**. Wrangler dry-run produced the Worker bundle and exited with `--dry-run: exiting now.` after the original shell command's collection timeout; no deployment was requested. Generated files stayed under `/tmp`, outside the repository. No app-source change, APK/AAB, migration, merge, or acceptance-boundary change occurred in this continuation. This is useful recent evidence, not a new root-cause fix or 100% completion.

## Related maintenance entry-point hardening

Source audit found three additional related production-maintenance entry points outside the already guarded bulk-root workflow: `canonical-inventory.yml`, `coverage-proof.yml`, and `legacy-user-backfill.yml`. They previously allowed commit-marker push execution on an older named branch, and coverage also allowed unconfirmed manual dispatch. This is an accidental-rerun prevention gap, **not evidence of an active current-branch app loop**.

All three now require **manual workflow dispatch plus explicit default-false `confirm_production_scan`**. User promotion retains seven separately selected preview/apply modes; its account reserves, tests, transactional source protections and noncancelling concurrency remain. The change removes all commit-marker conditions from these three workflows. No workflow was dispatched and no D1 query/write was executed for validation. This protects the reviewed source revision only; it is not a repository-wide workflow disable and cannot remove triggers from old refs or queued runs.

Validation: **402 Python tests PASS**, including trigger/default/gate/mode regressions. All three workflow files also parsed with PyYAML BaseLoader and passed structural checks for manual-only trigger and fail-closed job gate. YAML parsing is not a substitute for future GitHub execution, and no production migration was run merely to test it. App features, SQL, 15-minute cron, APK/AAB pause, unmerged PR and production Worker version remain unchanged. Exact caller of the 151,719-read inventory still unproven.

## Concurrent offline read/write contract audit

Reviewed Android listener scheduling/cancellation and overlapping engagement-read coalescing; this source inspection did not establish a new unnecessary HTTP loop. No speculative client change or APK was introduced. Separately executed the real Worker plus all migrations in network-disabled SQLite at **10, 30 and 100 synthetic users**, **5 concurrent requests** each: **48 workloads PASS per run**. This is a synthetic replay, not representative production activity or a capacity/billing test.

Added `quota_replay_contract.mjs` to the actual replay runner, enforcing that all **20 explicitly listed read workloads** execute and cause **zero row changes including triggers**. Missing/unexecuted/malformed workload results now fail closed. Query-over-POST routes are explicitly included; the contract does not infer purity from HTTP GET. Genuine mutations (send message, add comment, etc.) are not prohibited. Three new Node regressions exercise positive checks, every read workload's write detection, fail-closed results and preservation of genuine writes.

Re-executed all three concurrent replays with the new mandatory contract: PASS. Each run retained zero changed rows for duplicate friend requests, duplicate receipts/unread reset, repeat device registrations, repeated reactions/comments/notification opens and duplicate notification creation. Five-item feed engagement remained **18 SQLite statement calls/request**, with no writes, across all three sizes. Statement counts/returned rows/local changes are not Cloudflare billed rows, and SQLite concurrency does not model remote D1 transaction scheduling. No extrapolated daily guarantee is made.

Validation: **286 Worker tests PASS; 402 Python tests PASS; diff check PASS**. Artifacts stayed under `/tmp`. Changes are test/investigation protection only: no production Worker/client source change, deployment, migration, APK/AAB, merge, or quota-proof boundary reset. Current app-wide acceptance remains unproven, and the inventory query's exact historical caller remains unresolved.

## Concrete promoter zero-work and inventory-completeness fix

`promote_legacy_roots.mjs` had two independently reproducible source issues:

1. After a complete initial inventory reported zero legacy-only rows, `promoteCollection` still requested an empty candidate page and reran the same inventory. It now returns the zero-work result immediately: **one inventory statement rather than inventory + empty page + inventory**. This applies to preview and apply mode. There are no new writes, no removal of genuine pending work, and no persistent cache. The result describes the initial snapshot; it never locks out subsequent inserts or replaces the separately gated final coverage proof.
2. `legacyOnly` allowed `sampled === 25001`, although that is the bounded SQL sample's sentinel, not proof the whole collection was covered. It also accepted negative/inconsistent counts. It now refuses the sentinel and malformed/inconsistent counts before any candidate/page/write work. At most 25,000 sampled rows can be treated as a complete inventory. This fail-closed behavior prevents zero within a truncated sample from falsely establishing complete coverage; a legitimately oversized collection now needs a separately reviewed recovery strategy rather than silently accepting partial proof.

Added three regressions: actual SQLite fixture promoted once then rerun in preview/apply (one statement, zero changed rows); malformed/sentinel cases stop after the first call; largest complete/empty inventory remain supported. Existing nonzero promotion, equivalence, representation opt-in and concurrent-source-change tests remain green.

Validation: **289 Node/Worker tests PASS; 402 Python tests PASS; `node --check` PASS; diff check PASS**. The `reads` field retains its existing page-read-only semantics and does not include inventory billing; do not use it as a total D1 savings measurement. No production inventory was run to demonstrate savings. Source-only maintenance fix: no Worker deployment, app behavior change, data migration, APK/AAB, merge or proof-date reset. This does not identify the exact historical caller of the 151,719-read shape and does not establish whole-app daily quota acceptance.

## Fail-closed coverage proof parsing

The coverage verifier previously coerced missing/null count fields to zero, silently filtered nondictionary rows, and overwrote duplicate groups in a dictionary. Reproduced malformed-input cases could therefore print zero legacy-only roots without valid aggregate evidence. It now validates both query results before printing success: list/dictionary structure, nonempty unique collection names, actual nonnegative integers (not booleans/string coercion), positive grouped source counts, legacy-only <= legacy rows, and canonical groups restricted to the 13 requested roots. Invalid proof returns exit **2**, not pass. An actually empty database (both successful query lists empty) remains valid. Genuine missing root coverage still returns **1**.

Unknown legacy/custom collection names are no longer echoed into coverage notices; their missing-row totals remain visible under the fixed label `other`. This preserves the prior distinction that the success message covers only the checked root allowlist, not every custom collection. Query SQL, bound root allowlist, resource-index seeks, count semantics, normal app reads/writes and final legacy-only gate are unchanged. No production query was run to validate these parser failures.

Regression coverage exercises malformed/null/missing/negative/inconsistent/bool/string/duplicate/unexpected groups, empty database and private custom collection labels. **404 Python tests PASS; Python syntax compilation PASS; diff check PASS**. Previous **289 Node/Worker tests PASS** remain applicable because no JavaScript/Worker source changed in this step. This fixes an evidence-validation defect, not measured D1 usage; no deployment, APK/AAB, merge, acceptance-threshold reduction or proof-date reset. Whole-app quota acceptance is still not established by historical maintenance attribution or local tests.

## Android Reels repaint-triggered engagement reads removed

Investigated a real application request path rather than another diagnostic workflow. `ReelsFragment.VH.renderCounts()` called `D1Reactions.reel()`, which hydrated cache **and** requested an engagement GET whenever its 8-second freshness window had elapsed. Thus save/share counter repaints and other UI-only paints could cause an unrelated full engagement-summary fetch. Its async callback also reentered that fetch-capable renderer (normally stopped by the newly fresh cache). This is a reproducible source-level unnecessary-read path, **not evidence of a self-running timer loop or proof it caused the largest production SQL shape**.

Split `D1Reactions.hydrateReel()` into a cache-only operation and made `renderCounts()` use only that. Explicit bind and authoritative metadata/resume paths still call `refreshEngagement()`, preserving the existing freshness/coalescing/authentication behavior. Reaction-mutation callbacks retain their existing explicit refresh. Async completion paints only the still-bound model, without entering another fetch-capable render. Pending local-upload placeholders are excluded from the new explicit refresh helper. No TTL increase, server cache, ACL bypass, missing-document fallback or mutation suppression was added.

Three source regressions enforce cache-only counter rendering/hydration, explicit bind/metadata refresh preservation, placeholder guard and recycled-holder callback guard. **407 Python tests PASS; diff check PASS**. Android source compile/JVM test workflow https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45199878586 **SUCCESS** at source **9639e12**. This ran `testDebugUnitTest`, not APK/AAB packaging. Previous 289 Worker tests remain unchanged/applicable; no backend source changed here.

Fix is source-only, not installed on user devices; production savings are therefore **not yet measured or claimed**. No APK/AAB, deployment, migration, PR merge or acceptance-boundary reset. The recent largest read consumer was engagement summary, but SQL alone does not identify its screen/device/caller. Removing this request path is not whole-app daily quota acceptance.

## Reels offscreen/prebind engagement requests gated

A second concrete Android extra-read path was found after separating paint from fetch: `ViewPager2` binds/attaches neighboring holders, and `refreshVisibleHoldersInPlace()` iterates **all attached holders**, not only the selected page. Explicit bind/metadata engagement refresh could therefore fetch summaries for offscreen prefetched Reels or while the tab was hidden.

`refreshEngagement()` now returns unless the tab's existing visibility supplier (`isResumed() && !isHidden()`) is true, the holder matches the adapter's selected `playingPos`, and that position is valid. Adjacent/prebound holders still paint their cached counters, but do not perform that GET. `playOnly()` and selected-holder attach explicitly refresh engagement, so entering an already-prefetched page cannot leave its counters permanently unhydrated. Existing bind/resume/metadata refresh routes, account checks, 8-second freshness, overlapping-read coalescing, reaction writes, playback and server ACL checks remain. No timer/polling or persistent cross-user cache was added.

Validation: **408 Python tests PASS**, including source guards for selection/visibility gating and selected play/attach fetch preservation; Android compile/JVM workflow https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45200572239 **SUCCESS**, source **9a989e9**. These are source-wiring regressions plus compilation/existing JVM tests, not instrumented device request counts or proven D1 savings. No Worker source changed. Fix remains uninstalled because APK/AAB packaging is explicitly paused. PR unmerged; no migration, backend deployment, production SQL run or acceptance-boundary reset.

## Comments author-bind read amplification removed

Found a separate source-level application consumer in `CommentsAdapter.onBindViewHolder`: when an author's RAM entry was absent, **every bind** called `users/{author}.get()` (network by default). Every successful profile lookup then called `notifyDataSetChanged()`, rebinding the entire comment list. Multiple comments from the same unresolved author, slow responses, missing profiles, or many different cold authors could consequently amplify bind-triggered reads. This is a concrete source amplification path, not proof of its production traffic share.

Added per-adapter/account `CommentAuthorLookupGate`: overlapping author binds share one lookup slot; completed found/missing profiles do not requery on subsequent binds; failures are eligible only after 10 seconds **and a subsequent bind**, with no scheduled retry. Account changes reset the gate and generation tickets reject stale callbacks. Profile lookup now checks the document cache before the normal authenticated server read. Unlike the general `Fb.getUser` helper (which returns null on network failure), this path preserves failure versus confirmed miss so transient failures are not permanently treated as missing profiles. Success populates the existing FastCache and repaints only current rows belonging to that author, not the entire list. Embedded names/photos remain the fallback; server ACLs and normal comment/profile operations are unchanged.

Three pure-Java JVM tests cover 100 repeated binds, pending/completed/missing-profile suppression, independent authors, timed-on-demand failure retry, signed-out/invalid IDs, account switching and stale/ABA generation callbacks. A source-wiring regression covers cache-first access, scoped callbacks, success/failure distinction and targeted repaint. The gate is local to this adapter, not an app-wide negative cache or a claim that TickCache/other profile consumers are coalesced by it.

Validation: **409 Python tests PASS**, diff check PASS; Android source compilation and JVM tests including the new gate tests **SUCCESS**: https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45201453804 at **c8f9b76**. This is not an instrumented scroll/network measurement. No Worker source changed; previous 289 Worker/Node tests remain applicable. No APK/AAB, backend deployment, schema/data migration, merge or acceptance-boundary reset. The fix is not yet installed on devices, so production D1 savings are unmeasured and whole-app daily acceptance remains unproven.

## App-wide cold badge/profile lookup amplification removed

`TickCache.attach()` had no pending-lookup registry. Multiple simultaneously bound feed/comment/profile badge views for the same cold author could each start disk lookup then remote `users/{uid}` lookup before the cache was populated. Lower-level HTTP overlap coalescing may absorb some requests but cannot guarantee elimination of independently queued/sequential cold binds. The previous implementation also used ImageView's generic tag as the author guard, while `Icons.setTickBadge()` itself clears/reuses that tag.

Added synchronized, account/generation-scoped `BadgeLookupState`: one pending flight per author, weakly referenced/deduplicated waiting views, separate weak target tracking for recycled views, and result fan-out. Existing RAM/document-cache-first behavior and cosmetic badge cache remain. Cache/account clear invalidates pending tickets; explicit badge updates made during a flight win over its older result. Confirmed missing/no-level profiles cache `none`. Network errors instead release the flight with a ten-second **on-bind** retry cooldown (no scheduled request); they are not permanently cached as an authoritative absence. Current-account/generation checks happen before cache-miss network fallback and completion. Invalid/signed-out binds do not start a flight. No access checks or profile writes changed.

Four JVM tests cover 100 repeated binds, one-flight/multiple-row fan-out, recycled views whose new target is cached/empty, missing results versus transient failures/cooldown, account switches/stale generations, clear, signed-out state and explicit-update precedence. A Python source regression checks cache-first/gate/account-scoped wiring and absence of generic-tag or retry-timer logic. Scope: this gate covers TickCache, not all possible profile consumers; it does not establish that every feed/comments author lookup is globally shared.

Validation: **410 Python tests PASS; diff check PASS**. Android compilation and JVM tests including `BadgeLookupStateTest`: https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45202305113 **SUCCESS**, source **0e03199**. No Worker source change (previous 289 Worker tests remain applicable). No APK/AAB, installation, production deployment, SQL/migration, merge or acceptance-boundary reset. This removes a concrete source duplicate-read path and fixes cosmetic stale-view/account races; no production D1 savings or whole-app quota acceptance is claimed without installation/representative measurements.

## Shared cache-first profile lookup chains coalesced

Continued source audit found `Fb.getUser()` could start separate asynchronous disk-cache/miss/network chains for overlapping cold calls to the same author. Existing transport coalescing only covers the overlapping HTTP portion; queued async cache-miss callbacks can otherwise schedule further HTTP work after an earlier flight ends. The FeedAdapter's existing `requestedAuthors` guard was reviewed and was **not** misreported as an unbounded bind loop; Friends' explicit per-visit freshness was retained.

Added `ProfileReadFlights` around **cache-first `Fb.getUser()` only**, using structured `(actor, author)` keys rather than delimiter-concatenated IDs. Concurrent consumers share one Task for the full lookup chain. Registration occurs before async work starts, and the flight is removed **before** consumers are notified, so reentrant callbacks do not reuse a completed result. There is no new TTL/negative cache: successful/missing/error/completed results do not remain in this flight map. Existing RAM cache, document-cache-first behavior, authenticated network fallback, account checks and returned user DTO semantics remain. Explicit `getUserFresh()` is intentionally outside this coalescer and still goes to the existing fresh path. This is overlapping-read elimination, not a proven recurring background loop or whole-app profile-cache redesign.

Three Robolectric JVM tests cover 100 overlapping consumers sharing one operation, separate accounts/authors, sequential reads, missing/error/factory-failure eviction, and a completion callback starting a genuinely new read. Python regression checks that cache-first wiring and fresh-read separation remain. **411 Python tests PASS; diff check PASS**. Android compile/JVM suite **SUCCESS** https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45202973189 at **c6bae49**. No Worker source change (prior 289 Worker tests remain applicable); no production D1 SQL, migration, deployment, APK/AAB, installation, merge or proof-boundary reset. Profile sharing remains uninstalled source, so production quota share/savings and whole-app daily target acceptance are not claimed.

## Shared pager stale-lifecycle network refresh prevented

`CursorPager.next()` already rechecked lifecycle/generation after notifying its render listener, but `startLocal()` did not. Its cached-result `emit()` callback can synchronously close/replace the pager or switch accounts; the old code could then start a full server GET/delta refresh for that now-inactive screen. Added the same active-account/request-generation check immediately after the cached-render callback and before choosing full versus delta network work. It does not cancel legitimate open-screen refresh, change pagination, trust stale cache as server proof, or weaken the query ACL.

Robolectric/Mockito regressions verify **zero network-refresh invocations** after close during populated/empty/failed-cache rendering for both full and delta modes, zero full refresh after a render-triggered account change, and exactly one normal server refresh for an unchanged open pager. These tests exercise mocked query task calls around the real pager; they are not production network/billing measurements or evidence this race was a dominant historical consumer.

Android compilation/JVM suite **SUCCESS** https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45203915268 at **1fd3f6b**. Earlier attempt 45203707509 failed before compilation/tests during Robolectric dependency download (curl exit 22); a direct `gh run rerun --failed` was rejected, so no success was inferred from it. Latest expanded full/delta test suite passed. **411 Python tests PASS; diff check PASS**. No Worker source changed, so prior 289 Worker tests remain applicable. No APK/AAB, installation, deployment, migration, merge or acceptance-boundary reset. Source removes a concrete stale-screen request opportunity; its production share/savings and whole-app daily quota acceptance remain unmeasured.
