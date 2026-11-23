# Remaining consumer attribution audit

Analytics-only run 45176401220 succeeded, source c14b38d. Provider-reported measurement end: 2026-11-23T12:39:42Z. These are rolling database-wide query analytics, not exclusive device telemetry, account billing or matched-session savings.

## New evidence

Returned-row counts are now collected rather than ranking costs without result volume.

- 60-minute chat-membership shape: 93 executions, 11,265 reads, 5,586 returned rows (~2.02 reads per returned room). This is consistent with membership-index traversal plus canonical room lookup, not proof of 185 unrelated rows scanned for one result. The existing local EXPLAIN plan already starts with covering `idx_cf_chat_participant_source(participant_uid,source)` and then seeks canonical `path`; changing JOIN to CROSS JOIN produced the same plan. No speculative join-order change was made.
- This does NOT close the remaining pagination issue: the compatibility inbox path still reads and sorts all matching rooms before applying the requested page. More efficient ordered seeking requires a separately tested design with write-cost tradeoffs; no arbitrary SQL LIMIT can safely replace it because ordering is derived from last-message activity and authorization must precede pagination.
- 60-minute writes: observed top shape subtotals include 1,665 mirror-insert writes / 185 executions and 1,456 snapshot-guarded canonical insert writes / 208 executions, plus a second mirror upsert shape at 351 writes / 39 executions. The same source statement family can serve several document types; no per-document or caller identity is available here. It would be false to claim every mirror write is an avoidable duplicate.
- Latest 15-minute query totals: 1,658 reads / zero writes. Latest five minutes: 447 reads / zero writes. Windows are not matched before/after experiments; five minutes straddles the latest code rollout. Quiet activity does not establish savings or a completed-day acceptance pass.

## Attribution tooling corrections

Multiline SQL previously caused `on conflict` token detection to miss some upserts. Whitespace normalization now fixes that reporting bug; absence of the old feature label must not be taken as proof of a plain insert.

Large GitHub notice annotations were truncated, hiding the tail of write-shape evidence. The report now emits a compact window summary and one bounded sanitized notice per shape. Full JSON output remains in the report artifact. Tests ensure each notice is below 4,000 characters and contains no SQL literal/private value.

Validation: all 375 Python tests pass. This follow-up did not change or deploy app code, run production SQL, remove mirrors, add/drop indexes, or merge PR #36. Full quota target remains unverified; pending compatibility inbox pagination and write-family caller attribution are explicitly not claimed resolved.
