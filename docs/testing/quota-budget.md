# Quota regression checks

Android uses D1 for app documents and Firebase for authentication and push. A user count alone doesn't identify which service limit was reached. Check Cloudflare Workers request/error counters, D1 rows read/written per operation, and Firebase Auth/FCM errors for the same time window. Do not use production identities for a synthetic load test.

Steady-state upper estimates per device and listener (not production measurements):

| Idle operation | Before | After |
|---|---:|---:|
| Foreground inbox polling | 720/hour | 240/hour |
| Background inbox polling | 360/hour | 180/hour |
| Foreground notifications polling | 240/hour | 30/hour |
| Profile presence fallback writes | 120/hour | 40/hour |
| Home live/friend listeners while on another Main tab | Periodic | None |

Assumes unchanged successful results after warm-up, one listener for each row, and no pushes/manual refresh. Active room signaling and foreground chat retain their healthy-network cadence. Periodic failures back off and HTTP 429 pauses both document pollers temporarily; explicit refreshes, mutation notifications and FCM invalidations bypass periodic backoff.

Before releasing: run staging with 200 synthetic concurrent sessions; compare idle and active workloads separately. Check Home/Friends tab switching, message delivery, incoming calls after idle both foreground and background, notification badges, and recovery after a simulated service limit. Verify requests and D1 rows examined, not just response size. Do not infer 200-device capacity from unit tests alone.

### Notification page follow-up

The badge (32) and notification history (20) now use recipient/order indexes and a cursor, fetching at most one page per store per SQL batch for standard records. Historical exceptional timestamp formats still use the compatibility path. The SQLite regression checks unread paging and canonical precedence with a stale legacy mirror; this is not a production rows-read measurement.

The static list inventory still flags Home friendship membership and Main chat-room badge/call fallback. Neither was arbitrarily capped: doing so could hide an existing friend or drop a call from a room outside the cap. They need dedicated participant-indexed projections with correctness tests before a cap is safe. Background data-only push now posts the Android notification without also running foreground list invalidations; foreground pushes still refresh authenticated data.

### Event-driven inbox (2.9.1)

General document listeners check on attachment, after a foreground return, on relevant writes and on FCM hints. Their steady-state scheduled read rate is now zero for chat lists, messages, notifications, requests, profiles and stories, in foreground and background. Active foreground call/live/typing signaling keeps its targeted checks; completed work retries and token-change registration are not polling. An authorized call start sends a short-TTL FCM hint only after commit; a foreground receiver or a user tapping the notification reads the actual room state before opening the call.

Bounded listeners (up to 40 rows) accept document deltas after their first successful server response. The response contains ordered IDs and revisions plus only changed documents. This reduces bytes sent, not the cost of evaluating the authorized D1 query when a refresh is triggered. With no foreground socket for general social events, remote changes without an FCM hint (or a delayed/dropped hint) appear after a foreground return or explicit refresh, not continuously.

### Whole-app quota verification status

| Area | Live optimization | What still needs evidence |
|---|---|---|
| Home Feed | Indexed post pages merge historical timestamp exceptions without dropping old posts | Compare rows per page on actual production Feed traffic; the earlier legacy scan cost 668 rows per execution, not necessarily per user-visible page |
| Profiles and people | Exact-ID seeks, indexed first pages and keyset continuation | Attribute remaining user fallbacks by query shape before narrowing them |
| Chat and inbox | Indexed membership/history probes, skip unchanged membership-index updates, one room check per atomic message batch | Replay healthy, delayed-push and offline sessions; compile-tested client polling/receipt changes are not in APK 151 |
| Notifications and push | Recipient page indexes, durable send claims and unchanged-token write suppression | Test badge/history/call freshness while replaying real traffic; one audit showed 36 no-op registrations and zero row writes |
| Stories, Reels, live, comments and reactions | Existing bounded and indexed paths where supported; collect read/write patterns separately | Verify individual paths under representative traffic; do not equate smaller responses with fewer D1 reads |

For each area report query count, rows read, rows written and errors in matched windows, then confirm privacy and unchanged result order. APK install checks do not exercise those production paths. The published 151 APK passed fresh install and upgrade from 150 on the emulator.

### Capacity gate for large rollout

The shown D1 Free budget is 5,000,000 rows read and 100,000 rows written/day. At 100,000 **daily active** users that is at most 50 reads and 1 write per user per day, before fixed jobs, indexes and headroom. Registered users and daily active users are not interchangeable. Measure actual rows by workload (idle, browsing, messaging and posting) in staging, include fan-out and retry costs, and compare forecast peak and daily load against platform limits before claiming this scale. No production load generation on the current quota.

#### Offline replay (no D1 usage)

Run `node scripts/loadtest/d1_quota_replay.mjs 30` (or `200`) with Node.js 22+. It invokes the Worker handlers against SQLite with every migration applied. It uses fabricated identities, doesn't run a server or contact Firebase/Cloudflare, and excludes bootstrap writes from workload counts. At 30 synthetic users, 15 message sends changed 135 SQLite rows, and the first 15 receipt/reset requests changed 105; replaying those receipts changed zero. Thirty unchanged in-window profile heartbeats also changed zero. These counts include guard table and trigger side effects, but **exclude SQLite index maintenance and aren't billable D1 writes**. They aren't concurrent traffic or a capacity certification. Separate message/receipt guard write overhead from content writes before forecasting scale.

