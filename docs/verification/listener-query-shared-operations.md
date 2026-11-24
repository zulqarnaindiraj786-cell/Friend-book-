# Queued listener-query operation sharing

Source: 606f702526e57ce5d59b25adb6bfcca15a16818f; additional failure tests d000b5c.

## Confirmed source defect

Document listeners already shared queued work, but query listeners still submitted an independent executor job per registration. An identical posts/users query opened by overlapping consumers could repeat transport even when requests and known revision states matched. This establishes a reproducible duplicate-capable source path, not its production frequency or ranking.

## Change

Query listeners now share an active Task<String> before entering the appropriate listener/inbox/chat executor. Identity includes the authenticated actor and the complete serialized query body, including known revisions, filters, order, cursor and limit. The queued request body is frozen. Each delivery independently parses the immutable response and merges against its own prior rows; mutable response objects are never shared.

Document and collection mutation invalidation detach listener flights alongside ordinary query flights. Existing consumers still complete, while post-invalidation requests start new work. Account identity is checked before and after transport, which remains bound through runForActor. Per-entry refreshPending, forceDelivery, callback delivery state and budget handling remain. A push during pending work retains its follow-up request. No new TTL, fallback, polling loop or authorization bypass.

Different revision states do not share, even if their base query matches. Listener requests intentionally remain separate from ordinary one-shot queries because their revision-bearing response/merge contracts differ.

## Verification

- Android compile/JVM run 45305755748 SUCCESS on d000b5c.
- Actual store helper, controlled executors: 101 overlapping identical calls queue one job and invoke transport once, separately exercised for posts, users, notifications and chats. This is a test workload, not observed device traffic.
- Tests verify completed results are not cached; parsed JSON isolation; frozen queued body; revision/account separation; mutation detachment; queue rejection and transport failure permit a fresh retry.
- Existing actor-binding source regression updated to assert the new queued helper rather than the removed per-entry transport wrapper. Transport remains actor-bound.
- 430 Python tests PASS; 298 Worker tests PASS; actual Worker/local SQLite quota replay completed for 100 users, concurrency 5, 48 workloads; diff check PASS.
- Initial Android run 45305661463 failed during Robolectric jar fetching before compilation/tests. The subsequent green run includes the failure/retry regression tests; no dependency verification was relaxed.

## Delivery and acceptance limits

No APK build, Worker deployment, migration or PR merge performed. APK178 does not contain these Android changes. No production billed-read savings, installed-client result or representative-day <=100k reads/<=5k writes acceptance is asserted. The owner's post-reset spike remains an open runtime acceptance issue, not something these controlled tests can retroactively explain.
