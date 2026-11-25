# Isolated Cloudflare quota reporter — deployed, not an auto-fixer

## Verified deployment

Worker: **friendbook-quota-monitor** (separate from application Worker rough-firefly-dd7b).

[Successful deployment and live report verification, run 45120921972](https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45120921972)

Source: `a51375267ecb0693cc25c11f57626060db8a3691`.

- 240 Worker regression tests and 358 Python tests PASS.
- Application Worker and isolated monitor both compile successfully.
- Only the monitor was uploaded/deployed; application code/data/schedules unchanged.
- Remote configuration verified: exactly two encrypted secret bindings; no D1, R2, KV, Durable Object, or application-service binding.
- Remote schedule verified: exactly `*/15 * * * *` for this monitor. The application's existing 15-minute maintenance schedule was not changed.
- Public `/health` returned the expected healthy observer response.
- Unauthenticated `/report` returned 401; authenticated `/report` returned a validated aggregate report.
- PR #36 remains OPEN/unmerged. No Android rebuild or Play publication.

Health endpoint: https://friendbook-quota-monitor.zulqarnaindiraj786.workers.dev/health

The health endpoint does not expose account totals. `/report` requires the separate encrypted report credential, which is randomly generated/rotated during deployment and never stored in Git, printed in logs, or included in a report artifact.

## Live report, not a quota-success claim

Provider-returned report time: **2026-11-23T05:57:26.510Z**.

| Field | Result |
|---|---|
| Role | observe_only |
| Acceptance target (not a verified plan limit) | 100,000 reads / 5,000 writes per UTC day |
| Daily account reads | 470,485 |
| Daily account writes | 6,383 |
| Status | **TARGET_EXCEEDED** |
| Recent database query window | 05:42:26.510–05:57:26.510 UTC |
| Recent query dataset | 3 reads / 0 writes / 3 executions |
| Automatic fix actions | **none** |
| Future quota guarantee | **false** |

The recent window is low-activity, not a controlled app session or savings proof. Daily account and recent database-query datasets have different scopes, are delayed/adaptive, and cannot be equated or subtracted from each other. An empty group stays unavailable, not zero. A current day below target remains UNVERIFIED rather than proof of compliance.

## What runs automatically

The scheduled handler is configured to run every 15 minutes and emit a structured `quota_monitor_report` console event to Cloudflare Workers Logs (observability enabled). This is aggregate logging, **not** a push/email notification or a persistent report archive; availability/retention follows the account's Workers Logs configuration.

The schedule was verified through Cloudflare metadata. The live report was verified through authenticated HTTP; a separately observed cron invocation was not captured in this deployment proof.

The handler makes one fixed GraphQL request combining account daily and database recent aggregates. It executes **no D1 SQL**, no diagnostic table scan, and no application request. It does not consume D1 row reads/writes. It still uses ordinary Workers invocation/CPU, API and logging quotas (nominally 96 scheduled runs/day plus manual requests); it is not free of every platform cost.

Concurrent reports coalesce in the same isolate; authorized manual reports reuse an isolate-local result for at most 60 seconds. There is no cross-isolate cache or storage binding. Unauthenticated requests do not trigger analytics.

## Security and limitations

The monitor does **not** rewrite source, deploy app changes, add/drop indexes, mutate documents, upgrade billing or stop user traffic. A Worker is code executing fixed rules, not a general AI that can independently guarantee a quota fix. It cannot truthfully be described as an autonomous quota repairer.

`CF_ANALYTICS_TOKEN` and `REPORT_TOKEN` are encrypted runtime secrets. Deployment prefers a dedicated `CLOUDFLARE_ANALYTICS_READ_TOKEN` repository secret and otherwise uses the existing connected Cloudflare deployment credential. The fallback credential may have broader account permissions: its scopes were **not** proven read-only. The implemented runtime code only calls the fixed GraphQL endpoint; use a dedicated least-privilege analytics token for stronger capability isolation. No credential values appear in this document or source.

Initial deployment attempts uploaded the monitor but failed endpoint smoke checks (including HTTP403). Subsequent diagnostics preserved failures, verified remote bindings/schedules, and corrected smoke-test HTTP identity consistently. Latest run above passes health, private access and authenticated analytics checks; tests and authorization were not bypassed.

Configuration: `wrangler.quota-monitor.toml`.
Runtime: `cloudflare/quota-monitor.js`.
Tests: `cloudflare/test/quota-monitor.test.js`.
Deployment: `.github/workflows/deploy-quota-monitor.yml`, only on the current branch with the explicit deployment marker. Never use the application's deployment workflow for this observer.
