# FriendBook quota rollout — 2026-11-25

This record accompanies the owner-authorized staged Cloudflare Worker rollout from
`arena/b52bc74d-friend-book`.

## Safety boundaries

- The production deployment workflow must validate the live Worker, D1 and R2
  configuration before deployment.
- The current deploy workflow requires the pending D1 migration list to be an
  exact suffix of the reviewed sequence `0088` through `0108`. Any older,
  unexpected or out-of-order pending migration must stop the rollout. Migration
  `0108_notification_retention_seek.sql` is additive and has its own account-
  wide D1 usage preflight because building the new partial index scans existing
  notification rows.
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

## Notification fanout and retention follow-up

Like/reel-like notifications are deduplicated per actor, recipient and type into
ten-minute windows. The first event keeps its existing notification document
shape; later actions in that window return the existing reaction acknowledgement
with an optional coalesced indicator, do not create another inbox row, and do
not trigger another push. Self-notifications remain excluded. Comment/reply
events are unchanged. This reduces notification row creation and push fanout for
repeat likes; exact billed savings still require post-deploy D1/Workers analytics.

The Worker scheduled handler now removes at most 200 canonical notifications
per 15-minute tick: read notifications older than 7 days and unread notifications
older than 30 days. It accepts numeric millisecond dates and valid Firestore
`__type: timestamp` envelopes; malformed/unknown date or read-state shapes are
left untouched. The request path is unchanged. The additive retention index is
covered by `EXPLAIN QUERY PLAN` tests and account-wide quota preflight. Android
also reads a public `min_version_code` advisory through the existing hourly,
D1-free app-config refresh; `1` is the fail-open default. Play confirms whether
an update is available, and even clients below the configured minimum receive
an optional, rate-limited prompt only (no blocking/throttling in this rollout). This is
an intentional scheduled application-data retention policy, not a migration
backfill or a manual production delete. It is not enabled in production until
the guarded Worker+migration workflow is run.

The migration itself is reversible by dropping only the new index; reverting
Worker code disables future retention passes. Notifications already expired
under the retention policy are not recoverable from that index and should not
be represented as rollback-restorable data.

This workflow is not a live quota measurement and does not certify a zero-cost
or zero-read workload. After rollout, inspect Cloudflare D1 rows read/written,
Workers requests/CPU and R2 operations separately.
