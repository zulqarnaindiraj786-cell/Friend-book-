# Dominant historical writes: exact promotion SQL match

## Confirmed attribution, not an app-loop inference

Analytics run https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45193677348 SUCCESS, prior 24h ending **2026-11-23T14:23:37Z**:

- Account/main D1 query totals: **1,096,036 reads / 63,033 writes / 121,535 executions**.
- The largest write shape is an **exact whitespace/case-normalized match** to `rowStatement` in `scripts/loadtest/promote_legacy_roots.mjs`: **5,078 executions, 5,078 returned paths, 16,088 reads, 45,581 billed writes** (about 72.3% of window writes).
- It is a guarded INSERT SELECT copying equivalent legacy rows into canonical storage, with unchanged-source and absence checks. It is not an ordinary app mutation template. Exact SQL matching establishes template identity, not a particular operator/device or CI run ID.
- A separate legacy inventory template matched **7,612 reads** within the top-read list. Do not subtract any such classified values from billed totals or claim the whole window is explained.
- Next-largest write shape is still unattributed: mirror INSERT, **380 executions / 3,420 writes**. Largest SELECTs remain unattributed (**169,534 reads / 9 executions** and **151,719 reads / 112 executions**). TOP-60 shapes are truncated.

Reporter now recognizes only the three exact fixed promoter templates (row INSERT, PAGE, INVENTORY), not generic table-name or operation heuristics. Tests reject near-matches and generic canonical/mirror INSERTs. Output remains sanitized; no SQL text, bindings, IDs, documents or credentials are emitted.

Historical completed run https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45089112889 SUCCESS is evidence of administrative backfill/coverage work in the window, but its reported promotion steps inserted zero; **it must not be presented as the run that generated all 5,078 inserts**. That particular run also reported a full inventory costing 42,896 reads. Actual run attribution for the dominant promotion shape has not been proven here.

## Preventing accidental reruns

Branch source **6a6989c** removes push triggers from the completed legacy-root-backfill workflow. Recovery is manual-only, requires explicit `confirm_production_scan=true`, defaults to diagnose, and selects diagnose/promote/finish through a typed input instead of commit-message markers. This preserves a deliberately authorized recovery option without accidental source-push scans/writes. It retains non-cancelling migration concurrency so a legitimate partially applied operation is not interrupted.

**Scope limitation:** this guard is committed on the current PR branch; it does not rewrite workflows on other/default refs. A repository-wide workflow disable was attempted and returned **HTTP 403 Resource not accessible by integration**; global disable was NOT applied. The previously observed push trigger targeted an older branch, not the current quota branch, and no schedule was found. Thus this is a preventative source guard, not proof an active recurrent migration loop was stopped now.

Full **390 Python tests PASS** (18 report tests plus backfill-gate wiring coverage included), workflow YAML parse PASS, diff check PASS. Worker/application code is unchanged; no production deployment, migration, data deletion, APK/AAB creation, merge, acceptance-threshold relaxation or proof-boundary reset occurred. Existing historical cost cannot be undone. Remaining daily quota acceptance and unattributed high-cost shapes remain open.

## Largest-read follow-up

Analytics-only run https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45194313477 SUCCESS (24h ending 2026-11-23T14:27:04Z) exactly matched the **169,534 reads / 9 executions / 11,798 returned rows** SELECT to the coverage-inventory template set. In the reviewed set, the matching canonical-only template is `verify_root_backfill.CANONICAL_SQL`, which grouped every parent path, including nested message collections. Other exact coverage templates brought the observed top-read coverage subtotal to **190,954**; these are classification totals, not savings or a licence to subtract billed rows. The **151,719 reads / 112 executions** shape remains unattributed. Window total: 1,093,469 reads / 63,033 writes; top-60 shape rankings remain truncated.

Source **3a39ecd** scopes current canonical coverage counts to the 13 root collections actually consumed by the report, using bound allowlist values and the existing parent-path index. No count threshold is relaxed: the legacy-missing coverage gate remains unchanged and legitimate missing canonical roots still fail. Historical broad SQL is retained as an analytics-only constant, never executed by the current coverage main. Source **3a567fa** added AST extraction of reviewed fixed SQL constants without importing or executing remote maintenance modules.

Real SQLite regression seeds 3,000 nested message parents and two records in each of the 13 root collections. Scoped counts exactly equal the consumed subset of broad counts, return only those 13 groups, use `SEARCH ... idx_friendbook_cf_firestore_parent` rather than a canonical table scan, and execute less than one tenth of the broad query's SQLite VM work in this fixture. VM steps are NOT production D1 billing. A mocked main verifies the allowlist is bound, old SQL never executes, and missing root coverage still returns failure. **393 Python tests PASS**, including 19 report tests and two new coverage-budget tests; diff check PASS.

This is a diagnostic-script cost correction for future explicitly run coverage checks, not a deployed app fix or proof a currently running loop stopped. The corrected script has NOT been run against production merely to demonstrate savings; no new D1 scan, index rebuild, deployment, APK/AAB, merge, daily-verdict change or proof-date reset was done. Default/other-ref workflow limitations recorded above still apply.
