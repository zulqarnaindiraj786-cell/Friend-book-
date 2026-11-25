# Document listeners and one-shot reads: shared queued operation

Source **bf19bf652581c0a43b74867a0d7cfd4c7dca4bad**, Android compile/JVM **45301052941 SUCCESS**.

## Remaining bypass found

DocumentReference DEFAULT get calls shared their queued reads, but document listeners independently queued raw transport reads on the listener executor. Multiple subscriptions and get callers could therefore repeat identical operations when queue execution did not overlap at HTTP time. The transport coalescer alone did not cover that window.

Both paths now share immutable serialized document responses through the same registry **before enqueueing**, while retaining the initiating listener/default executor lane. Each consumer parses its own snapshot and keeps its independent listener delivery/error state and active-account checks. SERVER reads stay independent; CACHE still bypasses networking. The key encodes path and profileAudience option as a JSON tuple, scoped by actor. Mutation invalidation detaches both option variants. No TTL, periodic refresh, listener removal, auth-read cache or server permission change was introduced.

The refactor also fixes a correctness omission: one-shot withProfileAudience().get() now forwards its option, rather than issuing the ordinary endpoint and potentially sharing that result. Audience and ordinary reads cannot join. General document-cache storage remains unchanged; this is not query-row promotion into a full-document cache.

## Verification

- New actual-store controlled-executor test issues one initial call plus 100 overlapping calls through another lane before allowing execution: **one queued operation and one transport getDocument call**.
- Other tests separate options/SERVER operations, detach mutation-invalidated reads, and ensure a queued account switch cannot invoke document transport.
- **428 Python / 298 Worker passed**, 48-workload replay completed successfully, diff whitespace check passed.
- Android compile/JVM **45301052941 SUCCESS** at exact source above.
- Earlier two CI runs failed a test's transport-invocation count: CALLS_REAL_METHODS recorded the stubbing invocation in addition to actual execution. The test now clears setup invocations before measuring; no production behavior was relaxed to make it pass. Bounded XML failure reporting added to Android workflow.

No production duplicate frequency, billed-row savings, installed-device trace or daily quota acceptance is claimed. This Android change is **not included in APK 178**, and no new APK/AAB was built in this turn. Production Worker remains the previously deployed exact-pair source 6dfdebb; no deployment/migration/merge this turn. Notification-write frequency and active-use quota acceptance remain unresolved.
