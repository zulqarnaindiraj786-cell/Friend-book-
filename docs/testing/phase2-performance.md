# v2.8.25 / 57 — Phase 2 implementation and validation limits

## Delivered source
- Existing feed stays at 15 posts/page, stories 20/page.
- Canonical comments latest 15 live; explicit older 15 cursor pages. One bounded last-comment preview; new writes never expand commentsMap. Canonical edits/reactions retain offline queued batches. Promoting frozen legacy-only records still requires a network transaction to avoid overwriting newer canonical data.
- Open-chat message listener latest 15, explicit older 15 one-shot pages. Inbox one-shot 15, explicit refresh and more.
- Reels metadata 5 native + 5 video posts per batch (filters can produce fewer), explicit/near-end next batch. Linked target fetched independently. No offscreen full-video prefetch; playback requires a tap and visible selection. Cloudinary thumbnails request 480px/WebP. Other thumbnail providers cannot be guaranteed 480px server-side.
- Watch metadata 10/page. Feed video posters no longer prefetch full media.
- Bulk mention backend v2 deployed: canonical comment lookup first, frozen legacy fallback only if missing; persisted ownership/tags, relationship checks and daily reservation retained.

## Checks
- Main/test Java syntax parsing, Python regression guards, backend mocked-provider tests.
- `scripts/loadtest/paging_model.py`: 5,000 cooperative virtual sessions / 120,000 in-memory pages, no duplicates or missing records. See `paging-model-5000.json`.
- CI additionally runs the actual Java PageWindow policy through 5,000 parallel model sessions, all existing release unit tests, R8 and signed APK/AAB build. Check CI result before claiming this passed.

**These simulations send ZERO actual backend requests. They do not certify 5,000 real or concurrent users, delivery, media bandwidth, 2G responsiveness, latency, offline SDK behavior or free-tier capacity.** No production traffic flood, billing change, data migration or deletion was executed.

## Remaining release/scale caveats
1. MainActivity still has its global room-metadata listener for aggregate unread badges and incoming calls. Message-history payload listeners are bounded, but not all chat metadata listeners. A secure per-user server summary migration is required before claiming fully bounded chat infrastructure.
2. Inbox/video-post pages use default document-ID order to avoid requiring an undeployed composite index. Loaded items are ranked/sorted client-side; the first page is NOT guaranteed globally newest. A deployed index + ordered-query rollout is needed for that guarantee.
3. Existing parent legacy maps are still downloaded once for compatibility; they are not magically shrunk by pagination. An admin-only, dry-run-default migration utility is supplied at `scripts/admin/migrate_comments_v2.py`, not executed. Explicit apply requires a private backup. Pruning is a separate opt-in after old clients stop writing; update-time preconditions prevent pruning changed parents. Resume via the reported last collection IDs. Verify permissions/copies and backups independently before running.
4. Historical comments/messages beyond the live head are one-shot snapshots; remote edits/deletions outside that head need reopening/refreshing. Pin metadata is one-shot. Parent last-comment previews can lag edits/reactions; deleting a comment clears that preview until another comment arrives. Device validation is still required for recycling, deep links, viewport anchoring, audio and receipts.
5. A real 5k-capacity assessment needs authorized staging traffic, realistic media/concurrency, Firebase quotas/rules/indexes, RTDB/FCM behavior and budget verification. Unlimited lifetime-free hosting is not guaranteed.
