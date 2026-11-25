# Quota follow-up: reaction write attribution

Date: 2026-11-25

Status: source-verified findings; this is not production billing telemetry.

## Current merged state

- PR #42: comment candidate de-duplication change is merged and deployed; production Worker version recorded in the deployment report: `bfde2990-3743-4c5a-9723-2986dfb549b2`.
- PR #43: actor/resource-scoped legacy Reel reaction fallback gate is merged.
- PR #44: legacy post-backed Reel reactor hydration is deferred until the user opens the reactor sheet; merged.
- PR #45: 2,000-DAU baseline and acceptance gates are merged. It is documentation, not a quota code change.
- APK/AAB packaging remains out of scope.

## Reaction write path: what the source actually does

The canonical `PUT /v1/engagement/{collection}/{id}/reactions` path uses an absolute desired state, not a toggle:

1. The reaction row is inserted only for a non-null type, an existing actor row, or an explicit legacy unknown-timestamp history marker.
2. Conflict updates run only when the stored type differs, or when the legacy timestamp marker needs correction.
3. A separate history update changes only rows whose `timestamp_known=0`.
4. The compact reaction response batches the state mutation, summary read, and optional idempotent inbox event. The notification insert is guarded by `NOT EXISTS`.

The existing regression test `cloudflare/test/interactions.test.js` verifies that a repeated compact reaction request leaves SQLite `total_changes()` unchanged, that an identical reaction notification retry does not create another row, and that quick-tap sequences preserve the final reaction state.

**Quota implication:** counting two SQL statements as two billed D1 writes for every reaction would overstate ordinary modern repeated taps. But reducing the planning model to one write per tap without accounting for legacy timestamp transitions and optional notification creation could understate writes. Keep the current conservative model until the replay reports these cases separately; do not delete the history update or notification path.

## Other write-path constraints

- FCM device claims are durable idempotency state. Removing them risks duplicate push delivery on retries.
- Chat message + room preview updates are transactionally guarded. Do not split or drop them without a replay proving equivalent delivery and unread-count behavior.
- Root document writes still maintain the legacy mirror where compatibility requires it. A blanket mirror removal is not approved by this audit.
- Live heartbeat coalescing already suppresses unchanged/quiet updates under its existing monotonic-time rules.

## Evidence still required

1. Add separate local replay measurements for first reaction, repeated same reaction, reaction change, clear, legacy unknown-history transition, and optional notification creation. Report SQLite statements, returned rows, and changed rows including triggers separately.
2. Correlate a controlled Android action trace with Cloudflare D1 rows_read/rows_written and Worker request/CPU metrics for the same time window.
3. Keep cron, inventory/schema diagnostics, app traffic, and other clients separately attributed.
4. Run staged DAU-volume and burst-concurrency tests before claiming 2,000-DAU capacity.

Local SQLite replay and unit tests are useful regression evidence, not Cloudflare billing or production-capacity proof.
