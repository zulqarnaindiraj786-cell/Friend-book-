# Queued query duplication across the four read-consumer families

## Confirmed source mechanism

`Query.get(DEFAULT)` previously scheduled a separate executor job for every call. The transport-level `ReadCoalescer` only shared overlapping I/O. Identical requests waiting in the client queue could therefore execute sequentially after an earlier flight completed. For a posts page this repeats its list query, request account/post ACL dependencies and optional engagement reads; users pages repeat their list work/account check.

The existing document-level flight fix did not cover Query. Also, `getCachedOrFetch(false)` escalated cold/evicted pages to SERVER even though the caller did not explicitly request independent fresh work.

## Fix

- Add store-owned pending query flights, using the tested generic active-read helper.
- Key by authenticated actor and the exact existing serialized query-cache key (collection, filters, ordering, limits, cursor, post tag and feed-engagement option).
- DEFAULT shares the entire queued operation. CACHE stays local and explicit SERVER/forced refresh stays independent.
- Non-forced cold/evicted cache fallback uses DEFAULT, still a network read, not stale-result caching.
- Any document mutation invalidation or collection push/commit invalidation detaches all pending query flights. New requests cannot join a pre-invalidation flight; old consumers still finish normally. Conditional removal prevents old completion from evicting a replacement.
- No new result TTL, endpoint, SQL/index change, authorization shortcut or dropped rows.

Partial/enriched feed rows were deliberately not copied into the full-document cache: omitted reaction/comment fields and profile projection variants make blanket seeding unsafe.

## Which of the four consumers this addresses

| Family | Avoided work when the same DEFAULT page is concurrently requested | What remains |
|---|---|---|
| Indexed point lookups | Repeated request account and per-post ACL reads for duplicate pages | Distinct required document reads, standalone callers and actual production duplicate frequency |
| Engagement summary | Duplicate page enrichment for an identical Home query | Standalone detail/Reels summaries and genuine refresh |
| Posts list | Redundant identical queued page operations | Genuine pagination/explicit refresh and ACL-filtered refill |
| Users list | Redundant identical queued page operations | Distinct searches/pages/explicit refresh |

This does **not** establish that all four production totals are unnecessary or eliminated. No percentage or billed-row saving is inferred from the prior six-hour aggregate report.

## Validation

- Three new Robolectric test methods: posts/users pages with **100 overlapping additional callers share one scheduled operation** before any I/O begins; exact serialized variants/account keys differ; mutation/push invalidation detaches old pages and late completion cannot evict replacements. Explicit independent SERVER work and a new post-completion read are checked.
- Existing OnDemandQueryCacheTest strengthened: forced refresh uses SERVER; non-forced stale/evicted fallback uses DEFAULT.
- Two new Python wiring tests verify scheduling boundary, cache/server separation and both invalidation entrypoints.
- Local Python: **419 passed**.
- Local Worker: **293 passed**.
- Actual Worker/local SQLite replay: **100 users / 5 concurrent / 48 workloads passed**.
- Android compile/JVM suite [45286611392](https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45286611392): **success**, exact source `b14e18e2535fb580ff45325f5618d7eca625feaa`.
- Diff check passed.

## Delivery boundary

Android source-only; no new APK/AAB, Worker deployment, PR merge or data/schema change. Previously built version 177 does **not** contain this later patch. Packaging remains paused under the owner's latest instruction. Device/production prevalence and representative completed-day quota acceptance remain unverified.