The next replay with grouped atomic chat snapshot checks (200 synthetic users) measured 7 changed SQLite rows per send and 5 per first receipt/reset, down from 9 and 7, respectively. Each still uses an atomic batch that compares the stored room/message snapshots and rolls back on conflict. Duplicate receipts remained at zero. This does not measure D1 index costs or establish daily active user capacity.

`node scripts/loadtest/d1_quota_replay.mjs 1000 200` runs local waves of up to 200 overlapping Worker requests, with SQLite commits serialized on one connection. It checks message ownership, delivered/seen state, unread resets, and private posts/Reels not leaking into public pages. Optimizing authorization lookahead changed a five-item Feed or Reels page from 26 to 12 SQLite statements in this fixture. Statement counts are not rows read or billed. This run does not exercise network, actual D1 concurrency, device playback, or sustained production load.

The expanded 1,000-user / 200-overlap offline replay executes 8,498 real Worker requests across chat, profile heartbeat/detail, Feed, Reels, Stories, Live and the notification inbox. Read-only pages and duplicated receipt/heartbeat scenarios wrote no rows in the replay. Actual D1 billing, FCM delivery, media playback and real network capacity are outside this test.

The broader replay adds actual Worker endpoints for post reactions, comments, push-token registration (no push sent), and notification opens. With 1,000 synthetic users / 200 overlapping requests it ran 16,498 requests. Each of the four repeated-operation workloads made zero SQLite row changes. Initial reactions, comments, device registrations and notification opens still change rows. The harness explicitly rejects outbound network calls; no production or staging usage is inferred from these results.

### Consolidated regression check

On the batch including indexed people/post pages and atomic bulk-mention claims, 165 Worker tests, 259 Python checks, the SQLite index-plan check, and the 16,498-request offline replay passed. The replay used 1,000 synthetic identities and up to 200 concurrent requests. Duplicate heartbeat, receipt, reaction, comment, push registration, and notification-open workloads each recorded zero changed rows; read-only profile and Feed/Reels/Stories/Live/inbox workloads also recorded zero changed rows. Some no-op requests still read or execute SQL. Bulk claim contention is covered by a separate regression test, not push delivery in the replay.

The production read-only follow-up (Actions run 44877309679) reported 2,258,129 reads and 44,186 writes for the UTC day in progress. Its quiet five-minute sample is insufficient to attribute savings or certify quota headroom. No production traffic was generated by the replay.

### Consolidated feature audit

| Surface | Checked path and quota decision | Remaining measurement gap |
|---|---|---|
| Feed and Reels | Indexed pagination starts at the requested size in each store; refill only when privacy filtering leaves a gap. Historical exceptions and viewer checks preserve order and access. | Production billed scans on rare fallback shapes; media traffic is not D1. |
| Stories and Live | Replay checks read-only pages; Live discovery seeks indexed status. Live signaling polls remain for correctness when a room is visible. | Real session duration and reconnect traffic. |
| Chat | Replayed sends, first receipt and duplicate receipt; unchanged list listeners are push-driven, with a visible-room fallback and typing retained. Invalid typing does not read the room. | Installed-client request rate and signaling fallback under real delivery failures. |
| Groups | Role, membership, private access and legacy recovery covered by Worker tests; ordinary list listeners do not periodically poll. | Group-specific billed reads at scale, including compatibility paths. |
| Notifications | Indexed inbox excludes stale mirrors before deciding to fall back. Retry opens and bulk delivery claims are covered; notification updates remain push-driven. | Real push delivery and retry workload. |
| Search | Prefix and tag paths use indexes for supported query shapes; name cursors preserve projection and ordering. | Unsupported and legacy query shapes can still require a wider read. |
| Profile and people lists | Request-local reuse, indexed recent profiles, and first picker request sized to the displayed page. Repeat unchanged heartbeat writes are skipped. | Real clients still on older Android polling behavior. |

This is a code-path and representative test audit, not a claim that all possible production requests consume the minimum or that Free quota cannot be exhausted. Do not generate synthetic production traffic to fill the measurement gaps.

### Reset-window baseline before consolidated deployment

Read-only Cloudflare Analytics audit 44965096457 (UTC 2026-11-22 00:07) reported, for this database rather than a single device or the whole account, an adaptive daily aggregate of 39,288 rows read and 4,429 rows written. In the preceding 15 minutes its largest reported read shapes included notification page queries (15,616 reads / 244 executions and 7,776 / 243), friends (5,841 / 51), and point lookups (4,401 / 4,401). The two notification shapes may be parts of the same logical request and cannot be attributed to one installation from this report. Leading write shapes included canonical/legacy document inserts and social-guard deletion; the aggregates do not identify individual features. Sampling, different windows, and indexing prevent direct comparison to a dashboard screenshot or a causal claim. Investigate notification invalidations, clients and document writes with matched windows; do not remove correctness or authorization checks based on these aggregates alone.

The pending Worker batch includes migrations 0068–0071, conditional writes and index changes. Local Worker, schema, migration and index-plan tests pass; actual D1 costs and user-visible behavior require post-deployment observation, not extrapolation from local SQLite or the baseline above. The deployment may itself use D1 write quota for migrations.
