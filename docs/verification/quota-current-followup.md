# Current quota follow-up and preview over-fetch fix

## Fresh remote evidence

Source commit 2c4cadf; analytics-only traffic run 45168599024 succeeded. Strict daily target run 45168598989 failed its acceptance check; this must not be described as quota solved.

The runtime-reported query timestamp is 2026-11-23T11:49:42Z. These timestamps come from CI/provider output, not a claim about the reader's current local date.

- Account daily cumulative: 570,579 reads / 9,630 writes, already above project target 100,000 / 5,000. Includes pre-fix activity and administration; cannot establish current application attribution.
- Query analytics 10:49:23–11:49:23Z: 12,885 reads / 370 writes, 4,128 executions; 52 observed shapes, no 60-shape cap reached. This is query analytics, NOT account billing.
- Largest read shape: comments/reactions aggregate, 502 executions / 3,502 reads.
- Next comment/canonical-document shape: 260 executions / 1,880 reads.
- Canonical path lookup: 1,828 executions / 1,828 reads.
- Recent 15 minutes: 1,354 query-analytics reads / 0 writes. A quiet interval is not full-day acceptance.
- Attribution remains application OR unattributed, not confirmed user/device traffic. No claim of polling or current APK installation.

Artifact download failed at the restricted external storage host. The same sanitized JSON notices were obtained from the GitHub check-run annotations API; no secret output was retrieved.

## Newly fixed source inefficiency

`engagementSummary` displays only two historical comment previews but previously requested the standard 21-item historical comment page. Now it explicitly requests two. `legacyPage` validates an internal page size in [1,21], with default 21 unchanged for full comment pages. Each indexed normal route fetches at most the requested size. The exception bucket and compatibility scan remain intact, so unusual legacy encodings retain compatibility and no new silent truncation is introduced.

The compatibility path still scans matching historical data when required for ordering correctness; no claim that this fixes all scan cost. This change does not remove any authorization check, total count or index. It adds no TTL cache or schema migration.

Regression coverage extends the existing mixed-encoding/blocked-author/promoted-row/cursor-tie fixture: both indexed and compatibility size-two results must equal the first two entries of the old standard page; invalid size fails. Full comment pages remain 21.

Local validation: all 241 Worker tests and 368 Python tests pass. These are correctness tests, not billed D1 savings. Actual savings depend on historical comments being present and the indexed path being used.

## Deployment boundary

The preview optimization is source-only pending validated deployment. Existing broad production deployment workflow applies pending migrations and does not allow this branch. Do not expand that workflow or replay migrations to ship this small optimization. A separate deployment must preserve current bindings/vars, capture rollback version, and apply zero database migrations. No backend deployment was performed by this follow-up.

The separate signed Android 2.10.34/176 build (45166501055) succeeded and contains engagement GET coalescing, but installation on user devices remains unknown. PR #36 remains unmerged.

## Production rollout completed

Source 23bb6cceee754abdeb964631fb86c2b13c0af6b8 was deployed by the dedicated code-only pipeline:
https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45169771972

All steps succeeded; CI job completed at provider-reported 2026-11-23T11:57:55Z. Use this conservative timestamp as the start of the latest deployed optimization, not the earlier index-only boundary.

- 241 Worker tests / 370 Python tests, schema/migration/index-plan local tests and 30-user network-disabled replay passed.
- Metadata preflight verified exact live resource binding names/types, D1 ID, R2 bucket, Analytics Engine dataset, Durable Object classes, reviewed public vars and */15 cron before deployment.
- Previous deployment version metadata recorded in `quota-code-rollout-evidence` artifact for rollback.
- Wrangler code/assets deployment succeeded with --keep-vars. No D1 migration command or table/index/data mutation was run by rollout tooling. Normal application traffic/maintenance remains active; health executes a constant SELECT 1.
- Post-deployment binding identity comparison and public-var/cron checks passed.
- Health returned 200 with D1 enabled and Analytics Engine bound; unauthenticated /v1/me returned 401.
- PR #36 remains open/unmerged. No new Android release was needed for this server-only optimization.

The source-only deployment warning above describes the earlier checkpoint and is superseded by this section. Full authenticated production comment behavior is covered by local tests, not by an authenticated live-user smoke test. Post-deployment billed savings and a full-day target pass are still unverified; prior strict daily FAIL remains historical evidence and must not be erased.
