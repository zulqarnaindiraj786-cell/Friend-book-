# FriendBook quota rollout — 2026-11-25

This record accompanies the owner-authorized staged Cloudflare Worker rollout from
`arena/b52bc74d-friend-book`.

## Safety boundaries

- The production deployment workflow must validate the live Worker, D1 and R2
  configuration before deployment.
- For this branch, the pending D1 migration list must be an exact suffix of the
  reviewed migration sequence `0088` through `0104`. Any older, unexpected or
  out-of-order pending migration must stop the rollout.
- The comment-reaction covering-index migration (`0103`) requires the workflow's
  account-wide quota preflight. The workflow must fail closed if that preflight
  cannot establish sufficient reserve.
- The workflow's legacy-comment index and canonical-coverage checks remain
  mandatory whenever their respective migrations/configuration require them.
- No application document, post, message, profile, comment or media object is
  intentionally deleted by migrations `0088`–`0104`. Some redundant indexes
  and legacy-derived maintenance triggers are retired; the underlying data
  tables and records remain.
- If any guard fails, do not bypass it or manually apply migrations. Keep the
  existing Worker version active and inspect the sanitized workflow diagnostics.

## Rollback notes

Worker rollback must use the previously deployed Cloudflare Worker version.
Database rollback, if needed, is schema-only: recreate any retired indexes or
triggers from the reviewed migration history before reverting Worker code.
Do not delete application data as part of rollback.

This workflow is not a live quota measurement and does not certify a zero-cost
or zero-read workload. After rollout, inspect Cloudflare D1 rows read/written,
Workers requests/CPU and R2 operations separately.
