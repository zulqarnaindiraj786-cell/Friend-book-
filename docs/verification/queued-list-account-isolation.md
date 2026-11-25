# Queued posts/users list account isolation

Source fixes: 20cbc75. Expanded notification regression tests: 1613c18.

Review of the original posts/users list consumers found that ordinary and cached-head delta queries checked account identity after transport, but not after waiting in the executor queue and before transport. A queued request from a departed account could therefore spend a request under the replacement account, only to discard its response afterward.

Both query execution paths now cancel before transport when their captured actor differs from the current actor. Existing post-transport checks remain. No authorization TTL, query fallback, polling interval or SERVER/CACHE semantics change.

Verification:
- Android compile/JVM run 45304248371 SUCCESS, head 1613c18.
- QueuedQueryAccountTest exercises real Query.get and refreshCachedHead for both posts and users, with controlled scheduling and a real flight registry: account changes before drain result in CANCELLED and zero transport calls.
- Expanded actual Notify test: 100 pending duplicate callbacks produce one create call; failure releases the slot for retry; account switch suppresses old-account push; another account can create independently.
- 430 Python tests PASS after the production query change; git diff --check PASS.

No installed-client savings measurement, new APK, Worker deployment, migration or PR merge. These narrow account-switch cancellations are correctness hardening, not evidence of a leading production consumer or resolution of the reported post-reset spike. APK178 lacks these and the preceding listener-sharing/comment-replay changes. Representative active-use and daily quota acceptance remain open.
