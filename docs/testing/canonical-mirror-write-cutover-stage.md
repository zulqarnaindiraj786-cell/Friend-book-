# Canonical document mirror retirement — staged status

## Production state already reached

Production `wrangler.toml` is in `DOCUMENT_MIRROR_MODE=canonical`. The guarded deployment applied migration `0104_retire_legacy_derived_index_triggers.sql`, removing source-0 mirror-derived trigger families for chat membership, post-search and follows while retaining canonical source-1 triggers. Before that cutover, the full bounded inventory sampled 11,565 documents (46,260 reported D1 rows read), found zero legacy-only rows, and was not truncated. Migration 0104 is applied remotely.

The Worker D1 wrapper already suppressed legacy mirror INSERT/REPLACE/UPDATE statements. The next staged change strengthens canonical mode: legacy mirror SELECT/WITH queries now fail closed (so stale mirror content cannot authorize or shadow canonical state), and mirror DELETE statements are skipped. Canonical DML remains in its original atomic batches. The default noncanonical mode remains compatibility behavior. The physical `friendbook_cf_documents` table is still retained and is not yet safe to DROP.

A deployment preflight transfers any historical view totals from D1 into an immutable, separate KV key and verifies the write before deployment. The live view-request path has no D1 read/write fallback. A second workflow guard skips repeated full canonical inventory scans only after migration 0104 is no longer pending; it still runs the full scan before the one-time cutover.

## Local regression evidence

`npm test` covers canonical source-1 chat/follow/post-search trigger maintenance, actual follow/unfollow routes, chat-group/message routes, friend-request acceptance with a stale mirror-only friendship, canonical-only friendship candidate SQL, duplicate/race guards, and canonical-mode no-op behavior. Wrapper tests now assert mirror reads fail closed and all mirror DML (including deletes) is suppressed only in canonical mode. Latest complete local result: 340/340 Node tests and 534 Python tests passed; local schema, migration and index-plan tests also passed.

These are local Worker/SQLite tests, not production account interactions or proof of per-device behavior.

## Remaining gate before physical table retirement

Do not add/apply a DROP migration until all of the following are observed after deploying the stronger canonical guard:

1. Sanitized production D1 query telemetry shows no mirror reads or writes over a sufficiently representative post-deploy window; existing report windows are delayed/overlapping and the 07:55 UTC five-minute sample still showed one legacy-mirror read.
2. Follow, chat/group membership, and friend-request create/accept/retry/unfriend scenarios are exercised against isolated, disposable production test accounts with cleanup and no real-user data mutation.
3. The remaining direct legacy-table references are inventoried and shown unreachable in canonical mode; deployment/build diagnostics must not be mistaken for app traffic.
4. A separately reviewed migration removes the table only after those gates, with a rollback/restore plan that cannot resurrect stale friendship or membership state.

Until those gates pass: keep the physical mirror table, do not claim it is fully retired, do not claim production behavior is stale-state-free, and do not merge or drop data based only on aggregate counts.
