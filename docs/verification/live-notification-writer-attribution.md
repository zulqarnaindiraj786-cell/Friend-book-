# Live largest-write attribution after listener fixes

Analytics runs 45306188331 and 45306363373 succeeded. These use Cloudflare analytics only: no production SQL, simulated app requests, migrations or Worker deployment.

Latest returned measurement end: 2026-11-24T01:37:13Z (provider-reported; do not infer user's local activity/time from it).

| Window | Reported reads | Reported writes | Executions | Interpretation |
|---|---:|---:|---:|---|
| 5 minutes | unavailable | unavailable | unavailable | No observations; not zero |
| 60 minutes | 2,495 | 0 | 1,240 | Not a representative active-use or daily acceptance test |
| 360 minutes | 35,873 | 2,058 | 12,448 | Mixed historical usage/deployments; not a daily forecast |

Database dimension returned only the primary database in observed groups. Six-hour shape ranking is potentially truncated; totals and returned shape subtotals are distinct.

## Newly resolved attribution

The six-hour leading canonical INSERT shape (147 executions / 1,029 reported row-writes) is now matched to the exact create-only notification branch. Previously it lacked writer aliases and appeared unattributed. The reporting classifier fingerprints the full fixed INSERT columns and VALUES expression, including canonical absence check and literal notification parent/collection; it does not infer this from write cost or loose occurrence of the word notification. No user SQL or bindings are emitted.

This identifies a writer family, not duplicated events, a particular actor/device, 147 HTTP requests, or proof that the 1,029 writes are avoidable. Its seven reported writes/execution must not be presented as seven duplicate notifications. The separate notification-mirror upsert shape reports 38 executions / 188 writes; these are not matched pairs.

Other returned six-hour leading reads: canonical point seeks 5,196 executions/5,196 reads, users list shape 120 executions/4,018 reads. Latest hour leading reads: engagement summary/preview 154 executions/930 reads, canonical point seeks 570 executions/570 reads. These figures do not justify dropping ACL checks or genuine notifications. No new application performance fix is claimed in this attribution-only change.

## Source/runtime gap remains material

APK178 predates the verified shared document-listener operations, saved-comment notification identities, queued account-switch cancellation and shared revision-bearing listener query operations. Those source fixes cannot change installed APK178 behavior. Its previous owner-authorized build does not authorize bypassing the explicit build pause for a new signed package. No new APK was built and no PR merged here. Representative use of a corrected installed client and daily <=100k reads/<=5k writes acceptance remain unverified. Quiet analytics does not invalidate the owner's zero-reset spike report.

Validation: 27 attribution/privacy tests PASS; full 431 Python tests PASS; exact notification fingerprint positive source test and negative different-root/conflict/guard tests PASS. No application/Worker code changed in this turn.
