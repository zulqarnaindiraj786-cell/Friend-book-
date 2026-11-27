# Owner-supplied APK180 one-minute trace: first interpretation

## Observed, not inferred

Owner supplied Version 2.10.38 (180), STOPPED, elapsedMs=60000, Attempts=45, completionsInWindow=45, errorsInWindow=0. All displayed responses are HTTP200. The line wrapping `status=2` / `00` is 200, not an error status.

Reconciled totals:

| HTTP operation family | Attempts |
|---|---:|
| POST firestore/query | 22 |
| GET firestore/document | 10 |
| GET firestore/count | 2 |
| GET unclassified `other` | 6 |
| PUT unclassified `other` | 1 |
| POST firestore/batch | 1 |
| PUT firestore/document | 1 |
| POST views | 2 |
| **Total** | **45** |

22 query attempts comprise MainActivity13, PostDetail1, Profile5 (including one labelledBG), Conversations2, Chat1. Ten document GETs comprise PostDetail7 (one BG), Chat3. Six `other` GETs comprise PostDetail5, Main1. There are two BG-labelled starts total, adjacent at ~22627/22628ms during the PostDetail/Profile transition; subsequent Profile starts are FG at ~22630ms.

MainActivity has grouped requests around 7.6s and 46.5s, rather than a stable subsecond repeating cadence throughout the minute. The sequence also contains activity transitions, chat history/read-state work, views and user-action writes. Without query shapes/values and action timeline, repeated route labels do not establish duplicate identical requests. 45 successful attempts does NOT establish no loop anywhere, and does NOT attribute the owner's historical quota exhaustion.

## What this trace cannot answer

- It does not report billed D1 reads/writes. One query/count may scan far more rows than it returns. 45 attempts cannot be substituted for 45 rows, nor can 45 account for the owner's quota without per-route row attribution.
- Screen is the latest resumed Activity, not guaranteed caller. Transient BG during activity handover is not proof of background sync or a leaked screen. No background traffic claim is made from those two labels.
- The exact engagement route was missing from the classifier: real `/v1/engagement/{posts|reels}/{id}/{kind}` was `other`. We cannot retroactively assign each historical `other` event to comments, reactors, reactions or another endpoint.
- All query bodies were previously deliberately excluded, so the 22 POST queries cannot be split into collections/limits or compared for exact semantic equality.
- APK180 did not record the absolute session start, preventing reliable billing-window correlation from the report alone.

## Diagnostic correction (APK181 source)

- Adds exact engagement route shapes with IDs replaced by `:id`.
- During opt-in capture only, extracts allowlisted collection root and bounded numeric page limit from small, replayable query JSON bodies. No filter values, IDs, cursors, raw JSON, authorization headers or full URLs are saved. Document/count paths expose only allowlisted root; nested user/chat IDs are omitted.
- Unknown/malformed/oversized/one-shot bodies omit context; metadata extraction failures do not replace or prevent the HTTP operation.
- Records capture-start UTC milliseconds from device clock so the window can be aligned to server billing metrics (device clock skew still possible).
- Actual POST body shapes may still differ despite identical root/limit. This metadata is not an exact-request-duplication proof.
- No product demand logic, backend query, deployment, privacy checks or retry rules changed based solely on this insufficient evidence. No calculator changes.

Next reproducible trace should isolate Home for one minute (no navigation, no pull-refresh, one controlled scroll) before a second mixed-feature session. Record whether a like/comment/share was tapped. Use the absolute start/elapsed window to correlate with billed rows, and rank actual query roots before further product/SQL changes. Do not request credentials or copy message/filter contents into diagnostics.

## APK181 verification/delivery

- Source `e81fa0af1b43ecf70056381a407669adabcc2963`; initial context/classification `95d13ae`; opt-in-only extraction `9b22e36`. Earlier intermediate runs superseded, not delivery evidence.
- 444 Python tests PASS.
- Android compile/JVM (including context sanitization, real dialog click and trace buffer tests) SUCCESS: https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45327685944
- Signed release build/JVM/signature/test-result gates SUCCESS: https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45327685816
- Signed APK181 / 2.10.39 prerelease, not Latest: https://github.com/zulqarnaindiraj786-cell/Friend-book-/releases/tag/friendbook-2.10.39-signed-e81fa0af
- Download: https://github.com/zulqarnaindiraj786-cell/Friend-book-/releases/download/friendbook-2.10.39-signed-e81fa0af/friendbook.apk
- 4,980,775 bytes. GitHub asset metadata SHA256 `4d22dfe640438e5bd63ae99bf2067377dc904b4bd4381d4945fb56c6c1866be9` (not claimed local-file hash).
- No backend deployment/PR merge/Play upload/auto-update. Diagnostics-only follow-up does not itself establish savings.
- Cold-start traffic before the user enters Settings and starts capture is not covered; in-progress traces do not survive process death. These remain explicit blind spots, not proof cold startup is safe.
