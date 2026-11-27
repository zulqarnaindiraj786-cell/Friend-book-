# Own and visitor Profile verification

Verified implementation: `998dec0` (2026-11-19). Scope: ProfileActivity; no APK, backend deployment, version bump or PR merge.

## Implemented

- Own/visitor screen lifetime and account ownership gate asynchronous results. Stopping closes pagers/count registrations/header observers; return starts one demand per data family, not repeated concurrent starts.
- Friends/posts/reels request exact pages of 10. Friends use a 300-second page cache; posts/reels use 120 seconds. List layout prefetch is disabled. Automatic near-end demand requires recent touch and downward movement; explicit footer demand remains available.
- Missing friend profiles are hydrated in document-ID batches of at most 10, not one GET per friend. Stopped results do not populate UI caches; return permits interrupted hydration again.
- Relationship existence uses bounded two-user queries, cached for 300 seconds, rather than scanning friend lists. Request send/cancel/accept/delete/unfriend have pending action guards. Cancel uses the known request ID; accept commits the relationship/request/count changes in one batch. Confirmed writes update visible state without an immediate lookup loop.
- Post count is independent of first-page completion, single-flight per screen, reused across profile instances for 120 seconds, actor/target keyed, and invalidated by post writes/deletion.
- Highlight binding performs no story document reads. Explicit Load 10 highlights fetches a bounded document-ID batch, remembers loaded stories, and exposes a retry on failure. This intentionally changes the rail to demand-driven loading.
- Subscription status is fetched once per screen generation only for an enabled creator viewed by another user. Own/disabled subscription profiles do not issue that query.
- Photo result handling rejects unrelated request codes, concurrent uploads and changed accounts. Upload completion rechecks ownership before patching the current user or sharing the optional photo post. Hidden-screen callbacks avoid UI updates.
- Temporary profile/header failures preserve existing content. A confirmed permission denial closes the screen; a cached missing profile is not treated as deletion.

## Evidence

- Android compile and full JVM suite: [45338416962](https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45338416962) **SUCCESS**, implementation/test head `998dec0`.
- `python3 -m unittest discover -s scripts/tests -q`: **469 tests PASS**.
- `ProfileDemandTest`: six Robolectric behavioral regressions: 100 repeated own/visitor starts; count single-flight/cache/write invalidation; 10 missing friends batched once and stale response rejected; own/disabled subscription zero-demand and enabled visitor coalescing; repeated highlight binds zero-demand plus 100 load taps one batch; 100 request sends one write with old-account notification suppressed.
- `test_profile_demand_quota.py`: five source wiring contracts for page bounds/TTLs, batched hydration/lazy highlights, gesture gating, photo ownership and bounded request actions.
- Existing profile/privacy/feed consistency regressions remain green; updated source-string contracts follow the new cached query/pager/helper implementation.
- `git diff --check`: clean.

## Limits

These are source, compile and mocked runtime proofs, not installed-device or Cloudflare billed-row telemetry. They do not certify production full-day quota targets, eliminate all possible backend races, or prove cross-device exactly-once friendship writes. No owner download or diagnostic capture was requested. Existing installed APK does not contain these changes until a later agreed consolidated build.
