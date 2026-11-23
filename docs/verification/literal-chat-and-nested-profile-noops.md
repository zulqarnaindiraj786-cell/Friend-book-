# Identical direct-chat and nested-profile saves

## Identified avoidable work

Unlike scalar profile no-ops, identical nested literal maps/arrays were excluded from the no-op eligibility check. Identical direct-room PUT merges and PATCH operations also always issued canonical/mirror writes. This audit establishes the code path; it does not claim these callers generated a specific historical aggregate write total.

## Change

- Literal detection recursively accepts JSON maps/arrays but rejects FieldValue transforms at any depth. Existing exact serialized-document equality, authorization, canonical snapshot/owner checks and mirror consistency checks remain prerequisites.
- Existing canonical direct rooms can return their unchanged revision after an authorized literal retry only when a fresh statement confirms the canonical JSON, revision, owner and mirror contents. Missing/stale mirrors still repair atomically. Group rooms retain their authority-ledger path.
- Server timestamps, increments, unions/removals/deletes and genuine changes remain normal writes. Same-value transforms are deliberately not reclassified as literal retries.

## Regression evidence

261 Worker tests and 376 Python tests PASS locally.

- Five same-value direct-room PATCHes plus a nested participants/unread map PUT merge: zero local row changes, unchanged canonical revision.
- Missing mirror repaired; outsider rejected; canonical revocation between authorization and no-op snapshot check returns failure without changing mirror.
- Same-value increment(0) retains the write path.
- Repeated profile saves including nested preferences/maps/arrays write zero rows; nested increment(0) still writes; real rename advances canonical metadata; unauthorized same-value save remains forbidden.

No new indexes, per-member activity fanout, database migrations or Android changes. These tests measure actual local SQLite changes, not D1 billing savings. Production rollout and daily acceptance require separate evidence.

## Scope still not certified

The membership-first inbox sort still reads matching candidates. Eliminating that scan with a per-member ordered activity index would add message-time index/row writes; that tradeoff has not been demonstrated to satisfy the user's 5k daily write target. No speculative activity-index migration has been applied. Old mirror aggregates cannot be retrospectively mapped to a caller, and quiet analytics cannot certify the daily target.
