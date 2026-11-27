# Current quota consumers: fresh analytics, not historical attribution

## Work performed

Reconciled a reconstructed local Git index/HEAD with the existing session branch: fetched only `arena/b52bc74d-friend-book`, verified all **1,026 remote-tracked files** against Git blob hashes with **zero mismatches**, verified no staged changes, then aligned local HEAD/index with that same remote branch using mixed reset (no working-file changes). No prior source work was overwritten or bulk recommitted.

Executed two GraphQL-only production reports. No D1 SQL, full-table audit, application load, deployment or packaging was run. All timestamps below are provider/run-reported, not assumptions about the local clock.

## Primary evidence: six post-rollout hours

Run [45285741198](https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45285741198), source `93f73f9`, **success**. Window **2026-11-23T17:05:52Z–23:05:52Z**. Last independently checked server rollout d9e685a / 45220396473 succeeded, updated at 16:47:43Z, before this window.

Database-level query totals: **50,428 rows read / 2,122 rows written / 17,535 executions**. Account database split observed this primary database only and was not flagged truncated. Query-shape lists reached their top-60 limit and **may be truncated**. Database aggregates and top-shape subtotals must remain distinct.

### Read shapes ranked by reported reads

| Shape family | Executions | Reads | Returned |
|---|---:|---:|---:|
| Engagement canonical summary/preview | 1,654 | 8,884 | 1,654 |
| Canonical indexed point lookup, shape A | 7,669 | 7,669 | 7,669 |
| Posts bounded list shape | 152 | 5,472 | 5,472 |
| Users bounded list shape | 119 | 4,779 | 4,779 |
| Historical canonical comment count | 827 | 3,308 | 827 |
| Canonical indexed point lookup, shape B | 2,876 | 2,837 | 2,837 |
| Ordered inbox membership shape | 159 | 2,427 | 756 |
| Other membership shape | 124 | 1,496 | 686 |
| Comment page | 31 | 1,240 | 278 |
| Other bounded canonical list | 124 | 1,212 | 1,208 |

Point shapes A/B together: **10,545 executions / 10,506 reads**. This establishes a major frequency target, not 10,545 client HTTP requests, a specific device, duplicate document identities or a proven polling loop. Shape A costs one reported row read per execution; suppressing required fresh authorization reads is not an acceptable optimization.

The current rank is materially different from the old four-day report: engagement is 8,884 reads in this six-hour window, not an assumption that historical guard/promotion/backup shapes are currently dominant. List rows returned closely track reads for the displayed shapes; the shape aggregate does not prove every returned row is displayed or every query optimal.

### Write shapes ranked by reported writes

| Shape family | Executions | Writes |
|---|---:|---:|
| Snapshot-guarded canonical insert | 146 | 1,022 |
| Mirror insert, unattributed document family | 43 | 294 |
| Canonical friend-request guarded insert | 21 | 147 |
| Guard cleanup | 71 | 140 |
| Attributed batch:chats canonical upsert | 131 | 139 |
| Attributed batch:chats mirror upsert | 48 | 96 |

These are different shapes, not matched canonical/mirror pairs. They do not prove every mutation doubles writes. The leading canonical insert is **7 reported writes/execution**, not the historical 66. It still needs exact source/route attribution before an index or transactional guard change is justified.

## Short/mixed-window context

Run [45285425079](https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45285425079), source `8deef12`, **success**, end **2026-11-23T23:03:53Z**:

- 5 minutes: **5 reads / 0 writes / 4 executions**.
- 60 minutes: **196 reads / 0 writes / 192 executions**. Largest read shape: canonical point lookup **155 executions/155 reads**.
- Rolling 24 hours: **1,130,522 reads / 64,490 writes / 131,998 executions**. Above the owner's 100k/5k target, but mixes earlier fixes and diagnostic/maintenance activity. Not a completed post-change UTC day.
- Old promotion (45,581 writes), coverage (169,534 reads) and unresolved inventory-like aggregate (151,719 reads) remain visible in the mixed 24h ranking. Their presence there is not proof of recurrence in the six-hour window.

The quiet last hour is not representative proof or a daily forecast. The six-hour window is broader evidence, still not a capacity/acceptance test. Installed Android version and user activity are unknown.

## Validation and limits

- Analytics privacy/attribution tests: **26 passed** in both reporting jobs.
- Local full Worker: **293 passed**.
- Local Python: **417 passed**.
- Actual Worker/local SQLite replay: **100 users / 5 concurrent / 48 workloads passed**.
- Diff check passed.
- Initial local Worker run failed because the reconstructed sandbox lacked npm dependency `jose`; `npm ci --ignore-scripts` restored the lockfile dependencies, after which all Worker tests passed. No source change was made to mask the environment failure.
- Full report artifact download hit the known blob-host EOF. Figures above were independently read from successful job annotations; no unseen artifact data is claimed.

## What is and is not changed

Only analytics workflow lookback/diagnostic comments were changed. **No new application fix or production savings is claimed in this refresh.** Packaging remains paused after the owner's latest correction. PR36 stays unmerged.

Next high-value investigation is point-lookup route/callsite attribution during actual activity and exact attribution of the 146 leading canonical inserts. Existing Android flight/cache/rebind fixes must not be treated as installed. Do not introduce another unused batch endpoint, blanket authorization TTL, mirror removal or index drop merely to manufacture a patch. Completed-day target remains **UNVERIFIED**.
