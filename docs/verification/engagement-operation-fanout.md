# Queued engagement operation fanout

Source: **99b243b7c8d37271781107a724992d13b8ddcbc0**.

## Identified bypass

D1Client previously scheduled every caller on its executor and acquired a token before consulting EngagementReadCoalescer inside that worker. The transport coalescer only joined overlapping HTTP intervals. Identical requests already queued could execute sequentially and repeat the entire authorized engagement request (post/viewer/author point dependencies plus summary/preview/count work). D1Reactions has its own per-key waiters, but direct detail/comments/recovery callers also use D1Client, so those waiters are not an app-wide queued-operation guarantee.

The new outer pending-operation registry shares identical GET/no-body operations **before scheduling**. Keys include actor, collection, document ID, kind, exact query and interactive/normal lane. No completed response cache or additional TTL is introduced. Results are stored as immutable strings and reparsed per caller to avoid sharing mutable JSONObject state. Account identity is rechecked at delivery. Non-GET/body-bearing operations do not join, and detach old reads before starting and on completion (including failure), so a read after a write cannot join a pre-write pending operation. Existing transport security, token refresh and failure handling remain.

## Evidence

- Android compile/JVM **45289073158 SUCCESS**, exact source above.
- New generic registry tests: summary/comments simulations with 100 extra queued callers schedule one operation; per-consumer JSON mutation isolation; account/resource/cursor/kind/priority separation; invalidation and late completion cannot remove replacement flights.
- Python **424 passed**, including D1Client production wiring guards.
- Worker **293 passed**; 48-workload quota replay completed successfully; diff check clean.
- Generic helper tests plus wiring tests are not an end-to-end HTTP capture, installed-client trace, production duplicate frequency or billed-read savings measurement.

## Combined four-family status (not quota acceptance)

| Consumer | Source mitigation now present | Remaining proof |
|---|---|---|
| Point lookups | Document and profile whole-operation sharing; avoided duplicate list/engagement requests also avoid their point ACL dependencies | Route/identity-level installed traffic; required authorization reads remain |
| Engagement summary/preview | Existing per-resource waiters; Profile bundled-mode correction; new whole queued D1Client operation sharing | Actual duplicate prevalence and corrected-client read totals |
| Posts lists | Exact whole-query flights; initial pager sharing; short search-head reuse | Device behavior and production usage |
| Users lists | Exact whole-query flights; initial pager sharing; short repeated-prefix reuse | Device behavior and production usage |

Outstanding source investigation includes raw delta-head refresh operations, which still have a separate merge path, and whether other list surfaces need existing bundled responses. This record does not assert all redundant calls are gone. No APK/AAB, rollout, migration or merge was performed. <=100,000 reads/<=5,000 writes per representative day remains unverified.
