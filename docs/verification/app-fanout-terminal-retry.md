# App background fan-out terminal retry fix

Source880b83e2c8d2e795f735822e01607ba587b60dda; Android compile/full JVM run45315361460 SUCCESS. Source-regression expectation updated in3e15048;434 Python tests PASS.

## Reproduced source defect

PushFanoutWorker previously returned Result.retry for every caught exception until attempt8. A denied/deleted/invalid target raised through Tasks.await could therefore schedule the entire lookup/fan-out again even though retrying could not repair the explicit terminal error. This is an app scheduling defect; its production frequency/ranking is not established.

## Change

A typed, wrapper-aware policy stops retrying explicit terminal D1 codes: permission denied, not found, canceled, unauthenticated, invalid argument, failed precondition, out of range, unimplemented and unexpected already-exists escaping the existing per-notification conflict handler. Existing stable-ID ALREADY_EXISTS handling in the friend-post loop remains intact.

Outages, timeouts, unavailable/aborted/resource-exhausted responses and unknown errors retain the existing bounded exponential recovery (attempts0–7 may retry; attempt8 stops). Empty document IDs fail before lookup. Account switches after a failure skip the departed actor's work. Recipient membership/author checks, unique work, notification create-only semantics and actual push dispatch are unchanged. No ACL cache or removal of genuine first delivery.

## Verification

- Actual Worker.doWork constructed with WorkerParameters and executed off the UI thread: denied and not-found chat lookup outcomes are failure after one lookup, unavailable lookup outcome remains retry after one lookup.
- Pure policy tests cover wrapped terminal codes, outage/timeout/unknown recovery, retry cap and cyclic cause chains.
- Initial Python run failed because a lexical source guard expected the old inline attempt comparison. Guard now verifies delegation plus the preserved policy cap and terminal codes; full434 tests PASS. This was not a production behavior failure and was not hidden by relaxing the cap.
- Android compilation and full JVM regression run45315361460 SUCCESS.

No APK packaging, Worker rollout, schema change or PR merge. Not installed in APK178. This closes only terminal-error replay behavior, not the separate partial fan-out replay/push-idempotency review or whole-app quota acceptance. It cannot establish100% defect freedom or measured daily billing savings.
