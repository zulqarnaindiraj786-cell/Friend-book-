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

Latest five-user scenario: **11,535 document reads / 1,495 row-write operations per day**.
See the inbox follow-up below for the additional explicitly charged actions.
If all 20 app opens miss cache: **28,555 estimated reads/day** under the script's other assumptions.

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

## Additional quota audit — 2026-11-19
Verified implementation commits: `ae3bed6`, `0a7b9bb`.

1. **Unseen Reel download**: first-frame callback used to download one next `.mp4` on
   unmetered Wi-Fi. Removed the speculative downloader and its call site entirely. Playback
   still uses the selected Reel's lightweight startup rendition; next-holder preparation can
   use only an already-complete local file. Existing local cache lookup, retry UI, selected
   autoplay and quality handling retained. This saves unnecessary media GET/bandwidth, not a
   demonstrated number of D1 rows. No claim that all selected-video streams now persist to disk.
2. **Duplicate Menu self-profile listener**: Menu had its own `users/{uid}` registration while
   the central account listener already delivered the same profile. Menu now observes the
   in-process shared profile only while visible, detaching on hide/stop. Name, photo, creator
   subtitle, account switcher data and verified tick still update without a second DB listener.
3. **Age-triggered profile registration churn**: `ensureMeFresh()` no longer recreates a healthy
   registration merely because 60 seconds elapsed. It still restarts for no registration,
   changed account or a failed listener. Central account listener, foreground refresh/invalidation,
   account creation and suspension/sign-out handling remain. Security handling runs before
   optional UI observers.

Verification for this follow-up:
- Worker `npm test`: **234/234 passed**.
- Python regression guards: **328 passed**.
- Android debug Java compilation + JVM tests: **PASS**, commit `0a7b9bb`:
  https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45109586711
- New JVM policy tests exercise 10,000 healthy resume decisions, registration absence,
  account switch, failure recovery and signed-out behavior. This is not a device load test.
- `git diff --check`: clean.

No invented numeric saving is subtracted from the earlier **11,000 / 1,495** scenario: it did
not separately model the removed Menu registration/churn or media GETs. That remains a scenario,
not proof of whole-app D1 usage or zero quota waste. Chat/call/account-security listeners and
legitimate explicit operations are intentionally retained. No APK/AAB produced; PR #36 unmerged.


## Inbox repeat-read and invalidation follow-up — 2026-11-19
Implementation: `f00bd80`.

- Ordinary chat-list opens and older pages reuse complete account/query/cursor caches, including
  empty results. Cache misses still fetch. The ten displayed rooms and one lookahead are retained.
- Explicit pull and visible-inbox message pushes bypass cache; pushes remain debounced, never polled.
- Cached first page restores its cursor; older pages can come from cache. Timestamp/revision checks
  and InboxMessageHandoff still protect the latest visible preview and unread state.
- Pending push is scheduled after cached completion too, preventing a push arriving during cache
  hydration from being stranded. Cached snapshots do not trigger incoming-call ringing; fresh
  call signaling paths and message receipt logic are unchanged.
- Found a correctness prerequisite: persistent query invalidation previously covered only groups
  and friends. Now all changed collections are marked stale in their correct account-scoped cache
  namespace, including the separate chat namespace. This writes local preferences only; it does
  not download a hidden list. A later open can fetch when the cache is stale.

Budget correction: current inbox code has no separate users-strip query. A cold page returns at
most **11 documents** (ten visible + one lookahead), not the previous 10+20 assumption. To avoid
claiming unmeasured savings, the updated model charges all ten inbox opens as cache misses,
plus 20 visible-push refreshes, two pulls, and five older pages: **407 documents/user/day**.
These added explicit actions raise the overall estimate from 11,000 to **11,535 reads/day** for
five users, despite removal of redundant reads. Writes remain **1,495/day**. A valid cache-hit
inbox open costs zero inbox-query documents; writes/push invalidation, eviction and user refreshes
can require a fetch. Neither total is measured D1 rows_read or a guaranteed production cap.

Proof:
- Worker tests: **234/234 passed**.
- Python checks: **331 passed**.
- Android debug Java compilation + JVM tests: **PASS**, commit `f00bd80`:
  https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45110057902
