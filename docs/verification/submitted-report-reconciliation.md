# Submitted six-minute report: source checks and post-fix measurement

The owner supplied a report labelled last five minutes, with stated bounds 2026-11-24T00:10:46Z–00:16:46Z and latest point 00:15:46Z. Treat the supplied figures as external/unverified until datasets, bounds and aggregation reconcile.

Source-confirmed corrections:
- `quota_writer_document_notifications` is a fixed production SQL alias created by writeAttribution. It adds no logging, query or probe. It is not evidence that an experiment generated those notifications.
- `quota_people_head` is the actual boundedPeoplePage SELECT alias, not an independent synthetic workload.
- The notification create-only canonical INSERT has the CASE-WHEN-NOT-EXISTS shape, but seven reported writes per insert does not mean seven app notification documents. Do not remove authorization/atomic duplicate guards or indexes without measured evidence.
- 540 SQL point executions do not imply 540 separate document HTTP requests: accountStatus intentionally reads current account state on authenticated requests, and authorizePost reads dependencies. Batching HTTP alone cannot justify 13,016->2,000 billed reads or 80% savings.
- Query-level 530 writes versus DB-level 174 is not reconciled. Sampling/index/trigger explanations are hypotheses, not established causes. Do not add incompatible datasets or accept shape rankings as automatically comparable across them.

The submitted window predates the exact friendship-pair fix: rollout 45297036141 started 00:25:29Z and completed **00:26:12Z**, confirmed by run API. Its source and additive index changes are recorded in friend-pair-spike-live-fix.md.

Fresh analytics **45297551887 SUCCESS**, provider window **00:24:27Z–00:29:27Z**: 2 executions, 0 rows read, 0 rows written, one observed primary DB, shape lists not truncated. Observed shapes were SELECT-constant and EXPLAIN. This window overlaps rollout and contains no observed meaningful application workload; **it is not proof of fixed active-use quota**. Index-creation billing may not be represented in these two query shapes; do not claim the rollout cost nothing.

No further application source patch or deployment was made based on the supplied inconsistent totals. No automatic APK build. Analytics tests 26 passed. The useful next acceptance evidence is a bounded normal-use session on existing Android 178 after 00:26:12Z with exact UTC start/end and screens/actions, then matched route/query metrics. Daily quota acceptance remains unverified.
