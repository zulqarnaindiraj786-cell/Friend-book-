# Canonical document write-mode stage

Migration `0104_retire_legacy_derived_index_triggers.sql` removes only the source-0 trigger families for chat-member, post-search, and user-follow derived indexes. The source-1 canonical triggers remain. An opt-in Worker binding mode `DOCUMENT_MIRROR_MODE=canonical` is also added. It wraps the D1 binding for a request and suppresses only `INSERT`/`REPLACE`/`UPDATE` statements whose target is exactly `friendbook_cf_documents`. Canonical DML remains in the same D1 batches; mirror `DELETE` statements and all reads are left unchanged. Default behavior remains dual-write.

## Offline proof

Targeted Node tests verify direct/batched DML suppression, Worker friend-request create/acceptance and duplicate protection, actual follow/unfollow and chat-group/message writes, canonical source-1 chat/follow/post-search trigger maintenance, and the absence of canonical-mode mirror reads on literal retries. Source-0 rows are absent in those fixtures. The complete Worker suite passes with `npm test`.

## Not a production cutover

The production Wrangler config now selects canonical mode, but the deploy workflow gates every such deployment on a full bounded inventory (`--require-no-legacy-only`) and refuses truncated or legacy-only coverage before applying migration 0104 or deploying the Worker. Preview Wrangler remains in dual-write compatibility mode. No production deployment has been run from these local changes. Friendship accept/unfriend guards use canonical-only candidate SQL in production mode; the default compatibility mode keeps legacy candidate rows. The inventory gate is point-in-time; concurrent old Workers still use atomic canonical+mirror writes until the new Worker replaces them. Mirror deletes are intentionally preserved for stale-row cleanup. This is a guarded cutover stage; the legacy table itself is not dropped.

Before enabling: finish an exhaustive write/read caller audit (including legacy-only compatibility behavior), backfill and verify every required collection, test create/update/delete and retry/race paths for follows/chats/friend requests, and ensure rollback can restore dual-write without resurrecting stale data. Only then should a guarded deployment and production behavior verification be considered. No production database or Worker was changed by this stage.