- New JVM policy tests verify chat/message cache namespace selection, invalidation, cache miss,
  timestamp rollback and expiry. Regression guards cover inbox push/pull, cursor restore, handoff,
  cached call-state safety and pending-push scheduling. No device-level test claimed.
- Merge remains on hold. No APK/AAB produced.

## Failed-count retry / lifecycle audit — 2026-11-19
Implementation commits: `d8d8d0d`, `169f127`.

- **Unbounded failed count requests**: CountReads previously retried indefinitely with backoff up
  to five minutes. It now allows three total attempts (initial + two retries after 1s and 2s),
  and only for transient transport/server errors. Permission/auth/quota exhaustion, cancelled,
  malformed/unsupported queries and other permanent errors do not retry. Exact server counts
  and the existing close/account-switch guards are retained. Reopening/explicit user action can
  initiate a new bounded operation; this is not a lifetime request limit.
- **Account summary detached from its owner**: Settings discarded the CountReads cancellation
  handle. It now stores/cancels it onStop, guards duplicate taps while loading, and unlocks retry
  with an error message after terminal failure. Closing a screen suppresses queued retries and
  late UI callbacks; an already-sent network request cannot be undone.
- **Deferred referral count could run offscreen**: Main's unowned four-second startup callback
  is now scheduled only while resumed, and both the pending callback and count registration
  are cancelled onPause. Once successfully loaded, the result is not re-requested on ordinary
  resumes of this MainActivity instance. Account changes reset that state. BlueTick's explicit
  count path and referral auto-grant logic remain intact.

Proof:
- Worker `npm test`: **234/234 passed** (Worker code unchanged).
- Python regression suite: **334 passed**.
- Android debug compilation + JVM tests: **PASS**, commit `169f127`:
  https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45110675372
- New JVM tests cover the attempt cap, 10,000 post-cap decisions, every D1 error code,
  transient I/O and non-retryable programming errors. Source guards verify owner cancellation,
  account guards, summary tap deduplication and referral session reuse. Not a device/outage test.
- No guessed daily savings applied: avoided reads depend on actual failures, lifecycle and the
  number of rows scanned by each aggregate. The prior **11,535 / 1,495** normal-activity scenario
  is unchanged and remains an estimate, not live quota telemetry. No whole-app zero-waste claim.
- PR #36 remains unmerged. No APK/AAB produced.

## Mention-search repeat reads / focus audit — 2026-11-19
Implementation: `9e090b3`; verification cleanup/config: `1423938`, `bbd22f4`.

- Repeated mention prefixes used to send the same bounded name queries again after typing and
  backspacing. Exact account-scoped query results, including empty pages, now reuse a five-minute
  cache window. New prefixes, expired/invalidated pages or cache eviction still query normally.
- Existing distinct case variants, ten-result per-query cap, selected UID identity, bulk mention
  choices, reply tagging and the 250ms debounce are preserved; no lower-result fallback substituted.
- Queued network work checks actor, attachment, window focus and input focus before dispatch.
  Completion checks those conditions again, and selection verifies the original actor. Existing
  detach cancellation remains. Already-dispatched requests cannot be cancelled by these guards.
- Query cache helper now accepts an optional maximum age. Existing list callers retain their
  previous reuse window; only mention searches opt into the five-minute window.

Proof:
- Worker tests: **234/234 passed**.
- Python suite: **337 passed**.
- Android compilation + JVM tests: **PASS**, commit `bbd22f4`:
  https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45111512077
- New behavioral JVM tests cover 100 repeated cache hits (zero fake-network calls), the supplied
  search TTL, stale results, explicit refresh and zero-window bypass. Regression guards cover
  focused-input dispatch, actor checks, debounce and retained case variants. Not a device benchmark.
- Two earlier Android runs failed while Robolectric was fetching Maven artifacts (IOException /
  MavenArtifactFetcher), not an observed app assertion. Failed-job rerun was unavailable. The test
  workflow now passes Maven Central's canonical `repo.maven.apache.org` endpoint to its JVMs,
  consistent with the existing release workflow. No test was skipped or downgraded to get green.

Mention-search activity is not separately modelled in the previous 11,535 / 1,495 daily scenario;
no invented daily saving is subtracted. Cache hits avoid repeat query delivery, not proof of actual
D1 scanned-row usage. No whole-app zero-waste/99% claim. PR #36 unmerged; no APK/AAB produced.
