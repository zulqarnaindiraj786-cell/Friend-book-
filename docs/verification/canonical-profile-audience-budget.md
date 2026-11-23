# Canonical-only profile audience aggregate

## Confirmed source issue

The opt-in profile audience aggregate still included a branch reading `friendbook_cf_documents` friend requests with a canonical anti-shadow lookup for each matching candidate. This could count a mirror-only pending request that is not a readable canonical root document. It also retained obsolete legacy read work despite canonical-only root reads elsewhere.

`profileAudience` now aggregates only: supplied followers, trusted friendships and canonical pending incoming friend requests. DISTINCT UNION behavior still counts each person once and excludes the profile itself and empty/non-string values. Canonical cancellations/deletions cannot be undone by a stale mirror. Mirror-only pending requests intentionally no longer increase the cosmetic audience count, in accordance with the canonical-only requirement. No authorization rules, stored records, indexes or schemas were changed.

## Local cost and correctness evidence

- Updated existing test: followers/friendships/pending senders deduplicate; canonical accepted rows shadow old pending copies; mirror-only pending sender is ignored; deletion removes a pending sender.
- New regression: 5,000 unrelated canonical requests, one matching pending request, no legacy table at all. Aggregate succeeds and EXPLAIN QUERY PLAN seeks the recipient/status expression index with the shape/name from migration 0049. Cancellation updates the result correctly.
- Supplementary before/after experiment: execute pre-change module from commit `b1e8d21` against new module in SQLite. Create both document tables with primary keys and install **all migration 0049 indexes**. Seed 5,000 legacy pending requests to bob, each shadowed by a canonical accepted request. Instrument the old anti-shadow predicate with `legacy_audience_probe(a.path)`. Both versions return audience zero; old branch visits **5,000** legacy candidates, new branch visits **zero**.

The old query was not universally unindexed: matching legacy expression indexes exist. The proven improvement removes the branch and its per-candidate anti-shadow work, not a claimed missing-index fix. Local row visits are not D1 billed rows, production frequency or measured daily savings.

## Validation

- Worker **293 passed**.
- Python **417 passed**.
- Schema/migration/index checks **2/3/4 passed**.
- Actual Worker/local SQLite replay **100 users, concurrency 5, 48 workloads passed**.
- Diff whitespace check passed.

A code-only rollout is requested with the accompanying marker commit; verify its outcome separately. No Android packaging/merge, schema migration, account/ACL cache, cron change or data deletion. Completed-day quota target remains unverified.

## Verified production rollout

Code-only run [45220396473](https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45220396473) completed **success** for `d9e685af33ebfe69090b50576eb2d0199d12e631`. Full regressions, production dry-run, code-only deployment, resource/schedule preservation and health/auth smoke checks passed. No D1 migrations ran. This server-side change is deployed without a new Android package; actual profile-aggregate traffic and billed savings remain unmeasured.
