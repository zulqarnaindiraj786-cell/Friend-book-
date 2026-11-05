# Interaction migration evidence — STAGING only

## Successful production administrative runs

- Raw snapshot backup: [41941688251](https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/41941688251).
- Normalized staging import: [41944815062](https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/41944815062), commit `94ae2fa`.
- Snapshot batch: `006ee816-4b8e-47a2-8677-ec1e881caf15`.
- Package and namespace: `com.friendbook.social` (guarded in both workflows).
- Android and Worker D1 switches: OFF. Verified/live-ready resources: **zero**.

## What was actually imported and verified

| Application table / purpose | Rows |
| --- | ---: |
| Resources, all state=staging | 83 |
| Resource reactions | 407 |
| Reaction timestamp provenance | 407 |
| Comments | 271 |
| Comment details/media/mentions/author snapshots | 271 |
| Comment timestamp provenance | 271 |
| Comment reactions | 68 |
| Pins | 0 |
| Original resource documents/counters + orphan preservation | 84 |

The importer re-read the complete normalized tables and compared every selected
column and identity with its proposal; it independently checked trigger-derived
reaction totals and exact preservation JSON/hashes. The 949 raw source snapshot
documents remain untouched in `friendbook_cf_copy_staging`.

Original typed values/URLs/counters remain in the raw archive; normalization is
not permission to discard a legacy variant. Five legacy likes were mapped. Known
Android reaction-map precedence was retained. Eleven comments and 35 reactions
had missing timestamps: schemas 0005/0006 preserve an explicit unknown marker,
not a fabricated date. APIs return null for unknown times; zero is only an internal
encoding distinguished from a genuine known epoch-zero date.

Only additive CREATE statements and INSERTs were used. Existing different rows
cause failure, not replacement. An exact retry skips identical records. Partial
imports stay intact on failure. No Firebase write/delete API is used by the
staging importer. No private exports or user records were uploaded as CI artifacts.

## Unresolved LIVE migration gates — do not hide these

- 30 resources have reaction counters differing from known normalized identities.
- Two resources have comment counter discrepancies; three comment representations
  have reaction counter discrepancies. Original counters are retained, not reset.
- One reaction has no source parent resource. It is preserved in quarantine, not
  attached to an invented post or exposed through authorization-free routing.
- Snapshot-time coverage is not a live change journal. Old and current OFF apps
  still write Firebase; final replay/watermark and a scoped old-writer fence are
  not implemented/deployed. Auth/chat/calls/presence must remain usable.
- Enabled build/device tests, backend notification dependency deployment, permission
  parity, rollback/reconciliation and reviewed activation remain required.

`safe_to_cutover=false`. Do NOT mark resources verified or enable either switch
just because the staging import passed. Cloudinary media remains remote; Firebase
Auth and realtime services have not been moved. This is not an all-Firebase export.

## Post-snapshot observation and notification prerequisite

Observation run [41947690174](https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/41947690174)
PASSED. At Firestore read time `2026-11-05T01:19:26.285930Z`, the observed 949
documents had the same complete snapshot hash as the baseline: no differences
were observed at that point in time. This is **not** a continuous change journal
and does not prove that no intermediate or later writes occurred.

Observation `f2201439-2855-4b4b-ad4c-a5c7184aa58b` has a verified append-only seal.
Resource-counter ledger classifications across 166 metrics: 134 match known
identities, 29 underreport them, two counters are absent, and one has an unsupported
counter value. Original values remain preserved. These are classifications, not
an applied source-counter correction or resolved cutover certificate. Application
rows modified by observation: zero; Firebase writes/deletions: zero.

`0007_additive_migration_observations.sql` creates administrative tables only.
Partial observations have no verified manifest; earlier observations are never
replaced. Full point-in-time reconstruction and every stored row were verified.

Supabase `friendbook-push` function `bulk-mentions` updated ACTIVE v3 to support
verified D1 comment sources; the existing `send-fcm-notification` function remains
unchanged. [41948533932](https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/41948533932)
PASSED all 45 mocked backend tests and production anonymous checks: POST returns
401/AUTH_REQUIRED; GET returns 405/POST_REQUIRED. No recipients or user notifications
were used. Enabled D1 notification delivery/device behavior remains unverified.
