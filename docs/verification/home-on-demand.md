# Home on-demand audit (2026-11-19)

PR #36 remains unmerged. No APK/AAB requested or published.

## Implemented
- Initial feed and each scroll-boundary page: 10 posts, no automatic page draining.
- Exact account/query/cursor cache reused, including empty results. Explicit pull bypasses cache.
- Committed collection invalidations bypass stale cache. Missing/evicted cache fetches once.
- Home feed has no polling or query listener; local reaction observer keeps own actions instant.
- Friend graph persisted and reapplied to friends-only post visibility on cache hits as well as server results.
- Visible header: three live cards and three story metadata rows; existing Live Hub and More stories navigation retained.
- Home featured Reels query removed; previews reuse loaded video posts.
- Suggestion candidates fetched only when discovery section intersects viewport.
- RecyclerView feed/story prefetch disabled. Video previews use posters, never MP4 frame extraction.
- Media playback still starts only after an explicit user tap.
- Badge queries reuse account-scoped disk snapshots; FCM updates badges locally.

## Document-delivery estimate, NOT live D1 quota evidence
Previous app-open bucket: 3,720 documents per user/day (20 opens × 186).
New scenario: one uncached open × 117 + 19 cache-hit opens × 1 auth allowance = **136**.
Friend graph separately: 60 assumed friends once. Discovery: 3 candidates once visible.
A wholly uncached open including 60 friendships: **177** documents; 180 including visible discovery.
Repeat open: **0 extra Home/badge query documents**, plus 1 conservative auth allowance.
Two explicit refreshes/day are separately charged (158 documents). Cache eviction, writes or push
invalidations can cause extra cache misses, so 136 is not a universal daily cap.

Five-user scenario: **19,945 document reads / 1,295 row-write operations per day**.
If all 20 opens miss cache: **36,965 estimated reads/day** under the script's other assumptions.
The notification query was previously undercounted at 20; actual `limit(450)` is now charged:
2,250 documents/user/day for five notification opens, now the largest bucket.

IMPORTANT: LIMIT bounds returned documents, not D1 scanned/index rows or engagement subqueries.
Cron scan counts, write amplification and other activity costs remain assumptions. No production
telemetry/device trace was collected; neither actual quota compliance nor a 99% reduction is proven.
See `home-on-demand-budget.json` and run `python3 -m scripts.loadtest.five_user_budget`.

## Verification
- Worker: `npm test`: 234/234 passed.
- Python: `python3 -m unittest discover -s scripts/tests`: 321 passed.
- Android: GitHub Actions `Android unit tests` compiles debug Java and executes JVM tests;
  latest commit run must be checked before marking complete. New behavioral tests cover empty
  cache, forced refresh, invalidation and eviction. No device-level scrolling/visual test claimed.
