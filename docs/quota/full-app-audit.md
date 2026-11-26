# Whole-app quota audit — working ledger, NOT 100% completion

> Follow-up source status: Group 120-post bulk listener has been replaced with independent 10-item feed/moderation cursors and explicit pinned-post resolution; see `docs/verification/app-group-post-pagination.md`. Group invite200 and legacy reactor row hydration were addressed separately in `docs/verification/app-invites-reactor-demand.md`. Quota follow-ups PR #51 (epoch-scoped legacy comment count reuse), PR #52 (canonical wrapped friendship indexes), PR #53 (retain indexed counts during epoch outages), and PR #54 (invalidate positive count cache on canonical shadow changes) are now merged and deployed to the production Worker; see the 2026-11-26 verification section below. Earlier findings remain the historical audit baseline, not current unresolved status for those specific paths. These Android/backend source patches are not yet in the owner’s installed APK; daily quota acceptance and 2,000-DAU capacity remain unproven.

## Evidence and scope

Source review covers Android and Cloudflare request/maintenance paths. Refreshed machine-readable inventory: `docs/testing/android-demand-inventory.json` (214 Java + 1 Kotlin source files). Its line counts are leads, not requests, SQL executions, billed rows, or measured load rankings. Cloudflare backend has received two targeted, tested production changes (PR #51 and #52); this is not a complete app-wide quota fix. Supabase unused.

Never replace authorization with client TTL, public caching of private profiles, or an unconditional one-query rule. D1 rows read/written, Worker invocations, HTTP attempts, and SQL statements are different metrics. Existing owner-observed 20.5k reads/504 writes after reset remains unresolved session evidence, not an attributable per-user benchmark.

## 12-area inventory

| Area | Android entry / backend owner | Read and write demand | Current controls / outstanding review |
|---|---|---|---|
| 1 Feed/Home | `main/HomeFragment`, `post/FeedAdapter`; `firestore-documents.js`, `interactions.js` | Post page, friend graph, visible suggestions/stories; reaction/share/post writes | 10-post head and cursor pages, 120s reuse, bundled engagement, actor-safe cache. Missing-name author lookups moved from bind to submitted-page batches of <=10. Author batch is an additional request, not a merged post/user endpoint. |
| 2 Profile/cover/followers | `profile/ProfileActivity`, `EditProfileActivity`, `friends/FollowListActivity`; `profile-access.js`, `profile-audience.js` | Profile/bootstrap, post/reel pages, relationship/count reads; edits/uploads/follow dual-side writes | Listener profile reuse now 5m; follow list now 10/page with 5m exact cache. Own-account ban/auth refresh must not be removed. Count queries and alternative profile paths still require runtime attribution. |
| 3 Post detail/likes/comments/shares | `post/PostDetailActivity`, `CommentsEngine`, `D1Reactions`, `Reactions`; `comment-api.js`, `interactions.js` | Detail, reaction totals/reactor pages/comment pages; comment/reaction/share writes | Comment pages 10, local append/stable ACK; 1s reaction guard. Post/reel listener reuse now 2m. Legacy reactor people UI hydrates only visible identities in document-ID batches of <=10; canonical D1 reactor route and its billed-row share still need separate attribution. |
| 4 Search/explore/suggestions | `friends/SearchActivity`, `FriendsFragment`, `core/Mentions`, Home discovery | Prefix queries, candidate relationship/profile checks | 500ms debounce; search exact-page reuse now 5m, account/query invalidation retained. Candidate-specific fresh reads remain a review target; no claim all N+1 removed. |
| 5 Notifications/requests | `main/NotificationsFragment`, `MainActivity`, `friends/FriendsFragment`; `notification-access.js`, `fcm-push.js` | Inbox/badge/request reads; read flags, requests/acceptance, notification creates | Notifications now 10/page, 60s head reuse, explicit mark-read batching. Badge windows and mark-all operation budgets are not ordinary list-page sizes. Fanout creates real recipient writes. |
| 6 Chat/messages/status | `chat/ChatActivity`, `ConversationsActivity`, `CallActivity`; `chat-access.js`, `call-access.js`, relays | History/inbox/read receipts/message writes, call state | Shared ListenerPollingPolicy returns NEVER for every path. Push/visible open drive reads. Do not interpret call UI timers as D1 polling. Presence/keystroke writes must stay absent; active call signaling is not optional analytics. |
| 7 Stories/reels/video | `story/*`, `reels/ReelsFragment`, `core/CloudflareViews`; `video-r2.js`, `view-counters.js` | Story/reel pages, views HTTP, video objects; story/reel mutations | View events use Analytics Engine, not per-view D1 writes; emergency read-only seed exists. Rewatch HTTP and media prefetch/buffering still need device traces. Legacy Firebase reaction fallback on post-backed Reels had a repeated-bind read candidate; PR #43 added a bounded actor/post gate and is merged. Android JVM and compile/unit-test workflows passed (runs 45593018150 and 45593031381). Do not introduce D1 view batching where D1 writes are already zero. |
| 8 Groups/pages | `groups/GroupsActivity`, `GroupDetailActivity`, page UI; social/document policy | Membership/content/member queries, group/page mutations | Group discussion uses a 10-item actor-scoped CursorPager; pending/moderation queues have separate cursor pages; pinned post is resolved by ID. Invite picker uses a 10-item users pager. Older approvals remain reachable through explicit paging, not a truncated 10-row replacement. See `docs/verification/app-group-post-pagination.md` and `docs/verification/app-invites-reactor-demand.md`. |
| 9 Settings/privacy/blocks | `settings/*`, `SettingsActivity`, `BlockedUsersActivity`; `account-access.js`, document policy | Preferences/session/block/profile reads; settings/block/session changes | Some user hydration is already chunked (`FeedPreferencesActivity`, document-ID IN <=10). Audit each list and mutation path; do not drop block/privacy checks to save reads. |
| 10 Startup/background/push | `App`, `MainActivity`, `Fb`, `PushDeliveryWorker`, `PushFanoutWorker`; `fcm-push.js`, `scheduled.js` | Auth/profile/config/badges; durable push sends/recipient lookup; scheduled live cleanup/broadcast outbox | Android background FCM refresh is foreground-gated; durable outbox workers are NOT globally foreground-only. Server cron remains every 15m. Neither zero background work nor exactly one startup API has been achieved. |
| 11 Images/video loading | `core/Ui`, Glide/media stack; `video-r2.js` and Worker media routes | URL/object requests; uploads | Ordinary Glide URL load does not call the Android D1 document store. Worker proxy requests can still count invocations and protected media may need authorization. No blanket zero-D1 claim for every URL. Thumbnail/full-res/autoplay behavior needs network capture. |
| 12 Analytics/crash/logs | `CloudflareRequestTrace`, `RequestWindowCounter`, `PushTrace`; `write-attribution.js`, views AE | Local diagnostic logs; view analytics; real security/business audit records | Request trace is debug-only/local, adds no HTTP. write-attribution.js labels SQL only, adds no queries. Security/business audit retention is distinct from click analytics; backend audit paths need cost attribution before deletion. |

## Verified changes in this pass

- Screen cache budgets: profile listener 300s, post/reel listener 120s, search exact query 300s; live/call zero-cache authority unchanged. These do not make every alternative REST/detail path conform automatically.
- Followers/following pages: 20 -> 10; cache reuse 24h -> 5m (freshness correction, NOT claimed quota reduction).
- Notification pages: 20 -> 10; unlimited valid cache reuse -> 60s (freshness correction, may increase revalidation versus previous policy).
- Feed missing-name hydration: per-author fresh GET from bind -> document-ID IN batches on page submission. Cached/embedded names require no lookup. Failure attempts remain bounded until explicit refresh. Backend still applies normal user projection/authorization.
- Friends suggestions: PR #49 is merged. Resolve bounded friendship facts first and skip incoming/outgoing pending-request probes for candidates already confirmed as friends; non-friends retain freshness, saturation, actor/scope, timeout and fail-closed checks. This removes two query calls for an all-friends ten-candidate page, but billed-row savings depend on query plans and workload mix.
- Legacy comment counts: PR #51 is merged and deployed. Cache exact legacy counts by resource, exact blocked-author set and fresh primary-backed comment epoch; canonical engagement summaries still refresh per request. Cache is bounded and revalidated; this reduces repeat probes only where the same resources recur within an unchanged epoch. It does not eliminate first-time reads for large legacy comment sets.
- Canonical friendship guard: PR #52 is merged and deployed with migration `0105_canonical_wrapped_friend_indexes.sql`. Normal two-participant Firestore-wrapped rows now use position indexes; malformed/unusual representations remain in a separate exception index and preserve the original fail-closed fallback. No friendship authorization or duplicate guard was removed.
- New calculator and four Python tests. Existing polling/background, shared-request, reaction-write-queue and actor tests remain part of the suite. Source assertions are not device evidence.

## Before/after evidence table

| Workload | Before | After | Evidence type |
|---|---:|---:|---|
| Real 1-user complete session billed reads/writes/invocations | Unknown | Unknown | No paired device + billing trace |
| 10 distinct missing-name feed authors on submitted page, no cache | Up to 10 individual profile GET starts when bound | 1 bounded user query start | Source demand contract; not billed-row measurement |
| Warm feed head repeated 100 times | Not measured baseline | 0 transport starts | Existing FeedHeadReadsTest |
| 100 immediate reaction taps | Prior queue behavior 2 PUT for even burst | 1 PUT, no summary GET | Existing ReactionDemandTest |
| Polling while idle/background | Shared policy already disabled | Still disabled | Existing ListenerPollingPolicyTest; not whole-app zero-background proof |
| D1 per-view writes | Already 0 on AE view path | 0, unchanged | Backend source; no fabricated batching savings |

## Outstanding gates, in priority order

1. Paired reproducible session trace on baseline and corrected APK: same actor, dataset, cold/warm cache, exact screen/tap sequence, retries, background interval and time window. Retain local `FBQuotaWindow` logs and network capture; no credentials/bodies. Trace currently covers shared HTTP and the 60s app-entry window, not every raw client.
2. Attribute real billed rows and notification/social fanout (including server cron and other clients) to that window; executions are not billed rows. Capture EXPLAIN/row metadata for biggest routes before changing SQL/indexes.
3. Friends candidate relationship checks, remaining list pagination, cache-first profile lookups on other explicit screens, and residual bind-time engagement outside bundled adapters still need request-level attribution. The previously listed Group 120/200 reads and legacy reactor row hydration are already paginated/batched in current main.
4. Decide product behavior for durable background push. Turning it off delays message/post notifications; do not silently claim both zero-background traffic and unchanged delivery. No blanket background transport kill added.
5. Startup endpoint consolidation requires backend design preserving profile bans, privacy, configuration, offline UI and lazy tab loads. Current secondary lazy loading is not exactly one total API.
6. Room migration is NOT implemented: current caches are RAM/preferences/document-page storage. A wholesale migration needs schema, actor isolation, invalidation, eviction, migration and logout tests.
7. No safe universal one-query-per-request claim: authentication/authorization, transactional dual-side mutations, notification delivery and canonical compatibility all have real work. No security guards/indexes were removed.

## Quota calculator

Run `python3 scripts/quota_calculator.py docs/quota/scenario-1000-assumed.json`.

Input is **billed rows per session**, session frequency, invocations, independent daily background work, and explicitly supplied daily limits. It does not equate ten displayed records with ten billed reads. Example is assumption-only: 1,000 users x 1 session x (100 reads, 5 writes, 30 invocations) -> 100,000 reads, 5,000 writes, 30,000 invocations, with **zero headroom** against the owner's read/write target before background work. Two sessions/user doubles those totals. Not proof the current app fits Free.

Cloudflare Free plan entitlement/CPU/storage/KV/R2/AE limits have not been independently refreshed here. Supply actual account limits and measured rates; do not mistake the owner's 100k/5k budget for a quoted provider allowance. A 100% guarantee is not supported until the outstanding gates pass.

## Verification for this pass

- `python3 -m unittest discover -s scripts/tests -q`: **440 PASS** (436 existing + four calculator cases).
- Android production compile and full JVM suite: **PASS**, run https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45321899910 at code `d9346e7`.
- New actual `FeedAuthorBatchDemandTest`: 10 missing authors -> one bounded document-ID query; 100 repeated page submissions -> no additional query; explicit reset permits recovery; no individual `Fb.getUserFresh` call. This checks the Android demand path, not Worker billed rows.
- Existing polling foreground/background policy, actor isolation, reaction write-queue and comment ACK tests run in that green suite. No new whole-app background-zero or 50-view D1 batching claim.
- First intermediate run 45321715425 exposed unnecessary Firebase setup for authorless test pages; production now skips author lookup setup entirely when the submitted page has no eligible missing author. Final full suite passed without deleting the failing dedup/footer test.
- No release APK/AAB was built. PR #51 and #52 were merged; the Worker was deployed and health-checked. No paired production app session or daily quota acceptance has yet been demonstrated.


## Production quota follow-up — 2026-11-26

- PR #51: `https://github.com/zulqarnaindiraj786-cell/Friend-book-/pull/51` — merged; production Worker version before the second follow-up was `4fc8869a-be3a-49ff-9dba-dd28d2bf5a6f`.
- PR #52: `https://github.com/zulqarnaindiraj786-cell/Friend-book-/pull/52` — merged; additive D1 migration `0105_canonical_wrapped_friend_indexes.sql` applied successfully.
- PR #53: `https://github.com/zulqarnaindiraj786-cell/Friend-book-/pull/53` — merged; preserves indexed comment candidate counts when the optional epoch hint is unavailable, using the old grouped query only as a compatibility fallback.
- PR #54: `https://github.com/zulqarnaindiraj786-cell/Friend-book-/pull/54` — merged; migration `0106_canonical_comment_shadow_epoch.sql` adds transactional invalidation for canonical comment insert/delete/resource-id move, protecting positive cached legacy counts from stale canonical-shadow state. This adds one small epoch-row write for those canonical mutations.
- Latest production Worker version after PR #54: `9255e493-dbbf-45b4-bb32-76f85499430e`.
- Validation: `npm test` 343 PASS; schema tests 2 PASS; migration tests 3 PASS; index-plan tests 4 PASS. PR #52 main validation run: `45729493478`; its production deploy run: `45729493430` (success). PR #53 validation run: `45730008091`; production deploy run: `45730242824` (success). PR #54 validation run: `45730887916`; production deploy run: `45730978196` (success).
- Post-deploy checks: Worker health, read-only D1 `SELECT 1`, and R2 bucket HEAD passed. No user data was written by the verification steps. The deployment workflow's Firestore copy and AAB dispatch steps were skipped.
- The deployment preflight reported daily account analytics at 2026-11-26 00:33:34 UTC: 99,160 D1 rows read and 2,732 rows written. The report is delayed/adaptive and not a live quota balance; the owner's 100k/5k values are internal targets, not Cloudflare plan limits. The bounded inventory diagnostic sampled 336 documents (1,344 rows read) and found zero legacy-only comments/posts/reels in that sample; it is not a full-table census.
- The 00:30 UTC six-hour traffic report still contained historical comment/friendship hotspots. Its 5-minute window (00:25–00:30 UTC) showed only 145 reads and no comment/friend guard shape; that window was quiet and cannot prove savings. The two large comment query shapes were both indexed `quota_comment_candidates_*` probes (707,724 reads/27 executions and 273,683 reads/467 executions in that 6-hour sample), not the old unindexed grouped fallback. PR #53 protects the epoch-unavailable fallback but does not remove those first-time candidate-row reads. No controlled before/after app session has yet measured the effects of PR #51/#52/#53.
- Outstanding: capture the same real app session before/after with paired Android request traces and D1 billed-row analytics; verify repeat legacy-comment counts under realistic resource reuse; measure the canonical wrapped-friend guard after the new indexes under actual friend-request traffic; run a staged representative 2,000-DAU workload. No 2,000-DAU capacity guarantee is made.
