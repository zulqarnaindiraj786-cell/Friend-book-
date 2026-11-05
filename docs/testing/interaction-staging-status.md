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

- Baseline cached-counter differences were reviewed and preserved (see current
  evidence below). Original fields are not reset. Historical unknown actors are
  represented as aggregates, not fabricated identities. This review is not a live
  writer fence, final replay or an authenticated device certification.
- One reaction has no source parent resource. It is preserved in quarantine, not
  attached to an invented post or exposed through authorization-free routing.
- Snapshot-time coverage is not a live change journal. Old and current OFF apps
  still write Firebase; final replay/watermark and a scoped old-writer fence are
  not deployed. A REVIEW-ONLY interaction fence has passed isolated SDK tests;
  deploying it while existing OFF clients are in use would reject their writes.
  An enabled-client rollout and explicit cutover sequencing are still required.
- Enabled build/device tests, authenticated notification delivery, permission
  parity, rollback/reconciliation and reviewed activation remain required. The
  bulk-mention backend prerequisite itself is now ACTIVE v3 (details below).

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

## Baseline counter reconciliation and old-writer fence draft

Historical aggregate preservation and baseline review passed in run
[41949901943](https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/41949901943).
Three comments have **36 historical unattributed reactions** in total. Source
actor IDs for this portion were absent; no users/reaction identities were invented.
Schema 0008 and three verified provenance rows retain the residual aggregate
separately from known actor rows. New known reactions increment/decrement their
own portion; the historical portion remains preserved. Reactor responses expose
`historicalUnattributed` instead of creating fake people. Android's D1-only dialog
now explains missing historical identities and avoids a false "No reactions yet".

Counter review policy v2 verified staged identities/content against the sealed
snapshot: 857 matches, 29 original underreported caches retained, ten missing
counter fields retained as absent, one invalid numeric cache retained, and three
historical aggregates reconciled by exact metadata read-back. **Zero unresolved
baseline counter cases under this preservation policy**. This does not recover
unknown actor identities, repair source fields or certify post-observation writes.
One orphan reaction remains preserved in quarantine, not attached to an invented
parent. `safe_to_cutover=false`; application rows are still state `staging`.

The generated `firebase/firestore.interaction-fence.review.rules` is NOT referenced
by production `firebase.json`, and has NOT been deployed. The server-only control
would be `friendbookMigrationControl/interactions`. Missing control or explicit
`firebase` keeps legacy writes; a fenced/unknown phase blocks interaction writes
while preserving reads, unrelated post edits, empty initial post creation, profiles
and Firestore chat/message paths. RTDB rules/Auth/Cloudinary are not changed.

[41949527257](https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/41949527257)
passed eight isolated demo-emulator SDK tests including atomic batch rejection,
server-only control, no-op updates, source comment preservation and re-opening.
This is NOT production rule deployment or a real-device chat/call/Auth test. Never
activate this fence before the enabled-app rollout and final backup/replay plan:
current OFF clients would otherwise receive permission-denied on interactions.

Android commit `0a8599d` passed signed release build job `126992035163` in run
[41950189062](https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/41950189062):
compilation, release tests (including three historical-notice UI tests), R8,
signed APK/AAB and uploads. This OFF build keeps package `com.friendbook.social`.
GitHub Pages deployment still fails with an unrelated integration permission error;
this must not be represented as an all-checks-green result. Local Python regressions:
114 PASS; Worker tests: 21 PASS. Enabled physical-device behavior remains unverified.
