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
