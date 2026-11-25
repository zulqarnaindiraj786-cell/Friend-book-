# Canonical document mirror retirement — staged status

## Production state already reached

Production `wrangler.toml` is in `DOCUMENT_MIRROR_MODE=canonical`. The guarded deployment applied migration `0104_retire_legacy_derived_index_triggers.sql`, removing source-0 mirror-derived trigger families for chat membership, post-search and follows while retaining canonical source-1 triggers. Before that cutover, the full bounded inventory sampled 11,565 documents (46,260 reported D1 rows read), found zero legacy-only rows, and was not truncated. Migration 0104 is applied remotely.

The Worker D1 wrapper already suppressed legacy mirror INSERT/REPLACE/UPDATE statements. The next staged change strengthens canonical mode: legacy mirror SELECT/WITH queries now fail closed (so stale mirror content cannot authorize or shadow canonical state), and mirror DELETE statements are skipped. Canonical DML remains in its original atomic batches. The default noncanonical mode remains compatibility behavior. The physical `friendbook_cf_documents` table is still retained and is not yet safe to DROP.

A deployment preflight transfers any historical view totals from D1 into an immutable, separate KV key and verifies the write before deployment. The live view-request path has no D1 read/write fallback. A second workflow guard skips repeated full canonical inventory scans only after migration 0104 is no longer pending; it still runs the full scan before the one-time cutover.

## Local regression evidence

`npm test` covers canonical source-1 chat/follow/post-search trigger maintenance, actual follow/unfollow routes, chat-group/message routes, friend-request acceptance with a stale mirror-only friendship, canonical-only friendship candidate SQL, duplicate/race guards, and canonical-mode no-op behavior. Wrapper tests now assert mirror reads fail closed and all mirror DML (including deletes) is suppressed only in canonical mode. Latest complete local result: 340/340 Node tests and 534 Python tests passed; local schema, migration and index-plan tests also passed.

These are local Worker/SQLite tests, not production account interactions or proof of per-device behavior.

## Production verification and remaining gate

The stronger canonical guard was deployed successfully by workflow run `45573010735` (successful retry), including the read-only Worker/D1 connectivity check. A manual analytics-only audit `45576914707` observed the 5-minute window 08:25:55–08:30:55 UTC: 11 query records, 41,897 D1 rows read and 1,508 written. Its reported top-priority shapes were canonical post operations; no legacy-mirror query appeared among those shapes. This output is aggregated and shape-prioritized, not a complete zero-query certificate, and the interval is short and may not represent normal account/device usage. An earlier overlapping window reported one legacy-mirror SELECT during the deployment interval. Longer windows include pre-deployment traffic and must not be attributed solely to the new Worker.

A full, bounded, read-only inventory (workflow `45578093389`) sampled all 11,564 mirror rows with no truncation and found zero paths missing from the canonical store. A raw comparison found 10,491 rows with differing `owner_uid` or serialized `document_json`. A follow-up privacy-preserving semantic comparison (workflow `45578431192`) normalized Firestore wire values into the Worker document shape and found 258 semantic mismatches (84 chats, 34 comments, 8 lives, 2 reels, 130 users) plus 214 owner mismatches (4 chats, 1 notification, 209 users). The normalization is not yet proven exhaustive for every stored value type, but the mismatches are real unresolved evidence, not a green parity check. Path coverage alone is not enough to authorize dropping the mirror; reconcile these counts and establish rollback before removal.

Before physical table retirement, all of the following remain required:

1. A sufficiently representative post-deploy telemetry period with no legacy-mirror reads or writes across the complete available query set, not merely the top-ranked annotations.
2. Follow/unfollow, chat/group membership, and friend-request create/accept/retry/unfriend scenarios exercised using isolated disposable production test accounts, with cleanup and no real-user data mutation. No such account/device smoke suite has yet been run.
3. A full caller/rollback review. Static app references still include compatibility-mode SQL templates, while canonical mode routes through `canonicalDocumentWriteEnv` and the D1 prepare/batch wrapper; compatibility mode still depends on the mirror. The source scan found no Worker `DB.exec()`/`DB.dump()` bypass, but static review and local route tests are not proof that every production caller is exercised.
4. A separately reviewed DROP migration and tested restore plan that cannot resurrect stale friendship or membership state.

Until these gates pass, retain the physical mirror table and compatibility rollback path. Do not claim the mirror is fully retired or production behavior is stale-state-free, and do not drop data based only on aggregate counts.