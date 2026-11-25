# Major consumer: fresh ranking and canonical content GET repeat

## Production ranking (analytics only)

Report **45291392949 SUCCESS**, source 4647abf, provider-reported window **2026-11-23T17:44:34Z–23:44:34Z**. These are timestamps returned by the reporting workflow. This is an overlapping rolling window, not a before/after experiment against the earlier report.

Database totals: **27,554 reads / 1,315 writes / 10,619 executions**. One observed database; 54 shapes; reported shape lists not truncated.

| Existing target family | Reads | Executions |
|---|---:|---:|
| Canonical point lookups, two shapes combined | **6,769** | **6,797** |
| Engagement canonical summary/preview | 4,261 | 839 |
| Posts list shape | 3,024 | 84 |
| Users list shape | 2,838 | 68 |

Other visible users-filtered shape: 847 reads/77 executions. Historical comment counts: 1,828/457. These shapes do not establish HTTP counts, duplicate document IDs, routes, installed versions or daily savings. Lower totals than the earlier overlapping window cannot be attributed to undeployed/uninstalled source fixes.

## Concrete fix in largest family

Source **94b79a7**. Generic content-document GET loaded its canonical root using readRow, then contentReadAllowed/authorizePost independently loaded the same post/reel/story again. It now supplies the just-read canonical storage fields to the internal authorization function, so ACL and returned payload refer to the same fresh root snapshot. Only canonical root posts/reels/stories in this GET path can supply it; nested resources, query projections, request JSON and cross-request caches cannot. No TTL, profile/author authorization-cache extension or mirror fallback was introduced.

Viewer/author account and block checks, group authority and trusted friendship remain on their existing fresh/request-local dependency paths. A request after a privacy change reads the changed canonical row; it does not reuse another request's authorization. As with any snapshot read, a subsequent concurrent mutation does not retroactively change a response already read. The change removes a second root observation, rather than claiming an atomic snapshot across all ACL dependencies.

## Verification and scope

- **295 Worker tests passed**, including a new actual Worker GET test for posts/reels/stories: public allowed, Only-me denied to another viewer, owner allowed, author block denied, author ban denied. Each case asserts **one canonical content-root SELECT**.
- **426 Python passed**; quota replay **48 workloads completed successfully**; diff check passed.
- No Android source changes in this fix; prior cumulative Android run 45290514044 remains the latest successful Android compile/JVM evidence.
- This removes one repeated root read per qualifying content GET, not all 6,769 point reads. Production frequency of that route is not known from shapes alone.
- No deployment, migration, APK/AAB or merge. Production Worker remains unchanged. Four-family and <=100k reads/<=5k writes representative-day acceptance remain unverified.
