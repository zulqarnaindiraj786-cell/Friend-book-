# FriendBook quota follow-up — 2026-11-25

## Production deployment findings

- The canonical mirror cutover has already passed its bounded full inventory.
- That inventory was being repeated on every Worker deployment, producing roughly 46k D1 reads per execution despite no data change.
- The deployment workflow now compares the previous revision's `DOCUMENT_MIRROR_MODE` with the current revision and runs the full inventory only for a transition into `canonical`. A failed cutover inventory still blocks migration and deployment.
- The account-wide D1 usage report is delayed/adaptive, not a live billing balance. The sampled 5-minute window recorded 93,984 reads; 92,512 were the repeated canonical inventory shape. This is a diagnostic sample, not a steady-state application baseline.

## Comment candidate lookup

PR #37 replaced the broad OR/CROSS JOIN candidate scan with three route-specific indexed seeks using the already deployed 0087 indexes:
- `idx_cf_canonical_comment_page_post`
- `idx_cf_canonical_comment_page_post_legacy`
- `idx_cf_canonical_comment_page_resource`

The query still covers all three historical field encodings and uses UNION de-duplication. Existing blocked-author, deleted-row, shadowing and epoch invalidation checks remain in place. Worker tests and SQLite query-plan tests passed before merge.

## Safety

- No D1 schema migration or data deletion was introduced by PR #37.
- Production Worker deployment is gated by local schema/migration tests, remote migration inspection, and read-only health checks.
- The full canonical inventory is no longer repeated after cutover.
- Follow-up analytics should compare equivalent traffic windows after deployment; top query-shape subtotals are not complete billing totals.
