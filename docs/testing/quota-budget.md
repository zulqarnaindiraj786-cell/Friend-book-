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
