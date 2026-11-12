# Firebase = authentication only. Everything else lives on Cloudflare.

## The three product rules (owner's acceptance criteria)

1. **New data goes to Cloudflare.** Every write this build makes - posts, reels, comments, stories,
   notifications, friend requests, friendships, chat messages, profile edits - travels to Cloudflare
   D1 through the Worker (`PUT /v1/docs/...`) together with the legacy write. Views never touch the
   legacy store at all: they are Analytics Engine events counted with `count(DISTINCT blob3)`.
2. **A user who never updates stays on the old app, and nothing of theirs is deleted.** The legacy
   store keeps receiving writes (dual-write) and keeps serving old builds. No code path in this
   migration deletes, resets, prunes or rewrites legacy documents; `CloudMigration` contains no
   write to the legacy store at all (guards enforce both).
3. **A user who updates gets their history carried over automatically.** On the first launch of a
   newer build, `CloudMigration.runOnceAfterUpdate` mirrors that user's own slices (own profile,
   posts, reels, stories, comments, notifications, friend requests, friendships, chats plus a bounded
   tail of each chat's messages) to D1 - once per versionCode, bounded to 100 documents per slice,
   additive merge-upserts only.

Reads are **Cloudflare-first with the legacy store as backfill**: a page is the union of the legacy
listener and `GET /v1/docs?collection=...`; a Cloudflare document only ever *joins* the page, never
replaces or removes a legacy one, so old-build writes stay visible to everyone during the overlap.

## Where each thing lives today (this line)

| Data | Legacy store | Cloudflare home | Status |
| --- | --- | --- | --- |
| Sign-in, ID token | Firebase Auth | — (stays) | **permanent** |
| View counts | never stored | Analytics Engine (`count(DISTINCT blob3)`) | **live** |
| posts / reels / stories / comments / notifications / friend_requests / friends / chats / messages / users | source of truth for old builds | D1 `friendbook_cf_documents` | **dual-write live** + self-sync on update + union reads for posts & reels |
| videos / photos | unused on this line | R2 via Worker endpoints | Worker live; client switch = next batch |
| likes / reactions | legacy increments | D1 interaction tables (`/v1/engagement`) | Worker live; client gate `D1_INTERACTIONS_ENABLED=false` until the interaction batch |
| payments / subscriptions / referrals / reports / config | legacy | not mirrored yet | reviewed batch, separately |

## Honesty notes

- Dual-write is deliberate and temporary: it is what keeps rule 2 true (old builds keep working) while
  rule 1 holds (new data lives on Cloudflare). Removing the legacy write is a later, explicit cutover
  per collection, never a silent flip.
- The mirror is additive only: merge-upsert on the Worker, no DELETE anywhere, 95 KiB document cap,
  fire-and-forget so a Worker outage can never block posting; the next edit re-mirrors the document.
- Guards pin all three rules: `test_update_carries_existing_user_data_to_cloudflare_without_touching_the_old_copy`,
  `test_every_new_write_travels_to_cloudflare_with_the_legacy_write`,
  `test_reads_are_cloudflare_first_with_the_legacy_store_as_backfill`.
- The Play Store line (`com.friendbook.social`) receives this through the controlled port described in
  `docs/handoff-social-line-port.md` - same rules, same guards, monotonic versionCode, same signing.
