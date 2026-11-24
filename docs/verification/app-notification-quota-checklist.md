# Notifications source / automated quota checklist

Verified code revision **c0bd589**. Android compile + full JVM workflow **45335619816 SUCCESS**. Python suite **460 passed**; `git diff --check` clean. No APK, release, merge, backend deployment or owner download/trace.

## Reviewed/fixed paths

| Path | Contract / changes |
| --- | --- |
| List entry / lifecycle | Ten-row cursor pages, 60-second head reuse, no snapshot listener or polling. Duplicate lifecycle entry does not replace the active pager. Pull refresh joins a pending load; explicit refresh has a one-second action guard. |
| Hide / stop / view destruction | Pager closes and page/bulk generations invalidate. Started/visible/actor/view ownership checks protect delivery. Destroyed-view references are released. Account changes clear old models and pending/failed/confirmed read identities before new page work. |
| Pagination / filtered results | Ten-row cached first paint and correct ten-row controls. Only explicit-more or genuine user-scroll boundary demand requests another page; no automatic filtered/empty-page drain. CursorPager's existing single-flight, generation, timeout and cursor-on-failure guards remain in use. |
| Cosmetic badge / profile N+1 | Notification bind uses embedded sender name/photo and cached TickCache.peek only. It no longer calls TickCache.attach, which could fetch one user document per unknown sender. Uncached cosmetic verification badges remain unknown until another genuine profile demand populates cache. |
| Individual mark-read | One pending write per ID, remembered successful IDs across row replacement/cached paints, no optimistic success on failure. Failed IDs do not generate writes on every tap; explicit full refresh permits another demanded attempt. Old-account callbacks cannot mutate new-account state; hidden callbacks do not repaint UI. Navigation has a one-second per-notification guard. |
| Explicit mark-all-read | Only the explicit button invokes the operation. Bounded ten-row keyset batches, one active action, pending-write guard, no writes from a bulk-read response arriving after hide/stop. A stalled read only unlocks UI, never retries by timer. Success updates local/cache read state and clears the badge without immediately re-reading the notification list. The explicitly requested bulk operation may span multiple pages; it is not capped at ten total rows. |
| Badge bootstrap | Notification badge bootstrap reduced from 32 to ten rows, 60-second cache window, account/destroyed-activity delivery guards. Bell dismissal is local and does not bulk-write all notifications. A bounded badge sample is not an exact total-unread count. |
| Push / invalidation | Existing push coalescing and cache invalidation retained; no hidden notification prefetch or new periodic task. Badge push updates remain local rather than querying the table for each pill repaint. |
| Stable outgoing event replay | Atomic server create and success-only push retained. Bounded actor/event ACK cache (512) suppresses repeated creates after success or ALREADY_EXISTS. Pending events share one write. A bounded failure timestamp cache (256) prevents repeated callbacks from retrying within two seconds; a later demand can retry, with no scheduled network request. Different saved comment IDs/resources/accounts remain distinct. |

## Behavioral evidence

`NotificationDemandTest` exercises:
- 100 pull-refresh demands -> one pending limit-ten server request.
- 100 explicit mark-all demands -> one limit-ten read; a hidden response starts zero batches.
- 100 pending taps plus 100 failed-result taps -> one mark-read write, without falsely marking read.
- 100 notification binds -> no database/profile request for cosmetic badges.

`CommentNotificationReplayTest` now drains callbacks between 100 replays, verifying ACK reuse rather than merely pending-flight sharing. It also verifies ALREADY_EXISTS -> one create/no push, failure cooldown and demanded retry, distinct saved-comment identity, and account-switch push suppression. Existing pagination/cache/listener/push regression tests run in the full JVM suite. Python notification guards cover lifecycle wiring, ten-row controls, badge TTL, no bind-time profile fetch and no post-bulk list reload.

## Verification boundaries

Source review and controlled JVM tests do not measure installed-device traffic, backend scans, billed reads/writes, or full-day quota. Ten returned rows do not necessarily equal ten billed database reads. ACK replay suppression is process-local and bounded; server create remains authoritative after restart/eviction. These fixes do not establish that the reported rapid production quota consumption is 100% resolved. No diagnostic APK or further owner trace is requested.
