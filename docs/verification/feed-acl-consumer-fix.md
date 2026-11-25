# Feed ACL request-local read-budget fix

## Result

Deployed source caad211bb04aebe92c10134250c3023434fb03ed through the no-D1-migration pipeline:
https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45175039976

Provider-reported completion: 2026-11-23T12:31:32Z. Deployment and post-deployment binding/flag/cron preservation, health and auth checks succeeded. No production SQL probe or schema/index change was performed. PR #36 remains unmerged.

## Root cause and fix

A feed page already shared user-profile ACL reads within its HTTP request, but repeated group document, trusted group owner and trusted friendship checks for every post. Concurrent post checks could therefore repeatedly read the same dependencies.

`authorizePost` now shares pending dependency promises through the existing caller-owned request map. User/group documents and trust lookups have distinct keys; post records still receive independent reads and unchanged authorization. Without a request map the original fresh-read behavior remains. No global or TTL authorization cache was introduced.

## Offline evidence

A 20-post fixture in one private group with the same author and friends-only post privacy:

| Dependency | Reference without request map | Shared request map |
|---|---:|---:|
| Group document | 20 | 1 |
| Trusted group owner | 20 | 1 |
| Trusted friendship | 20 | 1 |

This avoids 57 duplicate dependency statements in that fixture, not a claimed production billed-row saving. Existing user-profile sharing and independent post reads are preserved.

New tests verify a new request immediately denies access after group membership, trusted ownership, friendship, block or ban changes. Group storage failures propagate and a later request retries successfully.

Validation: all 249 Worker tests, 372 Python tests and network-disabled 30-user Worker replay pass.

## Honest remaining scope

This fixes an additional source of point-lookup fan-out but does not identify every one of the 1,905 observed point lookups. It does not eliminate legitimate reads/writes, root inbox scans, genuinely unread historical-notification exception paths, or transactional mirror writes. Those must not be disabled merely to meet a quota figure.

No new app installation is required for this backend change. Actual production frequency/savings and a comparable full post-fix UTC day remain unverified. Future strict checks should use the latest rollout boundary rather than an earlier deployment's timestamp.
