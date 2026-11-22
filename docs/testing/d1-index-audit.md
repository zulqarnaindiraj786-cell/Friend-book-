# D1 index audit (migration 0072)

This is a **local**, reproducible audit of the repository migrations, not an assertion about arbitrary deployed schemas, every dynamic SQL statement, or D1 billed rows. Run `python3 scripts/audit_d1_indexes.py` for all 50 local SQLite tables (including FTS and its shadow tables) and their indexes. Migration 0072 brings the local schema to **111 explicitly created indexes**, excluding primary-key/UNIQUE autoindexes. `python3 cloudflare/test/index_plan_test.py` checks selected plans. `QUOTA_QUERY_PLANS=1 node scripts/loadtest/d1_quota_replay.mjs 30 10` shows plans of SELECTs reached by that synthetic, offline Worker replay; it does not cover every feature or branch. SQLite `SCAN` may mean a partial-index traversal, a subquery or a virtual table, not necessarily a full physical table scan.

| Requested area | Actual storage and representative existing access paths |
|---|---|
| users | `friendbook_cf_firestore_documents` / legacy `friendbook_cf_documents`; user email and username expressions, display-name and people-page indexes, primary path index |
| posts | Same document stores; post Feed and author/page expression indexes |
| comments | `friendbook_cf_comments` keyed by resource/id; resource/time page, resource/author and reply indexes; legacy comments in document store have separate post/resource expression indexes |
| likes | `friendbook_cf_reactions` primary key `(resource,uid)` and totals; comment reactions have `(resource,id,uid)` |
| follows | User follow arrays and friendship documents, not a relational `follows` table; friendship participant expression indexes and `friendbook_cf_trusted_friendships` primary/reverse indexes. Arbitrary array membership cannot be covered by an ordinary single-column SQLite index. |
| messages/chats/chat_members | Document stores; nested chat history page index, `friendbook_cf_chat_participants` primary `(participant_uid,chat_path,source)` plus reverse path index. Historical root messages have separate ID/chatId access paths. |
| notifications | Document stores; recipient/time, unread and exception indexes for canonical and legacy formats |
| stories | Document stores; author and expiration indexes; unsupported shapes may still use compatibility paths |
| post_views | Normal view events are Analytics Engine/KV, not a `post_views` D1 table. Legacy D1 view-counter and dedupe tables have resource/identity keys. |

The user-supplied standalone table names and columns do not exist in these migrations; applying their SQL verbatim would fail. An index on every column of every predicate is neither sufficient to eliminate all scans (functions, ORs, unsupported sort/filter combinations, and array membership matter) nor free: every additional index can increase writes and storage. FTS shadow tables use FTS internals; the social guard table is used for constraint checks rather than ordinary indexed lookups. Fallbacks, unusual encodings, admin and compatibility paths remain to be audited; no all-queries/no-scan claim is made.

## Specific change

Migration `0072_root_message_guard_indexes.sql` adds **five partial indexes**, limited to historical root `messages`: three canonical (ID, plain chatId, typed chatId), two legacy (ID, typed chatId). The legacy plain-chatId index already existed. Splitting the original OR guard into equivalent EXISTS checks lets each representation use a seek while preserving atomic orphan-adoption protection and the original prefix test. These indexes do not cover nested `chats/*/messages` and do not add index maintenance to those rows. Building indexes costs quota, and root-message writes will maintain the new indexes.

With 5,000 synthetic unrelated canonical root-message rows in local SQLite, the original orphan check's query plan used the parent-path index and filtered that root collection; the new check used three targeted index searches. On one local run, the original executed 60,015 SQLite VM instructions versus 41 for the new no-match check. Both returned no match. **Those are instructions, not D1 rows read or a production comparison.** Regression tests cover both stores, all three representations, controls, and concurrent guards. No matched production before/after D1 measurement is available; do not report 5,000-to-41 rows.

Indexes can lower some reads but cannot guarantee the Free plan will never exhaust its read, write or request quota, especially as users, writes, indexes, notifications and retries grow. Compare the same query shapes over matched production windows, and review migration/index-build cost and live error rates.
