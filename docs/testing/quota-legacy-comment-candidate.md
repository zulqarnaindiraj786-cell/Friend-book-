# Historical comment page: indexed migration and verification

## Evidence and limits

The read-only Analytics sample in run 44977477454 classified the historical comment page SQL at 2,916 reads across 36 executions over five minutes. This is an adaptive aggregate, not a caller, post, or account-wide billing total; it does not establish the savings of this change.

Production aggregate-only preflight 44978284846 sampled all 422 root comments (cap 5,001, not truncated): 268 met the numeric plain-document safe-time predicate. The maximum exception count grouped across route fields was an upper bound of 12. D1 reported 3,109 rows read by the preflight; no records, IDs or user values were printed. This is a snapshot, not a forecast. The deployment repeats the capped preflight and refuses migration if the sample truncates or an exception group reaches 65. It is **not** a guarantee or estimate of billed migration writes or of the full database scan cost.

Production deployment 44978688476 passed its repeated preflight, applied 0076 and published the Worker; the deployment's read-only connectivity check passed. Worker validation 44978688554 and Android candidate checks 44978688550 succeeded; the Android signed-build job was skipped. This establishes deployment, not measured quota savings.

Read-only follow-up 44978798374 reported UTC-day adaptive analytics of 310,804 rows read and 18,864 rows written at its sampling time. Its latest five-minute sample was quiet, and the prior legacy scan appeared in the longer window, which overlaps deployment. The optimized shape was not clearly identified in the short window. Traffic, windows and sampling differ, so this is not a before/after saving or an account-wide billing total; continued observation under real usage is needed.

## Implementation

Migration 0076 creates three composite route/time/id indexes, then drops the corresponding old route-only index. It creates each replacement before dropping its predecessor to preserve lookup during partial application. The first key and partial predicate remain equivalent, and a grouped-count plan test confirms the old Worker count shape can use the replacement. New, distinct index names ensure a Worker on a pre-0076 database falls back, rather than mistaking old indexes for complete indexes. Missing indexes, invalid cursors, a 65-row exception bucket, unusual paths/IDs, or an incomplete result trigger the existing compatibility scan. Other database errors are not hidden. Six indexed statements are issued in one transactional D1 batch so independent route reads share a snapshot. Old/canonical comment merge and access control remain unchanged.

## Local checks

- Worker tests: 180 passed; schema/migration/plan: 9 passed; script tests: 271 passed.
- Synthetic tests compare both paging paths over 2,000+ records, plus 90 randomized forward/backward cursor comparisons; cases include Firestore envelopes, strings and missing times, equal and fractional timestamps, overlapping routes, blocked users, canonical shadows, deleted records and exception overflow. Tests check time-range and exception-bucket index seeks and actual missing-index fallback.
- Wrangler 4.157.0 local D1 accepted the 0076 SQL. A separate local Miniflare D1 test exercised the actual six-statement batch over 500 synthetic rows and confirmed real missing-index fallback. No remote test writes were made.
- Local real-Worker replay: 31,491 requests, 33 workloads and 57 captured query plans without errors. For 1,000 synthetic busy-comment pages (188 old comments, including 8 exceptions), forced compatibility scan returned 191,999 SQLite rows in 6,999 statements; indexed paging returned 32,999 rows in 11,999 statements. Both wrote zero rows. The indexed path adds five statements per request; SQL filters can still examine shadowed or blocked rows. Returned SQLite rows, statement counts and local plans are **not** billed D1 reads, and real outcomes depend on data and traffic.

## Rollback and monitoring

Worker rollback to the pre-0076 version can use the new indexes because their route expression is the leading key. If a rollback specifically requires the original index names, recreate the three indexes from `0032_legacy_comment_lookup.sql` before dropping `idx_cf_comment_page_post`, `idx_cf_comment_page_post_legacy`, and `idx_cf_comment_page_resource`. A new Worker without any replacement index falls back to the compatibility scan, at extra attempted-query overhead. Index builds and restoration both consume quota; avoid repeated toggles. If a migration fails or is partial, check D1 migration status and index definitions before retrying, and do not assume a Worker was deployed. Subsequent read-only Analytics samples should be compared as time-windowed patterns only; do not infer exact billed savings without equivalent traffic and account-wide quota data.

No synthetic production traffic, production write-verification probe, or new Android release was used for this change.
