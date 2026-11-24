# Feed source and automated acceptance checklist

Scope: Home Feed demand, lifecycle, adapter hydration, pagination, reactions/comments/shares, account/cache ownership and bounded failure paths. This is a source/automated verification record, not an installed-device or production quota certification.

Verified code/test revision: f0b194e. Android compile + full JVM workflow **45333217623: SUCCESS**. Python suite **451 passed**; `git diff --check` clean.

## Reviewed paths and regression coverage

| Checklist | Result and evidence |
| --- | --- |
| First load and warm Home return | Cache-first head, 120-second reuse; FeedHeadReadsTest covers 100 warm entries without a network fetch, stale revalidation once, explicit refresh, inactive ownership. |
| Background/hide/destroy | Pending session generations invalidated, loading flags released; HomeLateReadDemandTest covers late section callback and interrupted initial sections; hidden pagination cannot request work. |
| Refresh single-flight | Pending page blocks further refresh gestures; HomeRefreshDemandTest covers 100 gestures without new work. |
| Ten-post pagination | Exact query plus authoritative cursor, limit 10, one active load; HomePageDemandTest covers 100 concurrent demands, offline result retaining cursor for explicit retry, and authoritative empty page stopping without further queries. No recursive filtered-page drain; scroll admission requires user scroll and no page error. |
| Cache/tail boundaries | HomeFeedTailReuseTest covers 100 cached-head applications preserving a twenty-post session/cursor; confirmed-empty authoritative feed clears saved models. Filters repaint the loaded session without resetting the cursor or launching another head read. |
| Edited/deleted head rows | Stale Feed head now revalidates one bounded full head, rather than merging a new-ID-only delta that cannot observe edits/deletions/privacy changes. Query's delta API remains unchanged for other callers. |
| Missing-author hydration | Submitted-page batch queries of up to ten missing author IDs; no bind-time author GET, no retry from repeated missing/failed result. FeedAuthorBatchDemandTest covers repeated submissions and detached callback rejection. Explicit refresh permits new attempts. |
| Reaction observer feedback | Home observer invokes payload-only adapter update, not head reload. FeedReactionFeedbackTest covers 100 updates: stable order, zero full-list notifications and zero database/profile reads. Home uses bundled page engagement instead of per-card summary fetch. |
| Comments | Feed inline comments use embedded preview, not bind-time comment query. Comment entry navigates to detail without a Feed read (behavioral test). Existing comment ACK path updates cached preview without renewing reaction freshness. |
| Shares | Pending actor/post share coalesced, one-second cooldown, atomic new-post + counter batch. Notification/success feedback only after commit; no direct Feed reload. FeedShareDemandTest covers 100 pending submissions, failed commit, successful count/notification and old-actor success. |
| Account switch | Old cursor, models, section state and visible adapter data cleared before new reads; HomeAccountScopeTest. Async actor/session guards retain ownership. |
| Repeated safety writes | FeedSafetyDemandTest covers 100 repeated flagged cache paints, once-per-process actor/document remote actions and no old-actor follow-on delete. Pagination now also applies the text safety gate used by the head. |
| Retry/polling | Home failures show explicit retry, no recursive read. One-shot 20-second UI unlock is not a polling request. Existing ReactionInvalidationLoopTest bounds conflict recovery to three attempts with two-second spacing; reaction failure expiry clears a local guard without issuing a timed GET. |

## Deliberate boundaries

- No APK, release, merge or backend deployment is part of this verification.
- A page limit of ten is not a claim of ten billed database reads. Backend query scans, indexes, engagement and other app features affect billing.
- Production quota savings, reported sub-minute rapid consumption, and full-day quota targets remain unmeasured. No new owner download/trace is requested.
- Moderation action deduplication is process-local, not durable exactly-once execution. Failed actions do not automatically retry.
- Unknown future defects and behavior on an installed device cannot be certified as 100% eliminated by source review/JVM tests.
