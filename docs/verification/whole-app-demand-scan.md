# Whole Android source demand scan and comment-ACK read fix

## Scope and reproducibility

`python3 scripts/audit_android_demand.py --output docs/testing/android-demand-inventory.json`

Scans all209 Java files and1 Kotlin file under the application package. Kotlin SoundManager was also read manually; it delegates local sound actions rather than network reads. The JSON records source locations only, with no user content. Candidate line counts:

| Category | Matching source lines |
|---|---:|
| Document-store entry |440|
| Engagement transport |9|
| Snapshot listener |46|
| Explicit SERVER read |8|
| Count helper |5|
| Cache-first list |12|
| Delayed/periodic candidate |51|
| Durable unique work |3|
| Raw HTTP candidate |18|

Counts overlap and represent lexical review leads, NOT executions, network requests, unique paths, billed rows or identified defects. Comments are excluded only conservatively; this is not an AST/call-graph or exhaustive dynamic-path proof. The existing direct-list heuristic was refreshed: zero direct-chain candidates, explicitly not a boundedness certificate for variable-built/helper queries.

## Manually traced demand paths

- Home/FeedAdapter: verified feed engagement avoids per-card fallback loads when engagement is included in the page. Other list/detail surfaces still require summaries when not supplied; do not remove legitimate reads.
- Reels: selected/visible-holder refresh guard remains; cached render does not itself issue HTTP. Playback/watchdog timers are not all database polling.
- CommentsEngine -> D1Reactions: found and fixed unconditional full-summary invalidation after count-neutral comment mutations (details below).
- D1Client: queued account-scoped operation sharing and immutable JSON per consumer remain; mutation invalidation is preserved.
- Document/query listeners and one-shots: preceding verified queued-sharing fixes remain; no new blanket TTL or ACL cache.
- CountReads: bounded transient retries and lifecycle cancellation remain. At scan time AggregateQuery lacked concurrent count-flight sharing. Subsequently fixed and verified in5af886e / Android45311999858; see app-count-demand-sharing.md. Production frequency/ranking remains unproven.
- PushFanoutWorker: unique durable work and stable notification create IDs exist. Friend-post fan-out still performs fresh author/friendship checks and per-recipient writes. Subsequently terminal-error replay was fixed and verified in880b83e / Android45315361460; see app-fanout-terminal-retry.md. Partial fan-out/push replay still needs dedicated measured review; this family is NOT certified fully closed.
- QueryActions: exhaustive SERVER pages are tied to explicit confirmed bulk actions; anyRecent is used by poke gating, not a general app-start loop.
- MainActivity referral refresh: one delayed lifecycle count guarded by actor/loaded state, not a repeating4-second polling loop.
- CloudflareStore.list has no application callsite in the scanned tree; do not count its implementation as observed duplicate traffic. Views/media/live/push transports need their own semantics; URL construction alone is not D1 billing evidence.

## App fix: stop re-requesting a full summary after count-neutral ACKs

Source3468e5f; Android compile/JVM45310458719 SUCCESS.

Before: CommentsEngine.d1Changed unconditionally called D1Reactions.invalidate after every successful comment edit, comment reaction or pin. With a tracked post, invalidation requested a full engagement summary even though the authenticated ACK already contained the edited/reacted row, and pin does not change preview ordering or totals.

After: mutation operation reaches D1Reactions.commentChanged. Valid edit/react ACKs update the matching cached preview row and tracked post without a summary GET. Pin ACKs do not invalidate unrelated summary state. No comment ACK renews the age of post reaction totals. Create/remove, malformed acknowledgements and uncertain/concurrent post-reaction cases retain their existing invalidation behavior. Account checks remain at mutation completion. No fake counter arithmetic, fallback write, TTL increase or server/index change.

## Verification and limits

- Actual CommentsEngine edit/react/pin path:30 of each operation with a seeded tracked post ->0 follow-up reaction-summary GETs; edited preview text and post totals preserved; original summary age unchanged.
- A subsequent delete still requests the required authoritative summary refresh. Tests do not replace count-changing consistency with cached guesses.
- Android compile/full JVM suite45310458719 SUCCESS on application source3468e5f. Laterc1e7e24 changes inventory tooling/tests only.
- Full434 Python tests PASS after inventory test addition; diff check PASS.
- Inventory tool has a test demonstrating that a local media timer is merely a candidate, not a quota/network classification.

No new APK, Worker rollout, production SQL/index change or PR merge in this turn. APK178 lacks this client fix. Controlled request-demand proof is not installed-device savings or representative-day quota acceptance. Whole-tree inventory is complete for the stated files; whole-app runtime correctness and <=100kreads/<=5kwrites/day are NOT certified.
