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
