# Source-only reaction invalidation loop fix — no new APK

Owner explicitly asked to stop repeated APK downloads due to limited mobile data. This pass changes/tests source only: no signed APK, release, backend deployment, Supabase access or merge. APK180 trace is retained as evidence; no new trace/device test requested.

## Confirmed conditional defect

`D1Reactions.fetch` captured generation for a summary GET. If a comment mutation/feed seed/invalidation changed that generation while GET was pending, success discarded the response, removed waiters and immediately called fetch again for every pending callback. Continued invalidations could sustain the response -> GET chain with no total bound or delay. This is a source-reproducible defect, not an attribution of the owner's 45-call trace or historical quota exhaustion.

## Fix

- One opaque read token owns each pending summary flight and any delayed conflict recovery. Old actor/flight completions cannot tear down a new request for the same resource.
- Keep LOADING held through a 2-second conflict-recovery delay; new callers join rather than starting another parallel GET.
- At most three total attempts per conflict chain (one initial + two recoveries).
- Exhaustion discards stale waiters, stops network recovery and uses the existing failure cooldown. Timer clears eligibility only; it sends no request.
- A delayed retry is skipped if an optimistic reaction write is active; its authoritative acknowledgement remains responsible for local state.
- Stable successful retry still updates the shared summary and bound model and invokes waiters. No stale response overwrites newer local generation.
- Actor scope reset clears read tokens. This patch adds no per-second refresh/polling.

## Behavioral tests

`ReactionInvalidationLoopTest` uses the actual D1Reactions path with controlled transport:
1. 100 invalidations during each of three responses: no parallel starts, no retry before 2s, at most three total GETs, no further GET after a virtual minute.
2. A stale first result with comment count999 is rejected; stable retry with count7 updates both model and shared cache. Total two GETs, no further requests after a virtual minute.

Existing full Android tests retain reaction ACK handling, optimistic writes/coalescing, comment append/counters, profile/account scoping, feed sharing and pagination coverage.

## Evidence limits

No installed-client/runtime savings claim: these changes are not in the owner's APK180/181 until a later consolidated package. No new download is required now. Source guards/demand tests do not establish the full-day quota target or explain billed row amplification. Other ledger items remain open; nothing is marked 100% solved on this evidence.

## Verification results

- Final code/tests `3f1f0896136a0102f1e808977871d97bae7b71aa`; production changes `a1156ee` + `211e7ab`.
- 444 Python tests PASS; git diff whitespace check clean.
- Android production compile/full JVM suite SUCCESS: https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45328730345
- Intermediate 45328598045 failed downloading/validating Robolectric jars before tests (exit22), not a passing code verification. Rerun command was unavailable; final source/test push passed the full unchanged CI gates.
- Signed package workflow for head `3f1f089` explicitly SKIPPED, confirmed via workflow status. No candidate release created for this patch.
