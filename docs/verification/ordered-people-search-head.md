# Ordered people-search heads

Source **e631beaffe221c015a250adef6a9fc66fa11f370**; Android compile/JVM **45290514044 SUCCESS**.

The Android people search used displayName inequality filters without ordering. That selects the server's indexed-but-complete-prefix candidate path, not its existing bounded ordered-name first-head path. Both original/capitalized spellings now use orderBy(displayName ASC), startAt(prefix), endAt(prefix + U+F8FF). Results within each spelling are now alphabetical rather than default document-ID order. Same-query reuse, account scope, request-generation checks and explicit pagination remain. Prefix/filter membership is preserved; later cursors use ordered values plus ID tie breakers. Server privacy projection and Unicode/ambiguous-page compatibility behavior are unchanged.

New Worker request test seeds 2,000 matching canonical profiles, verifies the first ten ordered IDs, verifies only ten matching rows were materialized by the name-index SELECT, and checks indexed SEARCH in EXPLAIN QUERY PLAN. This does **not** measure SQLite/D1 rows scanned or billed reads; name ties can require additional sorting work. Existing prefix tests cover Unicode, typed/malformed historical names, profile visibility and cursor bounds. Non-ASCII/short/ambiguous pages and later pagination may still use the complete compatibility candidate path.

Validation: **426 Python / 294 Worker passed**, 48-workload replay completed successfully, diff check clean. Android run above includes cumulative prior point/query/delta/engagement regression tests, not only this patch.

Review of other post-list surfaces deliberately did not enable engagement bundling blindly: Search/Memories/Watch/Saved perform client-side filtering and could precompute summaries for undisplayed rows; GroupDetail currently has a 120-row live/moderation list, exceeding the existing 20-row bundle bound. Replacing live group behavior or restricting results without a pagination/moderation design would not preserve features.

Still the same four target families. Identified duplicate mechanisms have source fixes, but no claim that all traffic is redundant or eliminated. Corrected-client installed telemetry and representative-day <=100k reads/<=5k writes remain unverified. Packaging, deployment and merge remain paused.
