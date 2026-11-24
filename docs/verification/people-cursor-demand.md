# Default people picker cursor seek

## Confirmed code path and fix

The new-chat people list, create-group people list, group-chat member picker and admin moderator picker use `PagedLists` / `CursorPager` over `users` with no explicit order. Android serializes a snapshot cursor in that case as `startAfter: [documentId]`. The Worker already used the existing canonical `idx_cf_people_default_page` index for their first page, but rejected every cursor, so a next-page query fell back to loading the complete canonical users collection before applying its ID boundary and privacy projection.

The Worker now seeks `doc_id > ?` for a single safe ASCII ID boundary and reads bounded indexed candidate batches. Profile projection and visibility are evaluated fresh **before** filling each page; a deleted cursor remains a value boundary rather than a required live document. Hidden rows can cause up to four bounded refills; an incomplete proof, noncanonical path, unusual ID or unsupported cursor goes through the complete previous evaluator. Admin/moderator projection is unchanged. The fast path has no new table, index, migration, writes or cross-request authorization cache. It applies only to unfiltered, default-order user queries with the supported cursor shape; other pages are unchanged.

## Correctness and limits

An actual Worker request test uses 1,500 synthetic profiles and compares each page to the complete generic evaluator: ordinary and deleted anchors, private fields, banned users, live unban, admin visibility, exhausted pages, an oversized hidden-user run, Unicode IDs, noncanonical paths and object cursors. SQLite `EXPLAIN QUERY PLAN` confirms an index range search. One all-visible ten-item page returned 11 candidate rows including lookahead rather than scanning the fixture's entire collection; a hidden-row refill returned 28. These are local result counts, not Cloudflare billed reads or proof of production traffic frequency. The independent index fallback and small/empty last pages retain exact behavior.

Validation: local Worker 322/322, Python 518/518, schema/migration/index-plan suites passed. Source CI and deployment are still required; this change alone cannot certify app-wide quota or a representative-day target. No APK was built.
