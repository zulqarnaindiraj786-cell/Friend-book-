# Zero-visible comment preview read avoidance

## Proven source issue

The shared engagement summary used by Home and post details evaluated a two-comment preview even when its canonical visible count was zero. A resource can retain thousands of removed comments, or all its comments may be blocked for the current viewer. `LIMIT 2` alone does not bound the work of proving that no eligible row exists.

## Fix

Evaluate the existing visible-count expression once in a non-flattened one-row derived table (`LIMIT 1`). SQLite CASE short-circuits preview execution when that count is zero. Nonempty summaries retain the existing ordered preview SQL, ACL/block predicates and reaction hydration. Legacy merging remains outside this canonical summary, unchanged. Missing totals tables still take the existing compatible count path. No schema/index changes or writes are added.

## Reproducible local evidence

`cloudflare/test/interactions.test.js` seeds 3,000 canonical comments. It compares the same summary with unconditional preview evaluation to the guarded evaluation, instrumenting preview row visits with a SQLite function. Both removed-history and all-blocked cases visit at least 3,000 preview candidates unconditionally and zero when guarded, with identical DTOs. Unblocking returns 3,000 comments and two previews. Dropping the totals table retains the visible result through the compatibility path.

This proves avoided local preview traversal, not Cloudflare billed-row savings, production prevalence or completed-day quota acceptance. The count itself still performs its legitimate work, including blocked-author subtraction where needed.

## Validation

- Worker suite: 290 passed.
- Python scripts suite: 413 passed.
- Real Worker handler/local SQLite replay: 100 users, concurrency 5, 48 workloads passed.
- `git diff --check`: passed.

Code-only production rollout is requested in the accompanying marker commit. Its result must be checked separately; this document does not assert deployment success. No APK/AAB or PR merge.
