# Like/comment action and inbox batching

## Change

For the D1 engagement routes, the like/reaction acknowledgement now carries a stable notification event identity. When the sender's like-notification preference is enabled, the Worker derives the recipient from the freshly authorized post/reel, and creates the canonical inbox row in the same D1 batch as the absolute reaction and compact acknowledgement. It deliberately does not create a legacy mirror row: current inbox pagination, point lookup, authorization and FCM payload reads are canonical-only. Existing legacy rows are retained; no data is deleted. The Android client queues FCM only after the combined batch confirms a newly created event. Stable IDs make duplicate action retries no-ops for inbox creation.

A newly created D1 comment similarly creates canonical owner and (when distinct) reply-recipient inbox events in the same D1 batch as the comment/details insert and ACK. It does not create notification compatibility-mirror rows because active inbox reads use canonical storage. Recipients, author and reply target come from server authorization and the canonical parent comment, not client-supplied recipient IDs. The response reports only newly created events; Android queues their pushes without issuing another notification-document write. D1 draft requests keep the same stable comment ID and request event delivery. Firebase-backed legacy paths retain their existing notification behavior.

## Verification

- Cloudflare full Node suite: **326/326 PASS**; targeted interactions suite: **43/43 PASS**.
- Python regression suite: **525 PASS**.
- Local SQLite/Worker regressions prove one atomic batch creates the expected like/comment inbox rows, stable retries do not create duplicate rows, and comment/reply recipients are distinct and server-derived.
- `git diff --check` clean.
- Android JVM execution has not run locally: this sandbox has no Java/JAVA_HOME. Do not infer Android compile/test success until the `[no-release]` CI run completes.

## Important boundary

This removes the *separate client-to-Worker notification write request* and skips the legacy mirror row for inbox events created by D1 like/comment actions. It does **not** mean one billed D1 row-write: reactions/history, comment/details, canonical inbox rows and indexes/triggers can still add work. Authentication, authorization, idempotency, existing mirrors and inbox delivery remain. The narrower mirror change is safe only for these action routes because current read/FCM paths are canonical-only; generic notification writes and legacy data are unchanged. No production Worker deployment, APK/AAB, PR merge, device trace or live D1 billing measurement was performed. Actual quota savings and daily targets remain unverified.
