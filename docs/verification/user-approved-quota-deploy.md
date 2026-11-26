# User-authorized quota deployment

The user explicitly requested a production deploy to reduce quota and then authorized proceeding with the safe work needed. The authorized source is the current `arena/b52bc74d-friend-book` branch, including the one-minute viewer-local feed reuse and notification-retention state-seek optimization. This note records authorization only; it does not claim a deployment or migration succeeded.

The user has now also approved the specific reversible archive step for the reappeared mirror. Migration 0109 renames the live mirror table to an archive while preserving its rows, indexes, triggers and columns; it does not delete or reconcile the discrepant records. The guarded preflight and quota-budget checks remain mandatory. No archive will run if any safety gate fails.

The existing guarded Cloudflare deployment workflow remains the only rollout path. Its tests, D1 migration inventory, index-build quota preflight, resource/config checks, migration verification and post-deploy health/auth checks must pass. Canonical mirror retirement/archive safeguards remain unchanged; no mirror reconciliation, destructive migration, PR merge, or Android release is authorized by this note. If any preflight fails, stop and report it rather than bypassing the gate.

## Attempt history and successful guarded rollout

The first authorized attempt, [45783277593](https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45783277593), stopped safely because migration 0109's explicit archive authorization had not yet been supplied. No migration or Worker deployment occurred in that attempt.

After the user explicitly approved the reversible archive, the guarded rollout [45785006150](https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45785006150) **succeeded**. Local tests, quota/index preflight, resource/config checks and authentication passed. Migrations 0109 and 0110 both applied. The old table was renamed and all 336 rows were preserved in `friendbook_cf_documents_reappeared_archive_0109`; the 11,564-row rollback snapshot and original schema remain. The post-migration verifier confirmed the application mirror table absent and the archive/snapshot present. Worker version `82b149fb-0e6d-463b-b486-c14e3b6923bd` deployed; `/health` returned 200 with D1 and Analytics Engine enabled, and unauthenticated API access returned 401. No merge or Android release was performed.

A post-deploy analytics-only query report [45785216567](https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45785216567) included deployment activity: 5-minute window 2,668 reads / 18 writes; 15-minute 14,864 / 1,206; 60-minute 507,974 / 5,278. These are delayed, overlapping, database-wide query analytics, not a matched before/after user session. The 60-minute window still includes the earlier full mirror scans; no savings percentage or completed-day quota pass is claimed. The account-wide daily total observed just before deployment was 1,709,730 reads / 34,717 writes, already above the 100k/5k project target.

The Android-local one-minute feed cache is not live because no Android build/release was performed. Continue to avoid full mirror diff scans: three scans in one recent hour accounted for 333,163 reads. Their workflow now requires two separate explicit confirmations.
