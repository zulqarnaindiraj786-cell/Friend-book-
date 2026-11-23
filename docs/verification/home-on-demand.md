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

Five-user revised scenario: **11,000 document reads / 1,495 row-write operations per day**.
If all 20 app opens miss cache: **28,020 estimated reads/day** under the script's other assumptions.

Correction to the previous report: `limit(450)` was inside the explicit **Mark all read** action,
NOT the normal notification-open path. The old 2,250 notification-open reads/user/day number was
an accounting error, not a measured leak. Do not claim its removal as a performance improvement.
The normal list was already paginated at 20; its genuine repeated-read issue was `startDelta()`
revalidating on tab opens and `next()` always querying the server.

## Notification follow-up
- First page remains 20; cache-hit opening (including empty list) does not revalidate in background.
- Explicit pull bypasses cache; local committed writes invalidate stale query results.
- Older exact cursor pages are cache-first too. Pagination requires real downward scroll reaching
  the last visible row, or Load more. Scroll gate prevents repeated requests for the same boundary.
- Notification RecyclerView prefetch disabled; deep links, All/Unread filters and sender ticks retained.
- Mark all read only runs on button click. 20-row server batches use keyset continuation so already
  marked prefixes aren't repeatedly scanned. Duplicate taps guarded; leaving tab/closing invalidates
  callbacks and stops continuation. In-flight network requests cannot be undone.
- Bulk failures unlock retry without falsely marking everything locally; success updates local state.
- Per-item read success updates local state; repeated taps do not duplicate pending writes.
- Shared CursorPager's new on-demand mode is opt-in; other screens retain existing behavior.

Budget charges five notification opens and five older pages as cache misses: **200 list documents**,
plus a conservative **200 unknown-sender lookups**. One explicit mark-all of 40 unread rows adds
**61 reads** (40 rows + empty-tail allowance + refreshed head) and **40 writes** per user/day.
Cache hits lower query delivery but cache invalidation/eviction and more activity raise it.
Smaller mark-all batches bound request size; they do NOT eliminate the necessary total N row writes.
These new assumptions differ from the earlier model, so daily-total differences are not measured savings.

IMPORTANT: LIMIT bounds returned documents, not D1 scanned/index rows or engagement subqueries.
Cron scan counts, write amplification and other activity costs remain assumptions. No production
telemetry/device trace was collected; neither actual quota compliance nor a 99% reduction is proven.
See `home-on-demand-budget.json` and run `python3 -m scripts.loadtest.five_user_budget`.

## Verification
- Worker: `npm test`: 234/234 passed.
- Python: `python3 -m unittest discover -s scripts/tests`: 325 passed.
- Android debug Java compilation + JVM tests: **PASS**, notification commit `6b91a8d`:
  https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45109014981
  New behavioral tests cover empty cache, forced refresh, invalidation and eviction.
  No device-level scrolling/visual test claimed.
