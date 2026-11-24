# Whole-app quota audit — working ledger, NOT 100% completion

## Evidence and scope

Source review covers Android and the Cloudflare request/maintenance paths. Refreshed machine-readable inventory: `docs/testing/android-demand-inventory.json` (214 Java + 1 Kotlin source files). Its line counts are leads, not requests, SQL executions, billed rows, or measured load rankings. Cloudflare backend was inspected but NOT changed or deployed in this pass. Supabase unused. PR remains unmerged.

Never replace authorization with client TTL, public caching of private profiles, or an unconditional one-query rule. D1 rows read/written, Worker invocations, HTTP attempts, and SQL statements are different metrics. Existing owner-observed 20.5k reads/504 writes after reset remains unresolved session evidence, not an attributable per-user benchmark.

## 12-area inventory

| Area | Android entry / backend owner | Read and write demand | Current controls / outstanding review |
|---|---|---|---|
| 1 Feed/Home | `main/HomeFragment`, `post/FeedAdapter`; `firestore-documents.js`, `interactions.js` | Post page, friend graph, visible suggestions/stories; reaction/share/post writes | 10-post head and cursor pages, 120s reuse, bundled engagement, actor-safe cache. Missing-name author lookups moved from bind to submitted-page batches of <=10. Author batch is an additional request, not a merged post/user endpoint. |
| 2 Profile/cover/followers | `profile/ProfileActivity`, `EditProfileActivity`, `friends/FollowListActivity`; `profile-access.js`, `profile-audience.js` | Profile/bootstrap, post/reel pages, relationship/count reads; edits/uploads/follow dual-side writes | Listener profile reuse now 5m; follow list now 10/page with 5m exact cache. Own-account ban/auth refresh must not be removed. Count queries and alternative profile paths still require runtime attribution. |
| 3 Post detail/likes/comments/shares | `post/PostDetailActivity`, `CommentsEngine`, `D1Reactions`, `Reactions`; `comment-api.js`, `interactions.js` | Detail, reaction totals/reactor pages/comment pages; comment/reaction/share writes | Comment pages 10, local append/stable ACK; 1s reaction guard. Post/reel listener reuse now 2m. Legacy reactor UI still has missing-profile row hydration and 30-item pages: NOT resolved by the feed fix. |
| 4 Search/explore/suggestions | `friends/SearchActivity`, `FriendsFragment`, `core/Mentions`, Home discovery | Prefix queries, candidate relationship/profile checks | 500ms debounce; search exact-page reuse now 5m, account/query invalidation retained. Candidate-specific fresh reads remain a review target; no claim all N+1 removed. |
| 5 Notifications/requests | `main/NotificationsFragment`, `MainActivity`, `friends/FriendsFragment`; `notification-access.js`, `fcm-push.js` | Inbox/badge/request reads; read flags, requests/acceptance, notification creates | Notifications now 10/page, 60s head reuse, explicit mark-read batching. Badge windows and mark-all operation budgets are not ordinary list-page sizes. Fanout creates real recipient writes. |
| 6 Chat/messages/status | `chat/ChatActivity`, `ConversationsActivity`, `CallActivity`; `chat-access.js`, `call-access.js`, relays | History/inbox/read receipts/message writes, call state | Shared ListenerPollingPolicy returns NEVER for every path. Push/visible open drive reads. Do not interpret call UI timers as D1 polling. Presence/keystroke writes must stay absent; active call signaling is not optional analytics. |
| 7 Stories/reels/video | `story/*`, `reels/ReelsFragment`, `core/CloudflareViews`; `video-r2.js`, `view-counters.js` | Story/reel pages, views HTTP, video objects; story/reel mutations | View events use Analytics Engine, not per-view D1 writes; emergency read-only seed exists. Rewatch HTTP and media prefetch/buffering still need device traces. Do not introduce D1 view batching where D1 writes are already zero. |
| 8 Groups/pages | `groups/GroupsActivity`, `GroupDetailActivity`, page UI; social/document policy | Membership/content/member queries, group/page mutations | Confirmed oversized source candidates: GroupDetailActivity `.limit(120)` and `.collection("users").limit(200)`. These are NOT yet cursor-paginated. High-priority follow-up, not marked fixed. |
| 9 Settings/privacy/blocks | `settings/*`, `SettingsActivity`, `BlockedUsersActivity`; `account-access.js`, document policy | Preferences/session/block/profile reads; settings/block/session changes | Some user hydration is already chunked (`FeedPreferencesActivity`, document-ID IN <=10). Audit each list and mutation path; do not drop block/privacy checks to save reads. |
| 10 Startup/background/push | `App`, `MainActivity`, `Fb`, `PushDeliveryWorker`, `PushFanoutWorker`; `fcm-push.js`, `scheduled.js` | Auth/profile/config/badges; durable push sends/recipient lookup; scheduled live cleanup/broadcast outbox | Android background FCM refresh is foreground-gated; durable outbox workers are NOT globally foreground-only. Server cron remains every 15m. Neither zero background work nor exactly one startup API has been achieved. |
| 11 Images/video loading | `core/Ui`, Glide/media stack; `video-r2.js` and Worker media routes | URL/object requests; uploads | Ordinary Glide URL load does not call the Android D1 document store. Worker proxy requests can still count invocations and protected media may need authorization. No blanket zero-D1 claim for every URL. Thumbnail/full-res/autoplay behavior needs network capture. |
| 12 Analytics/crash/logs | `CloudflareRequestTrace`, `RequestWindowCounter`, `PushTrace`; `write-attribution.js`, views AE | Local diagnostic logs; view analytics; real security/business audit records | Request trace is debug-only/local, adds no HTTP. write-attribution.js labels SQL only, adds no queries. Security/business audit retention is distinct from click analytics; backend audit paths need cost attribution before deletion. |

## Verified changes in this pass

- Screen cache budgets: profile listener 300s, post/reel listener 120s, search exact query 300s; live/call zero-cache authority unchanged. These do not make every alternative REST/detail path conform automatically.
- Followers/following pages: 20 -> 10; cache reuse 24h -> 5m (freshness correction, NOT claimed quota reduction).
- Notification pages: 20 -> 10; unlimited valid cache reuse -> 60s (freshness correction, may increase revalidation versus previous policy).
- Feed missing-name hydration: per-author fresh GET from bind -> document-ID IN batches on page submission. Cached/embedded names require no lookup. Failure attempts remain bounded until explicit refresh. Backend still applies normal user projection/authorization.
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
3. Group 120/200 bulk reads, legacy reactors/user hydration, Friends candidate reads, list pagination elsewhere, and residual bind-time engagement outside bundled adapters.
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
- No release APK, backend deployment, PR merge, production session capture or daily quota acceptance was performed.
