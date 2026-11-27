# Production comment-count read hotspot and indexed candidate fix

## Observed production evidence

The latest available read-only Analytics Engine report run `45727540808` reported a six-hour primary-database aggregate of **1,229,929 rowsRead** and **11,745 rowsWritten**. This is an adaptive SQL-shape sample, not the account billing dashboard total or user attribution.

Its highest-ranked read shapes were both classified `application_or_unattributed` and both carried `comment`, `historical_comment_page`, `legacy_comment_candidate_probe`, JSON-field predicate and count/anti-join features:

- **707,724 rowsRead / 27 executions** (about 26.2k rows per execution).
- **348,105 rowsRead / 594 executions** (about 586 rows per execution).

Their combined observed subtotal is 1,055,829 rowsRead. The 360-minute shape list was marked potentially truncated; do not treat this subtotal as a complete billing or traffic total. The report cannot identify an app screen, individual user, or the exact request caller.

## Source cause and correction

`batchEngagementReads()` calls `legacyCommentCountsWithCandidatesStatement()` for Home/feed engagement pages. The previous candidate CTE cross-joined requested posts with the complete canonical `comments` partition, then applied three JSON route predicates using `OR`. On the production-scale comment partition, this turned a small batch of requested post IDs into a large unrelated-comments scan on every candidate probe.

The CTE now performs three separate exact route-key probes using the existing canonical expression indexes (`postId`, legacy `post_id`, and `resource`) and `CROSS JOIN` loop order to keep the requested resources outermost. `UNION` still de-duplicates documents carrying multiple key spellings. The exact legacy validity, deletion, block and canonical-shadow filters remain, and only verified empty candidate sets are still eligible for epoch-scoped negative hints.

The regression now applies migration `0087` and checks the actual SQLite query plan for all three named indexes, with no parent-partition index scan. It also checks exact counts across plain/wrapped fields, duplicate route keys, blocked/deleted rows, canonical shadows and malformed values.

## Validation and limits

`node --test cloudflare/test/legacy-comment-epoch-demand.test.js`: **7/7 PASS**. Full Worker suite: **342/342 PASS**. `git diff --check`: PASS. These local SQLite plans/tests validate code and semantics, not Cloudflare D1 billed-row savings. The Worker source fix must deploy before it affects production. A comparable post-deploy Analytics Engine window is needed to quantify the actual reduction; no per-user count or guaranteed percentage saving is inferred.
