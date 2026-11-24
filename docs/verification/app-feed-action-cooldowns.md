# Android feed/action demand policy

Scope: Android only. No Worker, D1 schema, Supabase, APK release, deployment or PR merge.

Code: 015f1e3; test package correction: 3cbbd45.

## Changes
- Home uses FeedHeadReads: display saved head first, reuse valid exact-page cache for 120 seconds, otherwise refresh in background. Existing query invalidation and account/generation checks remain. Loaded/scrolled page tails are not rewound on lifecycle return. Page size remains 10.
- Cached feed engagement is seeded into shared D1Reactions state before rendering; Home/Profile adapters already suppress child engagement fetches for bundled pages.
- Shared actor/resource reaction admission ignores additional actions within one second. Feed/reel like buttons are disabled for one second. Existing optimistic state and write queue are retained.
- Notify.follow shares the pending task and completed ACK within one second for the same actor/target/direction, including its notification side effect. Profile, reel and live follow buttons are briefly disabled.
- SearchReadPolicy is now 500ms; mention and admin search use the same budget.
- Explicit feed refresh gestures are throttled to one per second. Existing transport in-flight sharing remains; this is NOT a claim that every completed GET across the app now has a one-second cache.
- Comments' existing local append/stable ACK implementation is retained, not replaced with a list reload.

## Verification
- 436 Python tests PASS.
- Android compile and JVM suite PASS: https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45320507056
- Earlier run 45320338954 failed solely because the new Query test subclass was outside its package; corrected without changing production visibility.
- FeedHeadReadsTest: 100 warm cache entries -> 0 transport starts; stale head -> cached callback followed by one refresh. Forced read bypasses cache; inactive actor starts none.
- ActionRequestFlightsTest: 101 overlapping follow demands -> one action start; completed double tap shares ACK; after one second another action is admitted; actor/target separation and failure retry checked.
- ReactionDemandTest: 100/101 immediate actual reaction taps -> one PUT, no summary GET; next tap after one second -> second PUT. Previously the even 100-tap burst intentionally queued a second PUT; odd 101 taps already required one PUT. These are controlled tests, not real usage frequencies.

## Evidence boundary
No connected Android device/network capture is available. Real first-feed total API calls before/after and network screenshots have NOT been obtained. Owner-reported 5–10 duplicate calls is not substituted for a measured baseline. No full-day quota target, installed-client behavior, whole-app runtime correctness, or production quota savings is established by these tests. Changes remain on unmerged PR36 and are not in installed APK178.
