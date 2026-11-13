# Android D1 integration / v2.8.33 (65) — BOTH SWITCHES OFF

Current status: raw backup and normalized STAGING import/read-back have passed
through owner-authorized GitHub Actions. Additive schemas 0003–0006 were applied
for that staging import. No live activation, Firebase rule deployment, Time Travel,
Firebase data writes or deletion was performed. Both switches remain OFF and zero
resources are marked verified. Firebase Auth, chats/calls/presence and Cloudinary
are unchanged. See [staging evidence and remaining gates](interaction-staging-status.md).

## Android paths wired

- `D1Client`: bounded serialized HTTPS requests with Firebase ID tokens, timeouts,
  bounded bodies/responses, no redirects, actor-change rejection and no automatic
  Firebase fallback/retry. No token or server credential is stored in the APK.
- `CommentsEngine`: D1 initial/latest50, older cursor50, lifecycle/actor guards,
  send/reply/media/mentions, edits, comment reactions, pins and soft removal.
  Closed handles ignore callbacks. No Firebase comment listener/writes on D1 path.
- Private account/resource-scoped unsent drafts are saved before POST, excluded
  from Android backup/device transfer, and retried with the SAME comment ID via
  the comments screen's Unsent button. No background write/retry loop. A timeout
  is not proof the server rejected a write; same-ID retry prevents duplicates.
- Posts and both native/post-backed Reels route reaction reads/writes to D1.
  Scoped bounded cache, desired-state PUT, optimistic UI/rollback, bounded reactor
  pages30, existing profile/tick rendering and preserved Firebase notifications.
  Unsupported/failed D1 calls never silently write to Firebase.
- D1 summary hydrates counts/last-two previews instead of frozen Firebase maps;
  source privacy/bans/two-way blocks (including blockedUsers), groups and role
  checks are revalidated server-side. Comment list excludes blocked authors.
- Bulk mentions use persisted D1 source when selected, with no legacy-source
  fallback; the Supabase bulk-mentions function is now ACTIVE v3 with D1 source support;
  authenticated enabled-device notification tests still precede activation.

Frontend API/storage support includes additive optional 0004 details schema,
server-verified author snapshots, resource-scoped replies, authenticated edits,
owner pins, own/owner/moderator soft removal, and reactor cursors. Existing raw
comment rows remain stored after soft removal. Deploy never applies migrations.

The D1 branch does not emulate a cross-device Firestore snapshot listener. Pages
refresh on entry/reopen; local successful actions update active comment handles.
Unsent retry is explicit, not an unlimited automatic offline synchronization
service. Large histories, network/device behavior and production source-policy
races still require pre-activation testing; this is not live/capacity certification.

## Gate and test rules

Android `BuildConfig.D1_INTERACTIONS_ENABLED=false`; Worker variable also literal
`false`. Turning on only one is insufficient; Worker requires a verified resource
marker too. Do NOT use that marker to bypass a missing/incomplete migration.
The signed OFF build keeps existing Firebase behavior. Android routing tests use
mocked static routing/transport, not real production credentials or D1 users.

Tests cover disabled transport before Firebase init, DTO/mentions/media/replies,
server counts/own reaction, lifecycle/changed actor suppression, all comment
HTTP actions with zero Firestore calls, failure without fallback and private
unsent draft account isolation. Worker tests exercise actual local SQLite SQL,
transactional triggers and permissions with mocked source responses.

## Later manual migration — no secrets in chat

1. Arrange an owner-authorized, read-only complete source export/backup, including
   posts/reels reaction subcollections, embedded reaction maps/likedBy and canonical,
   embedded, root-mirror comments/replies. Firebase Auth accounts are not moved.
   Do not mistake copying a few Console rows for a complete export. A complete
   recursive export needs an authorized SDK/runner; this PR does not supply a
   mobile-only production exporter. Do not enable billing or change rules casually.
2. Keep private exports outside Git/website assets (`migration-artifacts/`). Use
   the offline preparer/verifier from `firestore-d1-migration.md` for raw staging.
   That SQL is NOT a normalized likes/comments import into application tables.
3. On an isolated/local database first, apply the optional numbered schema once
   via the authorized D1 Console/migration tool. Preserve all existing tables,
   backups and Time Travel. Never DROP/DELETE/reset/import over the live database.
4. Normalize per resource (`posts/ID`, `reels/ID`) without changing original IDs;
   deduplicate mirrors, prioritize canonical/current records, preserve media,
   mentions, timestamps, pins/replies/author snapshots. Preserve original REST
   envelopes in staging. Legacy Reel likedBy defaults to love; post defaults may
   differ. Do not invent missing users/reactions to match an inconsistent counter.
   Orphan reply parents or invalid legacy IDs require explicit reconciliation,
   not silent dropping, truncation or guessed replacement IDs.
5. Import parent resources in staging state, canonical rows and detail records;
   normalize active reactions BEFORE accepting trigger-derived totals. Compare
   every ID/content/hash and authoritative normalized totals, not raw duplicated
   mirror counts. Include empty, private, blocked, deleted and reply cases.
6. Account for writes made during export and old app versions. Arrange a reviewed
   final write fence/journal, replay and verification watermark while retaining
   login/chat/presence. Do not enable two independent authoritative writers.
7. Only after those checks and an independent backup: record verified resources,
   test a separate enabled Android/Worker build against isolated fixtures, deploy
   required backend/functions and stage rollout. An OFF APK does not become ON
   just because the Dashboard variable changes; a new reviewed Android build is
   required. Verify permissions, count stability, retries, media, mentions, groups,
   moderator actions, app restart/account switch and rollback before real users.
8. Keep Firebase source data and both backups. Rollback is not simply toggling a
   flag after new D1 writes; reconcile new writes first or they would be hidden.

No secrets need to be sent to this conversation. The normalized staging counts/content have been verified, but live source counter
discrepancies and post-snapshot writes remain unresolved; no live cutover is claimed.
