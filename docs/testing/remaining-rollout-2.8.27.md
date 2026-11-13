# v2.8.27 / 59 — remaining-list rollout

## Scope agreed
Preserve existing users and their records. No automatic migration, reset, bulk deletion or legacy-field pruning. Existing explicit user/admin delete and privacy actions remain available and now use bounded cursor batches; this implementation did NOT invoke them against production.

## Additional changes
- Saved posts15, Page posts15; Groups20 with separate discover/member/invite queries and independent cursors.
- Friends/incoming/sent/people20; New Chat people/friends20; birthdays20; feed-preference friends20. First-page memory/SDK cache, refresh/retry/more controls and lifecycle close through PagedLists/CursorPager.
- Archive stories20/posts15. The trash button explicitly deletes only **loaded** items, not a falsely claimed entire recycle bin.
- Story viewer20 with continuation at the end, direct document lookup for linked stories, and request guards. Default document-ID ordering means the sequence is not guaranteed globally chronological.
- Friend tagging and Restricted choices now use a20-friend paged picker. Unloaded selections are preserved. Profile unfriend targets only the two participants rather than downloading unrelated friendships; duplicate historical relation cleanup still needs review.
- Referral/subscriber/account-summary counts use exact server count aggregation, not full member-list downloads. These refresh on screen entry, not continuous membership listeners; aggregation is NOT a record-list get and must not have a truncating limit.
- Explicit post cleanup/cancel/past-privacy actions use50-record server-only cursor batches with account checks and failure propagation. No automatic startup action/migration is added.
- Poke rate checking uses50-record legacy-compatible batches and a deterministic per-pair transaction to prevent concurrent taps bypassing the24-hour gate. No old poke records are deleted by migration.

## NOT finished / do not certify all-app completion
The conservative direct-chain audit is down from36 to4 candidate collection queries: Home privacy friend graph, Main unread notifications, Main unread-room/call metadata, Main pending-request badge state. These need a verified bounded membership/per-user-summary replacement, not a blind cap that loses privacy decisions/counts/calls. The inventory is heuristic; variable-built queries and other bounded-but-nonpaginated auxiliary lists still need tracing/device review. This does not certify zero unbounded queries, Facebook latency or5000 concurrent users. Waiving old-data migration does not solve future growth of these four paths.

Prior2.8.26 caveats still apply: legacy parent arrays/maps remain, some pages use document-ID rather than globally newest ordering, profile native Reels aren't unified with video posts, reciprocal arrays may be inconsistent, and search isn't indexed full text. One-shot list pages require refresh for remote changes; SDK/device testing remains necessary for lifecycle, story advancement, mutations, pagination anchoring and receipts.

## Validation
Python regression/source guards, Java syntax parser, backend mocked providers, existing release Java tests and synthetic5000-session page model. Signed CI result must be checked separately. No real Firebase/device traffic load test, production migration/deletion, paid infrastructure or capacity certification.
