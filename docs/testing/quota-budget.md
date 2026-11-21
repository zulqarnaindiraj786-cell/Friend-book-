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
