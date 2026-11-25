# Inbox ordered-index experiment: rejected unconditional member fanout

## What was executed

`node scripts/loadtest/inbox_order_tradeoff.mjs 1000`

The experiment loads all current migrations into in-memory SQLite, seeds synthetic modern timestamp fixtures, calls the actual Worker inbox query and counts a local callback per visited canonical candidate. It then compares an experimental per-member activity-ordered table with a primary-key seek and checks identical first-page IDs. Candidate update triggers exist only in this in-memory experiment, not in a production migration or app handler. Network calls are disabled.

| Fixture | Rooms | Page | Current candidate callbacks | Ordered callbacks | Baseline local changed rows per canonical timestamp update | Ordered local changed rows | Extra local changed rows |
|---|---:|---:|---:|---:|---:|---:|---:|
| 2 participants | 1,000 | 10 | 1,000 | 10 | 1 | 5 | 4 |
| 50 participants | 1,000 | 10 | 1,000 | 10 | 1 | 101 | 100 |

The ordered plan seeks `uid` in its primary key, seeks canonical `path`, and uses no temporary sort. An update that leaves activity/participants unchanged does not fan out.

## Interpretation and decision

This proves the seeded current query examines the matching candidate set even though it returns a bounded page. It also demonstrates why an unconditional per-member activity mirror is unsafe as a blanket read-quota fix: changing a room's activity deletes/reinserts one row per participant. Group size multiplies writes.

These are **not D1 billed counters**, not a daily traffic model, and not measured production savings. SQLite total_changes omits physical secondary-index maintenance. The experiment measures one canonical row update, not an entire message transaction or mirror write; its additional-row figures must not be presented as full per-message billing. Experimental triggers support only these seeded modern timestamp fixtures and are not production ACL/encoding-complete.

Decision: **do not deploy unconditional per-member fanout**. No production schema, index, trigger or user row was changed. This settles rejection of this candidate, not completion of the remaining inbox-scan work. A global activity index is not automatically sufficient either: it must not scan every other user's chats before finding one user's rooms, and its added write maintenance needs evidence.

## Attribution and validation

The sanitized analytics report now tags the current CTE as `inbox_activity_sort`, separately from generic `chat_membership`. This can distinguish the current page-sort shape in provider analytics without SQL literals, UIDs or document data. It does not create device-level attribution or retroactively explain old queries.

263 Worker tests and 377 Python tests pass. New regression tests assert result parity, seek plan, fanout counts, unchanged-activity behavior and fixture bounds. Existing runtime remains f3c4109, successful rollout 45179710512. Daily acceptance still requires a complete measured post-rollout day below 100k reads / 5k writes; it has not passed merely because an experiment ran.
