# Ad-social failed-rebind read budget

## Source cause

`AdsConfig.hydrateAdSocialFromFirestore` used a process-global creative-ID set. Successful/pending reads were already gated, but every failure immediately removed its ID. A subsequent feed/reel ad bind could therefore issue the same failed document read again. The global social model cache/fetched set also had no account scope or stale-callback fence.

This is a reproducible source retry opportunity, not proof of how often sponsored reads occurred in production. Ordinary missing/denied sponsored documents must not be mislabeled the cause of all point-lookups.

## Fix

`AdSocialReadGate` replaces the fetched set:

- Pending hydration remains single-flight per creative in the current account.
- Success/missing result preserves the existing settled-on-bind behavior.
- Explicit permission-denied reads settle on-bind too: repaint/rebind cannot repeatedly retry a known denial.
- Other failures allow a demand-triggered retry after 30 seconds, never a scheduled retry.
- Explicit comments/detail opens bypass settled/failure cooldown state, but do not duplicate pending work.
- Observed account changes clear the social model cache and gate state. Old account/attempt callbacks cannot settle a newer attempt or apply their social result.
- Signed-out binds do not start document reads.

No server ACL is weakened. Public/profile/feed/comment security and genuine reaction/share/comment writes are not altered. Existing missing-parent seed behavior is retained, not removed to fabricate write savings. A failure to authorize a sponsored document is not turned into permission to read/write it; any pre-existing sponsored-author/visibility compatibility problem remains separate.

## Regression evidence

Five pure-JVM tests cover repeated pending/settled binds, 30,000 demand checks within the transient-failure cooldown with zero retries, the retry boundary, explicit opens, independent creatives, signed-out behavior, account switching and stale callbacks. One Python wiring test verifies account-scoped cache reset, all callback gates, explicit refresh entrypoints, terminal-denial handling and retained missing-parent behavior.

Local results:
- Worker: **292 passed**.
- Python: **417 passed**.
- Actual Worker handler/local SQLite replay: **100 users / concurrency 5 / 48 workloads passed**.
- `git diff --check`: passed.

Android compile/JVM run is started separately for the exact source commit; success must be recorded below before calling validation complete.

## Delivery/acceptance boundary

This patch is Android source-only. It does not alter the live Worker version or install itself on users' phones. No APK/AAB, merge, schema change or cron change. The existing debug opt-in trace can help establish an actual device-window request count after an authorized instrumented install; no such run has happened here. No production quota saving or 100k reads/5k writes completed-day pass is claimed.

## Verified Android outcome

Android compile/JVM suite [45219235830](https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45219235830) completed **success** for exact source `e72591795b4567bf1744e4752fc5b4bc2f2d5d4b`. Worker validation 45219252691 also succeeded. Packaging workflow 45219235983 was **skipped**. No APK/AAB was built and no new Worker deployment was requested for this client-only fix.
