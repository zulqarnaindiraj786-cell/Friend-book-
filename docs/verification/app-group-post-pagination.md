# Source-only replacement of the Group 120-post read

Owner requested continuing fixes without more APK downloads. No new APK/build/release/deployment or Supabase use in this pass; changes remain on unmerged PR36.

## Changes

- Removed GroupDetailActivity's 120-post snapshot read.
- Discussion uses a 10-item cursor pager with 120s exact-head reuse and cached-first rendering. Footer offers manual next/retry; one pending page blocks subsequent requests. Filtered/empty display pages do not auto-drain the collection. Prefetch disabled.
- Group document is still observed for membership/settings. Post pages start only after group metadata permits the current actor to view them; inaccessible private groups do not initiate a post page. Backend authorization remains authoritative.
- Feed pages request bundled engagement and seed shared summary before rendering, so the adapter does not issue a summary GET per row.
- Pending approvals use a separate lazy groupId+pending query with independent 10-item cursor pages; they are not truncated to whichever posts happen to be visible in Discussion.
- Pin/remove management uses its own lazy groupId cursor pages; archived/trashed/pending/declined rows are filtered in UI without automatic scanning of further pages.
- A pinned post outside the first ten is loaded by its explicit ID, with generation/account/current-pin/group/status guards. Its summary is loaded once by the parent through existing shared demand/caching, never by a row bind. This adds an explicit pin read when a pin exists; do not equate page size10 with total10 billed reads.
- Manage menu offers Browse instead of mislabelling a loaded page's count as the total moderation queue.
- Resolved moderation rows remain suppressed as later pages repaint. Write acknowledgements still precede success/notification; after a real approval/deletion the visible feed can refresh. Stopped screens do not start that refresh.
- Activity stop closes group/document/page registrations, dismisses owned moderation dialogs, closes their pagers and invalidates pin callbacks. Permission revocation clears visible feed and owned moderation dialogs.

## Tests and limits

- GroupPageDemandTest exercises the actual group page startup with mocked query transport: 100 repeat entry/load-more demands while initial request pending yield one DEFAULT query with limit10, not limit120; a closed pager rejects its late result.
- Source guards check independent pending/management cursors, explicit pin safety checks and lifecycle ownership. These guards do not replace a physical-device moderation/pinning interaction test.
- Existing CursorPager, actor/account, mutation acknowledgement, canonical backend/privacy and engagement tests remain intact.
- Bounded requests/items do not prove bounded billed SQL rows; groupId/createdAt/pending query plans and real row metadata still require attribution. No production savings or full-app100% claim.
- Installed APK180/181 do not contain these later source changes. No new download requested.

## Final verification

- Production patch `65ffc3f`, pin/display refinement `4128bd0`, compile correction `f7c7061`; demand/source tests `ca4ee65`.
- 449 Python tests PASS; whitespace checks clean.
- Android production compile and full JVM/demand suite SUCCESS: https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45330373151 at `f7c7061cb55bcd899fb8b18be5a1908a953e3875`.
- Intermediate run45330191137 caught Java nested-lambda error-name shadowing; fixed by distinct pageError parameter, without removing tests or relaxing CI.
- Package workflow skipped under source-only/no-release policy. No installed-client quota result claimed.
