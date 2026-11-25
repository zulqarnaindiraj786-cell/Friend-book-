# User-reported new APK session — query analytics consumers

User reports installing the new APK and using it for five minutes. No device/session ID was instrumented, so the results below are database-wide observations, not exclusive attribution to that phone.

Analytics-only run: https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45172622102 (success).
Provider/CI reported window end: 2026-11-23T12:15:59Z. Adaptive/delayed query analytics; not account billing and not exact real-time measurements.

## Latest five minutes: 12:10:59–12:15:59Z

Total query-analytics rows read 6,102; rows written 514; SQL executions 2,648. SQL executions are not HTTP requests.

| Observed read shape | Executions | Rows read |
|---|---:|---:|
| Canonical point document lookup | 1,905 | 1,905 |
| Notifications query | 21 | 1,302 |
| Engagement summary (reactions/comment total/preview) | 163 | 1,242 |
| Historical comment count | 113 | 452 |
| Chat membership/documents query | 30 | 390 |
| Users-root query | 29 | 290 |
| Second canonical point lookup shape | 148 | 148 |
| Second notifications shape | 4 | 128 |

Largest four read shapes account for 4,901 / 6,102 reads (~80.3%). Each main point lookup is already one read; reducing its call volume requires route/caller attribution rather than another index. Notifications cost 62 reads/execution for the main shape; summary costs about 7.6; historical counts cost four.

All observed write subtotal is accounted for by two shapes:
- Legacy mirror INSERT: 40 executions / 360 rows written (~70.0%).
- Canonical document INSERT with path lookup: 22 executions / 154 rows written (~30.0%).

The shapes do not reveal which documents or caller generated the writes. Do not assert they are heartbeats, duplicate writes, or all from this user; do not disable transactional mirrors on this basis.

## Wider window exposes a separate hotspot

Latest 15 minutes 12:00:59–12:15:59Z: 18,443 reads / 595 writes / 3,459 SQL executions. Largest shape is chat-membership query: 60 executions / 11,100 reads (~60.2% of that window; 185 reads/execution). It is not the same rank/cost as the latest five-minute chat shape. The older ten minutes account for most of this hotspot; do not attribute all of it to the five-minute user session.

60-minute observations: 23,220 reads / 900 writes. This rolling window includes pre-session activity and the backend rollout; it cannot prove savings.

## Next investigation priorities

1. Map the 185-read chat shape to its complete query/route and check indexed participant pagination vs diagnostic or exception scans.
2. Map the 1,905-call point lookup to callers; counts alone do not prove polling.
3. Notifications plan/pagination/exception paths (62 reads/execution).
4. Identify specific INSERT pathways before changing write/index behavior.

No production SQL probe, schema change, app load generation or deployment was performed for this report. No complete-day quota success is claimed.
