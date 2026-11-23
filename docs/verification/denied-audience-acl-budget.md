# Denied audience ACL dependency budget

## Proven source issue

`authorizePost` loaded the canonical post, rejected archived/trashed rows, then loaded viewer/author profiles and group authority before rejecting a non-owner's owner-only, unknown or malformed audience. Those dependencies cannot make that audience readable.

The non-owner audience rejection now happens immediately after the fresh canonical post read. The original audience interpretation is preserved: missing/empty strings default to Public; Public/Friends are case-insensitive; non-string values and other audiences deny non-owners; owners retain existing compatibility behavior. Public/Friends paths still perform all existing profile, block, group trust/membership/approval and trusted friendship checks. Owner group-ban/trust checks remain. No data is cached across requests and post authorization still independently reads the canonical row rather than trusting a client document or stale mirror.

## Regression proof

`feed-acl-request-budget.test.js` now covers twenty distinct authors on denied posts. The pre-change module was executed against the new tests using an isolated data-URL import of `git show HEAD:cloudflare/firebase-post-access.js` before committing: the budget test failed with **42 document lookups rather than 20**. This is 20 post reads plus viewer, twenty author profiles and group document; group authority is an additional statement. The optimized test passes with only the 20 canonical post reads and no group authority or friendship statement. Eight denied audience representations are tested. Existing/new tests also cover allowed audiences, owner compatibility, group bans, new-request revocation, errors and retry.

These are local prepared-statement/fixture counts, not billed D1 rows or measured production savings. Owner-only candidate prevalence in production is unknown. This does not attribute the external burst report to an app-open event or prove daily quota acceptance.

## Validation

- Full Worker suite: 292 passed.
- Python suite: 415 passed.
- Distinct-author targeted regression after final fixture update: 5 passed.
- Schema/migration/index checks: 2/3/4 passed.
- Real Worker/local SQLite replay: 100 users, concurrency 5, 48 workloads passed.
- Diff whitespace check passed.

Code-only deployment requested with the accompanying marker commit. Rollout outcome must be recorded separately. No Android change, packaging or PR merge in this patch.

## Verified rollout and additional equivalence check

Code-only rollout [45215521957](https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45215521957) succeeded for `e0d6484c1f47bc5f7895f6f5b8f4c2a0bd9279ae`. Full regressions, production dry-run, resource/schedule preservation and health/auth smoke checks passed. No D1 migrations ran.

A supplementary local mock differential experiment compared the pre-change and new authorization functions over **1,848 combinations**: eleven audience representations, self/non-owner, seven profile presence/ban/block modes, six group modes and friendship present/absent. All returned decisions matched. This is supplementary local evidence, not a production race/traffic test or an app-wide security proof. Existing independent canonical post authorization reads were deliberately retained.
