# Avoid redundant provenance rows for fresh reactions

The reactor-list API already treats a missing `friendbook_cf_reaction_history` row as `timestamp_known=1`. Only explicitly imported rows with `timestamp_known=0` mean an unknown historical time. New reaction actions previously inserted both the reaction and a redundant known-history row. The Worker now leaves missing provenance implicit, while retaining and atomically updating explicit unknown markers on a fresh action. It never deletes an existing history row. The set-state API, totals, authorization, push scheduling and Android code are unchanged.

The write batch first upserts the reaction and then updates an existing unknown marker. An identical known reaction keeps its timestamp and writes nothing; an unknown legacy reaction records the new action time and changes its marker to known, even if its type is unchanged. Both writes and the acknowledgement run in one D1 transaction. Failure during the marker update rolls back the reaction and totals.

## Verification

- Local Miniflare D1 with the real handler and prior production schema: first compact Like **4 reads / 6 writes / 4 statements** before, **4 / 4 / 4** after; identical retry **7 / 0 / 4** before, **5 / 0 / 4** after. Like-to-love changed **9 / 4 / 4** to **8 / 4 / 4**; explicit unknown-history same-type action remained **9 / 2 / 4**. These synthetic local D1 metadata are not account-wide billing. Real cost includes authorization, push, notifications and other actions.
- Regression tests cover implicit and explicit known timestamps, known epoch zero, unknown historical timestamp, duplicate retry, action changes/removal, reaction totals, a marker-update failure and full rollback. A separate test simulates the compatible old SQL processing a new reaction with no marker; it keeps the timestamp and count while it may create the known marker.
- 188 Worker tests, nine schema/plan tests and 280 script tests passed. The 1,000-user offline real-Worker replay executed 33,491 synthetic requests and collected 63 SELECT plans with no EXPLAIN errors; its 1,000 first reactions changed 2,000 SQLite rows (not D1 billed/index writes), and 1,000 repeated reactions changed zero.

## Rollout and rollback

Second-step production deployment 44984939207 and Worker validation 44984939217 succeeded, with read-only connectivity and no production test likes. Android compile/unit run 44984939206 encountered a Robolectric Maven download IOException; Android source was not changed, and a fresh verification is needed.

Compatibility Worker `b19cdc1` was deployed first (44984730032). It still inserts known markers, but changes the old `NOT EXISTS known` condition so missing markers are treated as known. Worker validation 44984730075 and Android compile/unit 44984730045 passed. The second step stops creating new known markers. There is no schema migration and no data backfill.

Roll back the second step to **b19cdc1 or a later compatible Worker**, not a pre-compatibility version: a prior Worker can treat a markerless known reaction as unknown and refresh its timestamp on an identical retry. Although the compatible version was deployed and verified before the second step, a pre-compatibility request already in flight during rollout is not formally bounded by these tests; it could still apply that older behavior. A compatible rollback may create missing known markers on subsequent writes, so it loses the write saving but does not require a costly backfill. Do not delete existing markers, fabricate traffic or test rollback on production.
