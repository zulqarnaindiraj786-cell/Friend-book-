# Firebase → D1 migration: normalized STAGING import, gates OFF

## Current verified status

Owner-authorized GitHub Actions now uses repository secrets (never chat/source).
Raw interaction snapshot backup passed in run `41941688251`. Normalized **staging**
import/read-back passed in run `41944815062` (commit `94ae2fa`). These supersede
older offline-only status statements. This is NOT a completed live migration.

- 949 raw typed snapshot documents remain in the original per-batch archive.
- Application staging contains 83 resources, 407 reactions, 271 comments/details,
  68 comment reactions, plus explicit timestamp provenance. No pins were present.
- 84 additional preservation rows retain original resource documents/counters and
  the orphan reaction. No Firebase mutations, resets, deletion or Time Travel.
- All resources remain `staging`; **zero** are marked `verified`. Both switches
  remain OFF. Firebase Auth/realtime and Cloudinary remain unchanged.
- Source counter discrepancies and one orphan remain unresolved for LIVE cutover.
  Snapshot-time reconciliation does not account for later/old-client writes.

See [current staging evidence and remaining gates](interaction-staging-status.md).
The sections below describe the original offline tools and safety checklist;
raw staging alone never substitutes for an application import or live-write fence.

## What is included

- Optional `0002_additive_copy_staging.sql`: a new staging table keyed by
  `(batch_id, path)`, separate from application tables. Deploy does not apply it.
- `scripts/admin/prepare_d1_copy.py`: local-only preparation from Firestore REST
  Document JSONL. Full names must target project `friendbook-17551`, `(default)`.
  Source must include nested subcollections, not only users/posts/chat parents.
- Every original REST Document is retained as canonical JSON: document IDs,
  metadata, typed integers, timestamps, references, nested maps/arrays and
  Cloudinary URLs are not flattened/coerced. This is staging, not a relational
  query model or a production API backend.
- Import SQL uses INSERT only, never REPLACE/UPDATE/DELETE. Existing rows are not
  overwritten; repeating the same batch fails on duplicate keys. Different batches
  coexist. Partial imports require diagnosis; verification rejects missing rows.
  Never remove old batches just to make verification pass.
- Comparison requires equal document identities, complete canonical content,
  SHA-256 and collection-path counts. Equal totals with different records fail.
  Reports always retain `safe_to_cutover=false`: matching supplied files does not
  prove an independently complete/live Firebase snapshot or a live D1 copy.
- Large documents exceeding the offline SQL statement budget fail before creating
  outputs. Do not truncate them; a separate reviewed large-document importer is
  required. This in-memory tool is not a tested large-production exporter.

## Offline usage (after a complete authorized export exists)

Keep private exports/artifacts in ignored `migration-artifacts/`, never Git,
`docs/`, Worker assets or CI attachments. No production export is in this PR.
Input is one Firestore REST Document per line, not managed-export binary files
or plain SDK `data()` JSON. Empty/duplicate/wrong-project documents are rejected.

```
python3 scripts/admin/prepare_d1_copy.py prepare \
  --source migration-artifacts/source.jsonl \
  --output migration-artifacts/batch-1 \
  --batch 550e8400-e29b-41d4-a716-446655440000
```

This only creates `copy.sql` and a manifest locally. It does not create a remote
table, import data, make network calls or alter either production database.
After a separately authorized import, export the batch's `batch_id`, `path`,
`document_json`, `sha256` rows as JSON (plain array or single-query Wrangler JSON):

```
python3 scripts/admin/prepare_d1_copy.py verify \
  --source migration-artifacts/source.jsonl \
  --destination migration-artifacts/d1-rows.json \
  --batch 550e8400-e29b-41d4-a716-446655440000
```

Exit 0 verifies only those supplied files. Console output contains no user IDs,
records or secrets. The private manifest includes collection-path counts.
Never publish it: nested paths may contain private chat/user IDs.

## Required before real migration / app switch

1. Establish an authorized execution environment with Firebase read access and
   access to the existing D1 database. Preserve credentials in a secure secret
   store, not source/chat. Revoke previously exposed Cloudflare credentials.
2. Inventory all collections and nested collections, including orphaned-parent
   subcollections, legacy fields and dynamically constructed paths. Auth accounts
   stay in Firebase Auth; `users` profile documents are a separate dataset.
   Initial code audit found 82 Java files mentioning Firestore/Fb.db APIs;
   presence/calls/notifications also require separate RTDB/FCM dependency review.
3. Obtain an independently counted, complete snapshot/backup. While the app keeps
   writing Firebase, define a change journal, replay watermark and reconciliation
   strategy; a one-pass export is not a safe live cutover. No paid resource is
   authorized by this PR. Managed exports may require billing; do not enable it.
4. Import into additive staging, verify paths/content/counts against authoritative
   source counts and the final change watermark; preserve old data on both sides.
5. Design normalized D1 queries/indexes and per-user authorization for profiles,
   privacy/block/friend graph, posts, chats/messages, replies, counters, notifications,
   stories, groups and moderator actions. Staging JSON is not a replacement for
   current Firestore queries. Authenticated access is not permission to read all data.
6. Implement/test Worker APIs plus event delivery, receipts, pagination, offline
   writes, idempotent mutations, attachment URLs and deletion semantics. Existing
   `/v1/me` alone cannot support the Android app. Keep Firebase Auth and Cloudinary.
7. Add opt-in Android adapters, shadow-compare reads and test old/new accounts,
   concurrent writes, nested messages, retries and permissions before release.
   No global database URL substitution; live listeners need explicit replacements.
8. Only after evidence/sign-off: staged rollout with rollback and post-cutover
   reconciliation. Never prune/reset Firebase, D1 data, backups or Time Travel.

Tests use synthetic records/local SQLite only, not production users or capacity
certification. Merge/deploy of this PR does NOT start a migration or switch users.
