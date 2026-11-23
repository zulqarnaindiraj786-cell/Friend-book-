# App-wide consumer audit: maintenance, Live PK and other databases

## Confirmed bugs fixed

### Scheduled maintenance

- The retired `friendbook_cf_view_dedupe` schema has only resource/uid/bucket. Scheduled cleanup referenced nonexistent `created_at`. Runtime view counters use Analytics Engine, not that historical table. Removed the invalid DELETE; historical rows remain intact. This is not claimed as a quantified D1 billed-row saving: failed SQL is not evidence of a large billed scan.
- Isolated synchronous prepare failures as well as asynchronous rejections so one maintenance task cannot prevent independent outbox work.
- Replaced nonportable UPDATE ... LIMIT syntax with a primary-key update over a limited SELECT of at most 20 expired live rooms. This is exercised against the real complete migration schema, not merely a source regex.
- Stale rooms still expire, fresh rooms and historical view rows remain unchanged, and broadcast retries still execute when the live task fails. The */15 cron is unchanged. No new maintenance loop, index or table was introduced.

### Live PK duplicate point reads

- Reuse the request's already-authorized viewer profile instead of rereading it after the Worker account gate.
- Room-action authorization already fetched both host profiles; reuse those fields for host names instead of two additional canonical reads. This removes three repeated profile lookups from the prior room-request path, not a measured daily billing total.
- Extend request-local room/profile reuse to status, invite and accept authorization. Invite/accept reuse the same canonical room snapshots already protected by transaction guards; invitation actor names reuse the checked actor fields.
- Tests count all canonical room/profile query variants, not just one SELECT spelling. Next-request name changes, kicks and host bans remain fresh. Status viewer/host profiles are checked once per request. No cross-request authorization cache was added.

## Other databases and current telemetry

The quota report now groups account query analytics by database ID with no primary-database filter. Invalid, unavailable or empty observations are explicitly not zero. It emits compact cross-window totals before detailed shapes because CI annotation limits could previously hide later windows.

Analytics run https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45185510075 SUCCESS, window end 2026-11-23T13:36:01Z:

| Window | Main DB reads | Main DB writes | Observed other DB reads/writes | Observed databases |
|---|---:|---:|---:|---:|
| 5 minutes | 8 | 0 | 0 / 0 | 1 |
| 15 minutes | 437 | 0 | 0 / 0 | 1 |
| 60 minutes | 14,914 | 194 | 0 / 0 | 1 |

No additional active D1 database was observed in these returned groups; the result was not truncated. This does not rule out earlier-day, delayed, intermittent or future other-database traffic. Quiet activity is not a savings experiment or a completed-day acceptance pass. Sixty minutes spans prior versions, index probes and deployment health checks. SQL counts cannot identify a particular installed device or user action.

## Validation and live rollout

- **275 Worker / 385 Python tests PASS**; schema/migration/query-plan suites and deployment replay/compile gates PASS.
- Maintenance source aa3e795: rollout 45185095296 SUCCESS, completion 2026-11-23T13:33:58Z.
- Final source b151612: https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45185924496 SUCCESS, completion **2026-11-23T13:39:12Z**.
- Exact production compile, code-only deploy, resource/binding/cron preservation and health/auth smoke checks PASS. No D1 migration, Android change, PR merge or Play upload.

The daily acceptance boundary is now 2026-11-23T13:39:12Z. The last strict daily check remains FAIL (636,015 reads / 14,160 writes for the reported partial UTC day before this rollout); it has not been reset, subtracted or relabeled as passing. Full app-wide <=100k reads / <=5k writes acceptance still requires a completed measured post-fix day with representative usage. This audit closes the confirmed maintenance and PK duplicate-read defects; it does not certify every code path or unknown traffic source as solved.
