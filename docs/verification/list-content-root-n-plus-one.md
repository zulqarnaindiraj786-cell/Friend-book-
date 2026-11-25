# List-to-content-root authorization N+1

Source **e10287b**. Same four quota target families; this patch targets the point-read dependencies of posts/reels/stories lists, not a new consumer.

## Root cause and fix

Canonical list SQL already loaded the complete stored content rows, but visibleDocuments/authorizePost fetched each root again to obtain ACL fields. The earlier document-GET correction did not remove this list N+1.

- Bounded posts/reels feeds (including exceptional-timestamp rows) now carry internal Symbol-keyed Firestore ACL fields taken directly from their unprojected canonical SQL rows.
- Generic query and count content roots use the same helper before any profile/view projection. Exact root path/parent/ID matching is required. Nested resources and non-content roots are not seeded.
- Legacy `/v1/docs` lists retain a request-local map only for storage rows explicitly marked canonical by their SQL SELECT and matching the exact root path. ACL uses that raw snapshot rather than the projected response.
- One redundant second canonical SELECT in the older non-forwarded PUT path was also removed: the first identical canonical SELECT already supplies existingRow.

This extends the internal canonical-fields authorization argument from the previous GET fix. It never trusts a client-supplied snapshot and introduces no result TTL or cross-request post/author ACL cache. Root authorization and response now use the same content observation; viewer, author, block/ban, group owner/membership and trusted friendship checks remain. Concurrent changes after a row is observed do not retroactively change that snapshot; this does not claim an atomic D1 snapshot across all dependency reads.

## Proof

- **295 Worker tests passed.** Existing canonical feed/reference tests now assert **zero extra content-root point SELECTs** for both indexed bounded and generic reference paths across posts/reels, 9 cursor variants and 4 limits, retaining result parity (privacy, ties, unusual timestamps, canonical precedence, mirror-only exclusion).
- Those tests additionally modify the author between requests to blocked, banned and deactivated states; both paths return no posts/reels after each change, demonstrating fresh author-dependency reads rather than a persistent ACL cache.
- Existing legacy-list test still returns only the ten public rows of twenty loaded roots, now with **0 repeated content-root ACL SELECTs instead of the previous asserted 20**. A changed author block on the next request still returns none.
- **428 Python tests passed**, including new snapshot-origin/wiring guards.
- **48-workload quota replay completed successfully** after the implementation; diff whitespace check passed.
- No Android files changed; latest cumulative Android compile/JVM success remains 45290514044. No new APK/AAB, rollout, migration or merge performed.

These are local source/query-count and authorization results, not billed-row measurements or production request-frequency evidence. Profile/user dependencies and legitimate engagement/list reads remain. The actual share of the latest 6,769 point reads attributable to these list paths is unknown; do not claim that all 6,769 are eliminated. Representative-day quota acceptance remains unverified.
