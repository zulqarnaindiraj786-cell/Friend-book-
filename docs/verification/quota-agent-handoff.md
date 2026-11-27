# FriendBook quota investigation — safe agent handoff

## Task
Only about five real users are reported, but database usage is high. Identify current app/backend causes using evidence, then implement and verify safe optimizations. Do not assume five users means five requests or that every historical query remains active.

Repository: zulqarnaindiraj786-cell/Friend-book-
Current branch: arena/b52bc74d-friend-book; PR #36 must remain unmerged.
Main Worker: rough-firefly-dd7b
D1: friendbook-db (e1c605ca-a50b-46c6-9deb-56dd1f70d163)
Observer: friendbook-quota-monitor

## Evidence boundaries
The user supplied another agent's historical report alleging 820,579 executions of a UNION ALL path lookup, expensive collection scans, JSON filters, presence writes, and 66 billed writes per legacy upsert. Treat these as investigation leads, NOT independently verified current findings. Obtain original analytics windows, query samples, scope, and deployment versions before relying on them.

Do not infer polling from query counts alone: multiple internal authorization queries can occur per HTTP request. Do not attribute all Worker errors to quotas without actual error codes/logs. Do not assume the account plan from usage alone.

The project's strict efficiency acceptance target is 100,000 D1 rows read and 5,000 D1 rows written per day. These are project targets, NOT asserted Cloudflare provider limits. Confirm actual current plan and official provider limits separately. Do not substitute Workers request limits for D1 row-read limits.

## Already implemented — inspect before changing
- Canonical-only reads are required. No legacy fallback / UNION ALL. A mirror-only document must return 404, not 403.
- Structural view/trigger table-swap Option #2 was permanently rejected. Do not revive it or drop/backfill user tables as a quota fix.
- Production migrations 0088, 0089, 0090 were applied outside the migration tracker. Do NOT run a broad migration sweep.
- 0090 replaced a global timestamp index with root-chat-only partial index idx_cf_chat_root_updated(parent_path, update_time DESC), restricted to collection_id='chats' AND parent_path='chats'. Chat ordering and live plans were validated.
- An isolated real D1 billing probe demonstrated non-chat writes 3 -> 2, root-chat writes 3 -> 3 for that tested pattern. This does NOT prove a whole-app reduction or daily quota compliance.
- App document reads already have coalescing/on-demand protections. New engagement bodyless GET coalescing shares only simultaneous same-account, exact-URL, mutation-epoch requests; no TTL, write replay or cross-account sharing. Each consumer parses independent JSON. Preserve mutation fences and 401/account guards.
- Engagement source commit 59a4017; CI dependency prefetch fix f50697b. Android unit suite passed: https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45123259509 . Python suite: 365 passed at this checkpoint. These changes have NOT been released in a new APK.
- Backend evidence: docs/verification/chat-timestamp-quota-fix.md; quota-target-proof.md; live-quota-diagnosis.md; quota-monitor.md. Read their timestamps and caveats. Do not claim fresh production verification from historical reports.

## Investigation sequence
1. Read current source and verification docs. List installed app version, deployed Worker version, and source branch separately. Old installed APKs can keep old request behavior.
2. Start with existing low-cost analytics/observer data. Attribute current reads/writes by normalized query shape and time window, splitting app traffic from admin probes, migrations, tests and diagnostics. Avoid repeated full-table audits: prior diagnostics themselves consumed much of measured reads.
3. For each top CURRENT shape, trace the actual route -> SQL -> Android caller. Count network requests separately from SQL executions. Check duplicate subscriptions, lifecycle teardown, retries/backoff, pagination, overlapping reads and write-on-read paths.
4. Reproduce using sanitized local fixtures and EXPLAIN QUERY PLAN before proposing indexes. Focus on measured current bottlenecks, not automatically executing the pasted historical recommendations.
5. Implement the smallest safe fixes. Add regression tests for security, account switching, failures, pagination completeness, concurrency and mutation freshness. Preserve chat, feed, auth, live, push, ordering and realtime behavior.
6. Validate actual billed D1 rows where justified with a bounded, explicitly costed probe; distinguish rows returned, query-plan estimates and opcode counts from billed reads/writes.
7. Run Worker/Python tests and Android CI as appropriate. Report exact run URLs and failures. Release APK/AAB only through the established signed pipeline when needed; source changes do not affect installed devices by themselves.
8. Measure a complete comparable post-deployment UTC day before claiming the daily target is met. Report activity levels, diagnostic costs, missing/truncated analytics and unresolved causes. Never manufacture savings percentages.

## Unsafe shortcuts to avoid
- Do not blindly add covering indexes containing document_json: large payload indexes can increase storage/write costs; path primary-key lookups may already be efficient.
- Do not drop ACL/guard/trigger indexes or replace transactional authorization checks with non-atomic application checks. Preserve atomicity and race safety.
- Do not assume DB.batch reduces billed row writes; batching primarily changes round trips/transaction behavior.
- Do not assume 66 writes/upsert means 66 indexes; triggers, side effects and query grouping require inspection.
- Do not treat SELECT * removal or ETags/304 as automatic billed-row/request savings.
- Do not use eventually consistent KV as the authoritative idempotency/security store for push claims, authorization or financial state.
- Do not use module-memory buffering as durable storage for counters/receipts. Workers isolates are ephemeral.
- Do not add shared private-data caches without account/ACL isolation and invalidation tests.
- No arbitrary LIMIT that silently hides authorized data; pagination must preserve completeness and ordering.
- Typing/presence features are explicitly unwanted: do not reintroduce them or merely slow their heartbeat as a proposed feature.
- Keep cron */15. Observer is a monitor, NOT an AI agent or auto-fixer.
- No paid-plan upgrade, destructive schema action, merge, auth weakening or full-table production sweep as a shortcut.
- Never request passwords/tokens in chat; use configured integration secrets. No claim of contacting 'Cloudflare Agent Lee' unless an actual supported handoff exists.

## Required output
Provide a ranked table: current query fingerprint | caller/route | observation window | executions | billed reads/writes | diagnostic vs app attribution | evidence | proposed fix.
For each implemented fix: files/commit, tests, deployment status, bounded before/after proof and remaining risks.
End with explicit status: source fixed / tests green / deployed / installed on devices / full-day quota verified. Mark unknown items unknown. Do not say DONE without evidence.
