# Hybrid interaction backend pilot — DISABLED, not an Android cutover

Agreed target: Firebase Auth login/signup and Firebase chat/calls/presence remain;
likes/reactions and comments/replies eventually use Worker/D1; Cloudinary remains.

## Implemented in this isolated commit

- Authenticated `/v1/engagement/{posts|reels}/{id}/reactions` GET/PUT and
  `/v1/engagement/{posts|reels}/{id}/comments` GET/POST backend pilot.
- Existing Firebase JWT verification runs before dispatch. Actor UID never comes
  from request JSON. An account-switching Android adapter is NOT implemented yet.
- Two gates: `D1_INTERACTIONS_ENABLED` defaults to literal `false`, and each
  resource must have a trusted `verified` migration record. There is no public
  endpoint to set that state. Do not manually mark unimported data verified.
- Optional additive schema only. Deploy never applies SQL. No existing data is
  copied, modified, deleted or pruned; Time Travel and Firebase remain untouched.
- Live Firebase REST source authorization uses the verified user's token, not an
  admin credential: parent existence, archive/trash, bans/deactivation, two-way
  blocks, Only me/Friends privacy and exact-pair friendship. Group resources are
  denied until group ACLs are implemented. Errors fail closed, never grant access.
- This permission check still costs Firebase reads. This pilot is NOT a certified
  quota reduction or a synchronized D1-only authorization system. Cross-database
  reads/writes are not an atomic transaction; concurrency/revocation needs review.
- Reaction PUT sets desired type (or null to remove), never retry-sensitive toggle.
  Transactional SQLite triggers maintain per-type totals; summary reads are bounded
  to six types plus current user's reaction, not a full reaction download/scan.
  Removed reactions retain their new-table row with null type; no DELETE route.
- Text comments use immutable client IDs and server timestamps. Duplicate delivery
  returns the existing row; changed payload/actor is rejected. Parent references
  are resource-scoped. Cursor reads use `(created_at,id)` descending, limit 50.
  JSON bodies are stream-limited to 32 KiB. Author spoofing is rejected.

## Remaining release blockers — do not enable the flag

Historical embedded/canonical reactions and comments must be copied, normalized,
verified and reconciled with ongoing Firebase writes. PR's offline staging tool
is NOT that live migration runner. Production counts have not been measured.
Old app versions still writing Firebase need a defined synchronization/cutover
strategy; do not split authoritative writes between databases.

Android reads/writes and counts/UI still use Firebase: no app adapter or APK
switch is included here. Before enabling, implement and test media-only comments,
mentions/notifications, edit/moderation, comment reactions, pinning, deletion
semantics, blocked-comment-author filtering, user profile hydration and old data
compatibility. This text-only pilot is not feature-complete CommentsEngine.
Live update delivery, offline queue/retry, post/reel count hydration, permission
races, authenticated on-device testing and production rollout/rollback remain.
No periodic polling, dual-write loop or native-D1 real-time guarantee is added.

Tests: 14 Node cases include local SQLite SQL/triggers, Firebase authorization
mock responses, equal-timestamp paging, idempotency/conflicts/spoofing and existing
real RSA token verification. 5 Python migration/schema tests, 73 existing guards
and Wrangler bundle dry-run also pass locally. No production API/data mutation
was performed and no free-tier or device-capacity certification is claimed.
