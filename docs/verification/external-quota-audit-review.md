# Repository verification of the external consolidated quota audit

## Scope and source confidence

The owner supplied an external analytics report. Its raw GraphQL responses, query definitions, deployed-bundle snapshot and artifacts were not supplied here; its new observations are **owner-provided external evidence, not independently remeasured telemetry in this review**. This review instead checks its three repository questions against the current branch and reruns actual code/tests. No production SQL, index drop, migration, deployment or APK/AAB was performed.

## 1. Are notifications/chat document queries still dual-reading mirrors?

Reviewed `cloudflare/firestore-documents.js`:
- `readRootStorageRow()` does one canonical primary-key lookup. A missing canonical row does not read the mirror.
- Notification recipient compatibility selection reads `friendbook_cf_firestore_documents` only. Its missing-page-index handling changes the canonical query plan, not the storage source.
- Chat ordered candidate reads use canonical documents and `friendbook_cf_chat_participants` **source=1**; generic chat membership likewise restricts to source=1.
- Mirror writes/atomic snapshot checks and friendship compatibility guards still exist. Those are not user document read fallbacks and must not be removed merely because the mirror table occurs in source.

Existing tests assert canonical-only notification SQL, canonical chat missing/membership/revocation behavior, and indexed inbox cursor/privacy semantics. **The reviewed routes do not establish the report's alleged current notification/chat duplicate fallback.** Two historical table shapes are not evidence the same current request executes both. Old APK attribution is also unproven: an old client calling the current Worker does not itself restore old server SQL; one must trace the actual endpoint/query specification and deployed revision.

## 2. Can friendship social_guards be simplified just by deleting NOT EXISTS clauses?

No safe deletion is justified by the supplied aggregate alone. Reviewed `friendship-candidates.js` and friend acceptance/unfriend transactions:
- Participant-position and exception indexes already narrow candidates.
- Native, wrapped/long/object historical arrays and canonical-shadow precedence are explicitly handled.
- A transactional malformed-row probe preserves fail-closed/historical JSON-error behavior when indexed candidate ordering would otherwise hide malformed rows.
- Snapshot equality, target-path absence, duplicate detection, counters, ledger/mirror writes and guard cleanup remain in the same batch.
- Guard writes were already combined into one temporary assertion row. Source has real regressions for stale request/user snapshots, concurrent duplicate/path creation, malformed JSON and mirror-write rollback.

The reported 39,576 reads / 24 executions is about 1,649 reads per execution in that historical shape/window, not per app session or per successful friendship mutation; a guard row is not the entire transaction. Another recorded window had 73,563 / 129 (~570 per execution). Do not mix those windows or remove security checks to meet a ratio. This review found no newly proven redundant predicate safe to remove. Required next evidence for a guard optimization is the **exact shape + relevant data distribution/exception cardinality + before/after plan/work + transactional race/security parity**, not fewer textual subqueries.

## 3. Which JSON indexes are unused?

Built an offline SQLite database from all checked-in migrations, and ran the actual Worker replay at **100 synthetic users / concurrency 5**, collecting EXPLAIN plans:

| Local observation | Count |
|---|---:|
| Explicit indexes (sqlite_master SQL not null) | 110 |
| Explicit indexes containing json_extract/json_type/json_array_length | 79 |
| Triggers | 26 |
| Distinct replay query texts with plan records | 48 |
| Explicit indexes observed by name in these replay plans | 23 |
| Explicit indexes not observed in this replay | 87 |

These are **local source-schema/workload counts, not live production inventory**. They do not validate or disprove the external report's 78/18 production counts. Autoindexes, FTS internals, schema/version differences and definition of expression indexes matter. A replay only covers its synthetic routes, data, arguments and branches. Its 87 unobserved explicit indexes are **not 87 unused indexes**, and this is not a drop list. Indexes can serve other routes, rare privacy/encoding exceptions, triggers, constraints or differently distributed data. Per-index drop experiments would additionally require write/read workload coverage and parity; none were performed against production.

Scratch plan/schema results stayed under `/tmp`. No user rows were queried.

## Corrections to conclusions in the supplied report

- High execution count alone does not prove a recurring loop. Engagement/canonical path/notifications/chat reads can be normal demand-driven work; screen/session/route evidence is needed.
- The 147,250-read presence OFFSET shape is already exactly attributed to the **old retired-table backup/checksum script**, not app presence polling. Removal from the deployed bundle does not establish who executed that script or a daily recurring saving.
- A historical day's 173k/147k cost versus zero in one quiet hour is not measured 173k/147k-per-day savings. Comparable workload/window/version evidence is missing.
- The external report's suggestions for the 151,719-read inventory do not exactly match it. Different grouped/WITH templates must not be relabeled as that caller.
- An unattributed aggregate/shape gap is not proven unavoidable index/trigger under-attribution. Top-N limits, filters, sampling, windows, grouping and counter definitions must first be reconciled. Neither platform defect nor accounting explanation is established by the supplied report.
- Diagnostics/engineering work is still billed account usage. If an observed account day exceeds the owner's thresholds, that account-day observation is over target. A pre-/post-fix mixed window separately **cannot decide acceptance of the current fixes**; it does not erase the observed overage.
- An hour at 1,773 reads / 3 writes is useful recent traffic evidence if raw data is valid, not proof of representative daily behavior.
- For a fix activated partway through a recorded November 23 UTC day, November 24 is the first fully post-fix UTC day; it completes at November 25 00:00 UTC, followed by any analytics delay. November 24 00:00 is not completion of that first whole post-fix day. Do not reset the proof boundary for source-only Android fixes that are not installed.
- Run 45190970384 was independently checked here: SUCCESS, source `eb6ca8987af580f295d6f91b2584a666bf89daf3`, updated_at `2026-11-23T14:08:59Z`. This confirms that deployment run's metadata, not a fresh assertion that no subsequent live deployment exists. The external report's older 06:03:58Z reference must not replace the separately recorded boundary without version verification.

## Validation/outcome

Targeted seven-file suite: **25 tests PASS** (chat canonical reads, notification indexes/cursors, inbox paging, indexed friendship candidates and acceptance/unfriend transactional races). The 100-user/concurrency-5 network-disabled actual Worker replay passed all **48 workloads**. Full-suite results are recorded alongside this review after rerunning.

No new application optimization was justified solely from this external report; no speculative fallback/index/ACL change was made. Existing uninstalled Android fixes and unknown attribution remain as documented in `wider-quota-consumer-review.md`. The external report did not independently inspect or validate those source fixes. Whole-app representative completed-day quota acceptance remains **UNVERIFIED**.

Full rerun in this review: **289 Worker/Node tests PASS; 413 Python tests PASS; diff check PASS**. Android production source did not change in this review; previous combined Android compile/JVM success 45207597668 remains the recorded client validation. No packaging, merge or deployment was triggered.
