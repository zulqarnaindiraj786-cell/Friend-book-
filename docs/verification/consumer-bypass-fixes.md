# Initial-list and profile-engagement bypass fixes

## Source changes

- `2498afb`: CursorPager initial cache-first full heads now use DEFAULT on a miss or expired page, joining existing exact-query operation sharing. Explicit full refresh (maxAge=0) remains independent SERVER. DEFAULT still requests network; this is not a new cache TTL. Applies to initial on-demand/day/short-cache posts and users lists. Avoided whole list operations also avoid their ACL and bundled-engagement work, but this does not establish production duplication frequency.
- `4c1cfdc`: ProfileActivity already requests `withFeedEngagement`, seeds D1Reactions, and hydrates models, but its FeedAdapter did not enable bundled mode. Set that flag to prevent bind-time per-card summary GETs, particularly after cached seeds have no fetch timestamp or after the eight-second summary freshness window. Mutation-driven invalidation remains unchanged. Other unbundled surfaces retain the normal summary-loading path.

## Verification status

Python 422 passed; Worker 293 passed; 48-workload quota replay completed successfully; diff check passed. Added Robolectric coverage for initial DEFAULT versus explicit SERVER routing in posts/users and retained short-cache reuse/expiry coverage. Profile bundled-mode change has a source wiring guard, not a device-level network trace.

First combined Android run 45288192016 failed **before compilation/tests** while downloading Robolectric jars (curl exit 22). The preceding 2498afb-only run was superseded/cancelled by concurrency. `gh run rerun --failed` rejected rerun; this documentation push requests a fresh normal workflow run. Neither result is a passing compile. Update this record after that run resolves.

No APK/AAB, deployment, migration, or merge. Standalone point-read and engagement request prevalence and representative-day <=100k reads/<=5k writes acceptance remain unverified. These fixes close identified duplicate paths, not all genuine reads or a 100% quota guarantee.
