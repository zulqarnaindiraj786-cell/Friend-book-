# Whole-app quota investigation follow-up

## Findings, not a resolved incident

Read-only adaptive query analytics run 45010855732 and account run 45010855763 completed while the incident was being investigated. In the latest 60-minute sample, the largest displayed read shapes were the shared preferred-document lookup (5,873 reported rows / 5,873 executions), canonical people-page shape (5,426 / 146), interaction-resource state query (3,055 / 157), and engagement summary (2,245 / 531). The largest displayed write shape was presence-session upserts (472 reported rows / 101 executions); other writes include shared document/mirror operations and old FCM claims. These are sampled query shapes, not exact billed costs, and the shared lookups do not identify a screen or action. The latest five-minute window contained little visible app traffic, so it cannot explain or rule out the previously reported burst.

The account-wide daily adaptive counter reported 1,228,484 reads and 54,049 writes at its sample time. This is delayed, not a live balance, and does not prove that five users consumed that amount. Incident-tail run 45011054659 returned no parseable HTTP events; it cannot attribute the burst or establish that traffic was absent.

## Notification write candidate: do not drop the index yet

The installed compatibility Worker permits investigating two redundant recipient indexes. The strictly read-only preflight was previously stopped by the index-build write budget before SQL. This follow-up separated the budget for the bounded **read-only** inspection from the unchanged 50,000-write limit for index-build preflights. Inspection 45011005194 reported 3,972 canonical index entries and at least 5,001 legacy entries (cap reached), with all four replacement indexes present. D1 reported 9,279 reads and zero writes for this inspection. Because the legacy count is truncated, the preflight deliberately failed; no index was dropped and the total index/drop/rollback cost is unknown. This investigation itself used quota and should not be repeated without a clear bounded purpose.

The old notification-index test measured 18 to 16 local writes per synthetic create only if both indexes are dropped; that is not a live measurement, nor does it explain the whole quota problem. A production drop still requires a safely bounded and separately gated migration, old Worker/rollback compatibility and actual headroom. A failed capped inspection is not permission to perform it.

## Next work

The main remaining task is to attribute the shared point reads and document/mirror writes to actual authorized flows and test changes against blocked, private, legacy, rollback and concurrent cases. The canonical Feed page currently reads candidate post rows and the authorization helper separately reads the authoritative post again. Reusing the page row may reduce reads but is **not yet safe to deploy**: stale legacy shadows and privacy changes across reads must retain their existing authorization semantics. Treat it as an unverified candidate, not a fix. Presence-session writes likewise need multi-device and expiry correctness; their indexes support real cleanup and cannot simply be deleted. No new production Worker or APK was deployed in this follow-up, and the complaint remains unresolved.
