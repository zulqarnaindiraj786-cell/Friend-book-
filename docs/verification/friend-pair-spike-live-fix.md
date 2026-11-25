# Live incident fix: exact friendship candidate probes

Source **6dfdebb8ff96c6f899774133740d620016afd3bb**. Production rollout **45297036141 SUCCESS**.

## Problem tied to the reported spike

The post-reset 15-minute analytics window showed a friends compound-selection shape at 14 executions / 2,800 reads / 2,772 returned rows. Source showed small exact-pair suggestion checks reaching the generic full actor-graph selection. This established a wasteful access path, not device/HTTP-level attribution of every reported execution.

## Fix deployed

For participants `in` or `==` filters consisting entirely of bounded two-string arrays, the server now deduplicates input pairs and removes pairs not containing the authenticated actor. A bounded JSON table feeds exact participant-0 AND participant-1 equality lookups into canonical storage. The unchanged projection, final filters, ordering/limit and ACL pipeline determines output. Longer/malformed/other filter shapes retain the existing behavior; no new mirror fallback or UNION path is introduced. Duplicate stored friendship rows remain observable so the client's saturation safeguards keep working.

Installed **only** `0092_friend_exact_pair_seek.sql`, one additive friends-only composite expression index. Existing indexes, constraints, triggers and data remain. It adds index maintenance on friendship row changes, not on unrelated posts/chats/notifications. This is a narrow migration/index rollout, unlike the previous code-only rollout; the full pending migration chain was not executed.

The existing Android 178 already sends the exact-pair query shape. No replacement APK is required for this server fix.

## Verification

- **298 Worker / 428 Python passed** before deployment.
- Schema 2 / migration 3 / index-plan 4 tests passed; 48-workload quota replay completed successfully.
- New actual Worker test seeds 5,000 actor relationships plus duplicate, third-party and long-array records. Exact query returns the three matching exact-array friendships while materializing only four candidates (the extra long-array candidate is rejected by the unchanged evaluator).
- EXPLAIN asserts `SEARCH f USING INDEX idx_cf_friend_exact_pair (<expr>=? AND <expr>=?)`, not merely a smaller returned result. Local candidate counts/EXPLAIN are not D1 billed-row savings measurements.
- Normal graph query remains available; unauthorized third-party pair yields no records; scalar exact-pair equality and duplicate inputs covered.
- Rollout reran full regressions, recorded rollback/config snapshot, dry-run compiled, created the one index, executed a live EXPLAIN command, deployed Worker, and passed resource-preservation/health/auth smoke verification.
- No APK/AAB build, merge or Play upload.

This fixes the identified exact-pair full-graph access path. Ordinary full friend-list requests can still legitimately read the graph; this does not certify all fourteen historical executions as fixed callers. Notification-write attribution and the unreconciled screenshot/API totals remain open. Total quota and representative-day <=100k reads/<=5k writes acceptance are **not yet proven**.
