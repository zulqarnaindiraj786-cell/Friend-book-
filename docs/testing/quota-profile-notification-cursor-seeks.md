# Profile and notification deep-page read scans

Two real Worker cursor queries were still walking newer rows before reaching the requested older page, despite existing indexes. No live sample established how much of the reported five-user spike these specific queries caused.

- **Profile posts:** the ordinary `authorId` + descending `createdAt` page now seeks the existing canonical and legacy author-prefixed indexes by author, timestamp rank, time and ID. Timestamp cursors also fetch the bounded numeric-rank window. Exceptions, shadowing, ACL, author filter and page size remain unchanged. If either index is unavailable, the prior bounded query remains the fallback.
- **Notifications:** older timestamp pages now seek the existing recipient-scoped page/unread indexes and merge a bounded numeric-rank window. Numeric seeks and newer/delta pages retain their semantics. A missing index uses the existing unhinted fallback; unrelated storage errors fail the request. Exception and legacy compatibility logic remains intact.

No migration or new index is required. No per-isolate authorization cache is introduced, and the old canonical/legacy and block/recipient protections are still applied. Both changes work with existing Android clients.

## Local D1 metadata, actual Worker, synthetic data

Using 800 synthetic records (mixed mirrors, privacy and authors) and identical response IDs, local Miniflare measured profile page reads: first **24 to 24**, middle timestamp **335 to 55**, older numeric **942 to 24**, and rank boundary **564 to 44**; all were zero writes and 14 SQL statements. For notification pages with 800 synthetic notifications and mixed mirrors: first **16 to 16**, middle timestamp **430 to 47**, numeric **17 to 17**, and rank boundary **738 to 39**, all zero writes and six statements. These counts are local D1 metadata, not billed production usage. The extra numeric window may read more on some timestamp pages; real savings depend on page depth, data, privacy and use. The earlier privacy and notification behavior is not traded for a global cache or missing checks.

Tests compare result documents or IDs against the full evaluator over numeric/timestamp ranks, ties, various page sizes and cursors, unread filters, other recipients/authors, changed canonical ownership/privacy, legacy-only/wrapped data, and exceptional timestamps. They assert that index plans constrain rank/time and exercise absent-index fallback and unrelated storage errors. Local validation passed 223 Worker tests, nine schema/migration/index tests, 293 Python script tests, and an isolated network-disabled Worker replay (8,688 requests, 69 query plans, zero plan errors). The replay uses serialized local SQLite commits and does not prove production billing, handset behavior or all concurrency cases. No synthetic production traffic or database migrations were used for these comparisons.

This reduces excessive reads in two specific deep-page paths; it does not reduce every request, reduce writes, or prove that the overall quota complaint is solved. Finite daily quotas cannot guarantee unlimited use.
