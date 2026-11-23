# Search head read budget

Source: eaf64a00bcca17c3baf664a43f0cf799358d928c.

## Root cause and bounded change

SearchActivity's ordinary text searches fetch the same recent posts query and apply `hay.contains(needle)` locally. Each replacement pager previously called `start()`, allowing another network read of that identical first page for every executed term. People prefixes also repeated reads on revisiting a term.

Search now debounces input for 350 ms (formerly 120 ms) and uses the existing account-scoped exact-query persistent cache for up to 30 seconds on its three head pagers. Hashtag filters and people prefixes remain separate serialized queries. No background timer, query-result-to-full-document promotion, relaxed authorization, or indefinite/day-long search cache was introduced. Cursor pagination is retained. Existing collection invalidation/freshness checks remain authoritative; explicit full refresh is unchanged. Remote changes without invalidation can remain stale within this short cache window, as with other client caches; this is not a fresh authorization check on every cached paint.

This avoids repeated list processing and any associated server ACL lookups on cache hits. It does not prove standalone point-read or engagement traffic reductions, and does not claim all four consumer families are solved.

## Verification

- Android compile/JVM workflow **45287544068 SUCCESS**, exact source above.
- New Robolectric tests exercise 100 sequential search pagers sharing one exact head: one cold SERVER request; simulated expiry/invalidation requires another read. Explicit full refresh still requests SERVER even with fresh cache.
- These mocks prove pager request selection, not production cache hit frequency or billed-row savings.
- Python **421 passed**; Worker **293 passed**; quota replay completed successfully (48 workloads); diff whitespace check passed.
- No APK/AAB, Worker deployment, migration, or merge requested/performed for this change. Installed-client and representative-day quota acceptance remain unverified.
