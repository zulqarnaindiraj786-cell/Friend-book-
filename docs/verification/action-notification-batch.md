# Like/comment action and inbox batching

## Change

For the D1 engagement routes, the like/reaction acknowledgement now carries a stable notification event identity. When the sender's like-notification preference is enabled, the Worker derives the recipient from the freshly authorized post/reel, and creates canonical plus compatibility inbox rows in the same D1 batch as the absolute reaction and compact acknowledgement. The Android client queues FCM only after the combined batch confirms a newly created event. Stable IDs make duplicate action retries no-ops for inbox creation.

A newly created D1 comment similarly creates its owner and (when distinct) reply-recipient inbox events in the same D1 batch as the comment/details insert and ACK. Recipients, author and reply target come from server authorization and the canonical parent comment, not client-supplied recipient IDs. The response reports only newly created events; Android queues their pushes without issuing another notification-document write. D1 draft requests keep the same stable comment ID and request event delivery. Firebase-backed legacy paths retain their existing notification behavior.

## Verification

- Cloudflare full Node suite: **326/326 PASS**; targeted interactions suite: **43/43 PASS**.
- Python regression suite: **525 PASS**.
- Local SQLite/Worker regressions prove one atomic batch creates the expected like/comment inbox rows, stable retries do not create duplicate rows, and comment/reply recipients are distinct and server-derived.
- `git diff --check` clean.
- Android JVM execution has not run locally: this sandbox has no Java/JAVA_HOME. Do not infer Android compile/test success until the `[no-release]` CI run completes.

## Important boundary

This removes the *separate client-to-Worker notification write request* for D1 likes and comments; notification persistence remains in the same transaction as the action. It does **not** mean one billed D1 row-write: a reaction may change reaction/history rows plus two notification storage representations; a comment writes comment/details plus inbox canonical/mirror rows, and indexes/triggers can add work. Authentication, authorization, idempotency, mirrors, inbox delivery and privacy remain. No production Worker deployment, APK/AAB, PR merge, device trace or live D1 billing measurement was performed. Actual quota savings and daily targets remain unverified.
