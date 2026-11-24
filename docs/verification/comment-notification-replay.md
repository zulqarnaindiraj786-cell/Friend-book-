# Saved-comment notification identity

Source: c3fe505305e554ec837b7efba5b5c58faddc71fd and 056cee10ea4b921e614b00204d496302e8025114.

Normal post/reel sends and unsent-draft retries now use the saved comment ID, actor, recipient, collection/resource and event role for create-only notification identity. Reel reply aliases normalize to the same role as draft retries. Separate comments and recipients remain distinct. Existing action identities are unchanged. Missing comment identities retain the existing ordinary notification behavior.

Pending identical notification creates share an account-scoped in-memory guard, removed on completion or synchronous failure. This is not a persistent success cache. A later replay still reaches create-only storage, where ALREADY_EXISTS suppresses duplicate push. Genuine new comments are not suppressed.

Verification:
- Android compile/JVM Actions 45302309307 SUCCESS, source 056cee1.
- Actual Notify Robolectric test: 100 callbacks resolve to one stored identity and one push; a second comment produces another identity and push.
- Event-key tests cover resource, actor, recipient, comment and role separation and reel retry aliases.
- 430 Python tests PASS; 298 Worker tests PASS; local quota replay completed (100 users, concurrency 5).
- git diff --check passed before push.

Limits: not packaged into APK178; no new APK, Worker deploy or PR merge. No production daily savings measurement or claim that the owner's post-reset spike is fully resolved. Sequential replay can still incur create-conflict checks, and legitimate first notifications retain their writes.
