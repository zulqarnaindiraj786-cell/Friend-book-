# User-authorized quota deployment

The user explicitly requested a production deploy to reduce quota after reviewing that the changes were not live. The authorized source is the current `arena/b52bc74d-friend-book` branch, including the one-minute viewer-local feed reuse and notification-retention state-seek optimization. This note records authorization only; it does not claim a deployment or migration succeeded.

The existing guarded Cloudflare deployment workflow remains the only rollout path. Its tests, D1 migration inventory, index-build quota preflight, resource/config checks, migration verification and post-deploy health/auth checks must pass. Canonical mirror retirement/archive safeguards remain unchanged; no mirror reconciliation, destructive migration, PR merge, or Android release is authorized by this note. If any preflight fails, stop and report it rather than bypassing the gate.
