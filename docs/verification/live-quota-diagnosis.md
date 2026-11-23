# Live quota diagnosis — investigation, not savings proof

Cloudflare returned telemetry timestamp 2026-11-23 (provider timestamp).

## Measured evidence

- Account daily analytics at 05:00:11 UTC: 369,424 rows read / 5,279 rows written.
- Subsequent snapshot at 05:02:24 UTC: 370,971 / 5,372. These delayed/adaptive account totals are not a five-user breakdown or remaining quota balance.
- 15-minute query analytics: social friendship guard INSERT shape involving both stores: 69,912 reads / 72 writes / 72 executions (~971 reads/execution).
- Duplicate friend-request UPDATE shape: 11,856 reads / zero writes / 24 executions (494 reads/execution).
- Chat membership SELECT: 2,118 reads / 6 executions.
- Separate query-adaptive and account-adaptive datasets must not be equated.

## Production configuration verified, not assumed

Read-only workflow 45115304953 succeeded:
- Deployed FRIEND_GUARD_INDEXED is `true`.
- All six required participant/exception index names exist in production.
- Active deployment reported created_on 2026-11-23T02:48:39.845649Z.
- Schema inspection cost exactly 300 reads / zero writes, changed_db=false.
- No user documents fetched, migration, deployment, application traffic or schedule changes performed.

This rules out an absent flag or absent index names as the simple explanation. It does NOT establish index definitions, actual query plans, exception cardinality, deployed source identity, or which guard predicates dominate the observed cost. Missing-index fallback is not a proven root cause. Next investigation should compare deployed query shape/plans and request lookup indexes before proposing a production change. Preserve legacy authorization semantics and atomic guards.

Evidence:
- https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45115076235
- https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45115076427
- https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45115304953

No measured savings claimed. Previous 11,535/1,495 budget is a model only. PR #36 remains open/unmerged. Signed APK/AAB delivery does not demonstrate backend quota improvement.
