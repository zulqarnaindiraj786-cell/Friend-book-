# Canonical-only document read cutover

Base: `b9338ed5856722b7d601f9253745003f71a1c172`.

## Completed

- Converted trusted-group, follow/unfollow, friendship reconfirmation and administrator snapshot-review fixtures end to end. Historical source rows and concurrent source mutations use canonical storage. Mirror-write failure tests still exercise transactional rollback.
- `comment-api.js` reads historical root comments from `friendbook_cf_firestore_documents`, including indexed routes, counts, single reads, promotion snapshots, collision checks and reactor lookups. Normalized interaction comments remain in `friendbook_cf_comments` as before.
- Removed legacy document read/merge/shadow arms from `firestore-documents.js`: point reads, collection candidates, indexed pages, search, snapshot guards, recipient enumeration and root-message bridging. Root-message compatibility reads now use backfilled canonical root messages, not the retired mirror source.
- Added migration `0087_canonical_comment_page_indexes.sql`: three additive canonical comment route/time indexes. It preserves data and can be applied repeatedly. Missing-index compatibility uses canonical storage too.
- Added explicit mirror-only profile/comment/story/chat negative cases, and updated row budgets and query-plan assertions to the single canonical stream. No Worker tests were skipped or removed.

## Verification

Use Node 22 (the repository tests use `node:sqlite`; the sandbox's default Node 20 does not provide that API).

- `npm test`: **234 passed, 0 failed, 0 skipped**.
- `python3 -m scripts.audit_canonical_read_arms`: **0** legacy source read arms in each of `app-documents.js`, `comment-api.js`, and `firestore-documents.js`.
- `python3 -m unittest discover -s cloudflare/test -p '*_test.py'`: **9 passed**.
- `python3 -m unittest discover -s scripts/tests -p 'test_*.py'`: **309 passed**, including the cutover audit and repeatable-index migration regression tests.
- `python3 -m scripts.loadtest.five_user_budget`: **43,630 modeled reads/day**, **1,875 modeled writes/day**, 96 cron runs/day, both target flags true.
- `git diff --check`: clean.

The budget script and its activity/cost assumptions were not changed in this cutover. Its current output is 43,630 reads, rather than the handoff's 44,110. This is an offline assumption-based model, not measured production billing or a capacity guarantee.

## Audit boundaries and retained behavior

“0 arms” means zero legacy **document-source fallback** reads in the three cutover modules. It does not mean deleting every SQL UNION or NOT EXISTS: canonical participant/rank/message/comment-route branches and atomic canonical absence checks remain necessary.

Mirror INSERT/UPDATE/DELETE maintenance and rollback checks remain. The write no-op decision in `unchangedRootState` retains one exact mirror equality probe so a missing/stale mirror can be repaired; it never supplies a returned document. The audit makes that one exception explicit. Friendship duplicate-candidate helpers outside the three cutover modules retain their defensive duplicate semantics.

This work is local code plus verification, not a production deployment. Apply the additive comment-index migration before deploying the Worker for bounded indexed comment routes. No production migration, deployment, database mutation, or Android release was performed here.
