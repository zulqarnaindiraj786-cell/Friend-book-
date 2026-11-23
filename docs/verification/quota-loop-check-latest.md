# Fresh quota-loop investigation

## Analytics-only observations

No application traffic, D1 SQL, schema change, Worker deployment, APK/AAB build or merge was performed in this investigation.

Recent report: https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45192871151 SUCCESS. Windows ended 2026-11-23T14:19:07Z:

| Lookback | Executions | Reads | Writes |
|---|---:|---:|---:|
| 5 minutes | 9 | 10 | 0 |
| 15 minutes | 23 | 20 | 7 |
| 60 minutes | 807 | 936 | 7 |

Highest read shape in that hour: canonical point SELECT, 378 executions / 372 reads. Engagement-summary SELECT: 63 executions / 315 reads. These delayed/adaptive windows do not show a continuously high-volume quota drain at their observed traffic level, but cannot exclude intermittent or future activity.

Prior-24h report: https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45193036265 SUCCESS. Window 2026-11-22T14:20:05Z through 2026-11-23T14:20:05Z: **122,107 executions / 1,096,793 reads / 63,068 writes**.

Largest read shapes:
- Canonical-document SELECT: **9 executions / 169,534 reads / 11,798 returned rows**. High row cost per statement; this number alone does not establish an automatic request loop.
- SELECT referencing canonical and mirror stores: **112 executions / 151,719 reads / 112 returned rows**.

Largest write shapes:
- INSERT referencing canonical and mirror stores with conflict handling: **5,078 executions / 45,581 writes / 16,088 reads / 5,078 returned rows** (about 72% of all observed window writes).
- Mirror-store INSERT: **380 executions / 3,420 writes**.

The 24h shape lists are TOP-60/TRUNCATED, not an exhaustive consumer inventory. Its account database split is not truncated and observes only primary D1; other observed database subtotals are 0/0. It includes historical code, engineering/replay/probe/index work and normal activity. SQL shape does not identify device, endpoint, intent, or exact application version. Do not label all historical mirror writes as a current app loop, subtract diagnostic subtotals from account billing, or assume earlier fixes resolved every execution represented here.

## Code tracing and limits

Checked live/call client renewal triggers and backend renewal gates. They deliberately perform fresh account/room authorization during active media; these are recurring necessary security reads, not a timer-based idle polling leak. The inspected UI disconnected handlers finish/end the session, rather than automatically reconnecting forever. The PK room Runnable inspected here is posted on connection readiness, not self-reposted in the locations examined. This review is not certification of live/call protocol correctness or real-device behavior.

Earlier confirmed live join/leave snapshot replay amplification remains fixed in Android source, but unshipped to devices because the owner paused signed builds. Preserve that distinction when diagnosing installed old clients.

Added bounded analytics --window-minutes support (1..1440, deduplicated), retaining 5/15/60 defaults; this run explicitly requested 1440 to inspect the spike instead of inferring from quiet traffic. Invalid >1440 input was verified to fail before network access. Existing 17 report tests and full **388 Python tests PASS**. No Worker source was changed in this investigation; previous 283-Worker-test green state remains applicable.

This check confirms the historic spike and narrows its dominant shapes; it does not establish a new fix, device attribution, ongoing idle loop, completed UTC day within target, or 100% quota completion. The strict daily FAIL and backend fix boundary remain unchanged.
