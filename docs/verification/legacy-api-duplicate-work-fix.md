# Legacy document API duplicate reads and profile no-op writes

Final deployed source: 633fd14827438ad4eb8671939c77e4b9063bdd61.
Successful rollout: https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45175827653
Provider-reported completion: 2026-11-23T12:36:39Z.

## Verified fixes

1. `/v1/docs` lists already fetched canonical rows, but non-joined roots then queried the canonical document again per item. The list query now carries the canonical path/source marker and only skips that redundant re-fetch when path and valid document ID match. Content/privacy ACLs still run unchanged, including independent post authorization reads. A 20-post local test has zero duplicate root reads, retains all 20 post ACL checks, excludes private posts and denies public posts after author blocking on a subsequent request. This is a legacy API fix; traffic frequency from the installed Android build has not been established.

2. The legacy `/v1/docs/users/{id}` adapter injected a new JSON `updatedAt` on every request. This defeated the guarded canonical route's existing same-data/no-op checks. Only profile forwarding now relies on canonical `update_time` instead of injecting a fresh timestamp. Client-provided fields remain subject to unchanged authorization. Genuine edits still update canonical revision metadata. Content/event/page forwarding retains historical JSON timestamp behavior.

Local profile test advances the clock each save and checks five identical saves cause zero base/mirror/guard/projection row changes. A missing mirror is still repaired. Genuine name edits advance update_time. Same-value writes from another actor return 403 without row mutation. `total_changes` is local SQLite correctness evidence, not D1 billing proof.

## Deployment validation

251 Worker tests and 372 Python tests passed, along with schema/migration/index-plan checks and offline 30-user Worker replay. Production binding/flag/cron preservation, health 200 and unauthenticated API 401 checks passed. No D1 migrations or index/user-data cleanup ran. PR #36 remains unmerged.

The first rollout 45175755396 briefly shipped the broader legacy timestamp change. Final rollout 45175827653 superseded it with profile-only timestamp deduplication, preserving content timestamp contracts. A cancellation attempt returned GitHub HTTP 403; no cancellation was claimed.

## Scope still unverified

No claim that these legacy API paths caused every measured write or point lookup. Existing-room chat-list read cost, complete mirror-write attribution and full-day project target acceptance remain unresolved. Normal necessary mirror consistency writes are not disabled. No measured live savings percentage or full-resolution claim is supported yet.
