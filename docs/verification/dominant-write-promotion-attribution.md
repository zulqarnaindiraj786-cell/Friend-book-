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
