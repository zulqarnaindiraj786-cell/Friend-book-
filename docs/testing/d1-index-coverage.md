# D1 feature/index coverage

The Android `collection(...)` paths are not SQL table names. D1 stores most application documents in `friendbook_cf_firestore_documents` with an existing historical mirror in `friendbook_cf_documents`. Creating unrelated empty `users`, `posts`, or `sessions` tables would not speed up the app, and copying into them would introduce a new source of truth. Migration 0038 is additive and uses the existing tables.

| Requested area | Actual storage and index coverage |
| --- | --- |
| Users: email, username, phone, created | 0038 adds canonical profile indexes for all four. Lookup inputs and API authorization are unchanged; no public search by email/phone was added. |
| Posts/media: author, created, visibility, media | Author lookup (0026) and actual feed ordering (0029) are indexed. Visibility is a per-viewer authorization decision (privacy, blocks, friendship, group roles), not a public SQL category. Media URLs/metadata are embedded; there is no separate `post_media` lookup. |
| Likes/reactions | `friendbook_cf_reactions` primary key `(resource,uid)` covers both posts and reels; per-type totals use `(resource,type)` in 0003. |
| Comments and replies | Resource/time/id pagination (0003), legacy resource lookups (0032), parent and author indexes (0038). The parent index also supports the comment table’s self-referencing foreign key. |
| Friends, requests, follows, blocks | Trusted sorted pair primary key (0019), participant indexes (0027), and directional request/status lookups (0023/0026). Followers and blocks are profile arrays, not normalized pair tables; array membership indexes require a transactional projection, not a standalone index. |
| Stories and views | Author/expiry indexes (0038). Existing story views are document `viewedBy` state; no independent `story_views` table. |
| Reels/video | Author lookup (0026), canonical author/time and time indexes (0038), shared reaction key (0003). Post/reel/video view events use Analytics Engine, not SQLite view-row indexes. Canonical `create_time` indexes do not change compatibility ordering for legacy timestamps. |
| Messages / chat | Conversation history pagination (0028/0029), legacy message prefix lookup (0024), sender index (0038). Room membership remains in the authoritative room document; a reverse participant ledger is not implemented. |
| Notifications | Recipient indexes (0025), unread-partial recipient indexes (0036). Exact inbox order is still applied by the compatibility query engine, not an indexed SQL sort. |
| Groups / pages | Group-post lookup and page-owner lookup for canonical and legacy rows (0038). Group-member access uses room/group membership arrays and trusted ownership, not a `group_members` table. |
| Search / hashtags / mentions | No live normalized hashtag/join-table or mention search index. Current mentions are stored in documents/comment details; search is not switched to a new schema. |
| Saved, reports, settings, sessions | Saved membership is `savedBy` on the source document; reporter/post indexes added (0038). Settings are profile fields read by the profile path primary key. Historical login records use `users/{uid}/sessions` and the existing `parent_path` index. Firebase Auth session tokens are not stored or indexed in D1. |

`cloudflare/test/index_plan_test.py` applies every migration to throwaway SQLite and asserts the selected group, story, old page and reply query plans. API tests cover notification privacy, read state and canonical precedence.

Admin payment approval can atomically approve an existing pending paid subscription and activate its linked subscription with a server-set expiry. It does not create payments or claim to verify an external charge. Official broadcast fanout and independent normalized relationship/search tables are outside this migration.
