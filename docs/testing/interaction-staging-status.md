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

## HTTP integration and reactor privacy follow-up (2026-11-05)

- Added four loopback HTTP integration scenarios: real Worker router, real RSA-signed
  test-token verification and real in-memory SQLite queries. The Firestore source
  provider and keys are synthetic fixtures; these are **not physical-device,
  production Firebase-token, Cloudinary-upload or notification delivery proof**.
- Covered unauthenticated/wrong-project rejection, both OFF/staging fail-closed
  paths before source access, comment/media URL/reply creation and idempotency,
  reactions/pinning/edit ownership, soft removal with original rows retained,
  50+11 comment cursor paging and 30+1 reactor paging, private-source/banned-user
  denial, and blocked actor/comment visibility.
- Confirmed and fixed a reactor-list privacy gap: source authorization's blocked
  context now reaches reactor queries. Blocked actors are filtered before LIMIT;
  reactors for a blocked comment return 404, matching single-comment access.
  Post and comment reaction rows themselves are not removed or rewritten.
- Local results: **25 Worker tests PASS**, **114 Python tests PASS**.
- Shared SQLite test adapter extracted without importing/registering another test
  suite; supports unbound reads as well as bound SQL and transactional batches.
- Both production gates remain OFF. No production user writes, schema import,
  fence/control activation or staged-resource promotion performed by this work.
  Final observation/replay, enabled-device QA and coordinated legacy-client
  rollout/fence remain required. Deployment result must be recorded separately.
- Actual deployment of commit `233fe83`: GitHub Actions run **41951547753**,
  job **126996412153**, **SUCCESS (19 seconds)**. Existing Worker/database
  retained, all 25 Worker tests and local schema tests passed in CI; read-only
  production Worker/D1 connectivity verification passed. BOTH gates OFF guard
  passed. No migrations or imports ran. This deploys the privacy fix but does
  not activate interaction traffic.

## Android transport and fresh observation (2026-11-05, follow-up)

- `028ba26` extracts the existing bounded one-attempt HTTP implementation into
  package-private `D1HttpTransport`. D1Client keeps its unchanged fixed HTTPS
  production URL, compile-time gate, Firebase token retrieval, serialized queue
  and before/after account guards. There is no public endpoint override, gate
  override, alternate writer or retry-to-Firebase path.
- Six actual loopback HTTP Robolectric tests exercise authenticated UTF-8 JSON,
  failed-write no retry, redirect refusal, pre-network request-size rejection,
  response-size rejection and malformed-success rejection. Synthetic bearer
  strings and a local socket fixture are not live Firebase/device evidence.
- First Android CI `41951871287` failed because Android test compilation does
  not provide `com.sun.net.httpserver`; `8134fd3` replaces it with standard
  `ServerSocket`. Corrected run **41952103897**, build job **126998221641**,
  **SUCCESS (7m4s)**: `testReleaseUnitTest bundleRelease assembleRelease`, R8,
  signed APK/AAB, uploads and release publication. Separate website Pages job
  **127000781889 FAILED** with integration permission error, not a green website
  deployment. Android package and both D1 gates remain unchanged.
- Fresh observation attempt `41951871270` stopped with ReadTimeout, safely and
  without application/source writes. `620b5a5` adds at most three attempts only
  for Firestore snapshot GET/listCollectionIds/runAggregationQuery transport
  failures, preserving the same request/readTime/page token. No destination
  INSERTs, arbitrary POSTs, HTTP authorization failures or user writes are retried.
  Three new retry-policy tests bring the Python suite to **117 PASS**. Worker
  suite remains **25 PASS**.
- Actual fresh observation **41952915125**, job **127000824857**, **SUCCESS
  (7m39s)**. Read time **2026-11-05T02:06:10.472758Z**; sealed observation
  **c9bafff7-82a1-46b8-a5fc-1b4a1f6b81c7**. All **949** documents unchanged;
  baseline/observed SHA256 both
  `164abd086b88825f8ffb83e61c51ed0cf54c66177d7d718e92425e3f2716e595`.
  No delta replay is needed for this particular observed snapshot. Application
  rows modified **0**, Firebase writes **0**, deletions **0**. Baseline raw
  counter/orphan classifications remain present; earlier preservation-policy
  resolution does not rewrite these source fields or invent the orphan parent.
- **NOT a completed live cutover:** observation explicitly reports
  `safe_to_cutover=false`; this is a point-in-time observation, not ongoing CDC.
  Enabled app/device authorization and UI flows still require QA, followed by
  coordinated legacy-client upgrade, fresh final synchronization and tested
  production fence/resource/gate activation. Production remains Firebase for
  interactions until that coordinated transition; Cloudinary media and Firebase
  Auth/chat/calls/presence remain unchanged. Never deploy the fence ahead of
  readiness while released clients still use the OFF/Firebase path.

## Owner-selected rollout: upgrade first (2026-11-05)

Owner selected **APK upgrade first**, not immediate interruption of old clients.
Activation remains conditional on successful tests, preservation and coordinated
cutover; this decision is not permission to run concurrent authoritative writers.

Version 2.8.34/code 66 adds an optional background update notice and a manual
Settings > Check for APK update action. A bounded public JSON metadata endpoint
contains only the package/version and allowlisted official APK download URL.
The app rejects foreign package names/URLs, malformed versions and JSON errors;
network failure never blocks launch. No silent install, uninstall, clearing data,
backend switch or account token is involved. It is preparation, **not an enabled
D1 release**: both gates stay OFF, package/signing identity remain unchanged.
Older installed APKs do not retroactively acquire this update notice; those users
must install the official signed update over the old app. Installing this build
alone does not prove rollout/adoption or migrate interactions.

Local tests: 27 Worker PASS and 119 Python PASS. Three new Robolectric update UI/
policy tests are submitted to the signed Android build; CI evidence follows on
PR29. Public metadata gets a production read-back check in the OFF-only deployment
workflow. APK/AAB publishing remains Android-only; docs/backend-deploy-only edits
no longer trigger an unrelated Android release.

Additional confirmed activation blocker: unknown/new posts or reels have no
verified D1 resource marker, and the current API correctly returns 503 for them.
A secure post-cutover registration path is still required before global routing
can be enabled. Existing staging markers must never be automatically promoted
just to make a health check pass. No production fence/control is deployed here.

Upgrade-first release evidence:
- Worker deployment/read-only health/auth/actual public APK metadata read-back:
  **41956620916/job127012953553 SUCCESS**. Both OFF guards passed.
- First update-dialog UI test failed on an undrained asynchronous click callback
  (`41956620859`). `c274abf` drains the main Looper before asserting the intent.
- Corrected Android-only run **41956902900/job127013879515 SUCCESS (4m36s)**:
  full release unit tests (including three update-policy/UI tests), R8, signed
  APK/AAB, mapping uploads and GitHub publication passed. No website job.
- Published **v2.8.34 (66)** assets: `friendbook.apk`,
  `friendbook-playstore.aab`, `friendbook-mapping.txt`. Users should install
  over the existing app, never uninstall/clear data as a migration step.
- Runtime switch/fence and all resource markers unchanged. This build adds the
  upgrade flow but does NOT enable D1 or solve new-resource registration.
  No physical-device/adoption proof is claimed by these CI results.
