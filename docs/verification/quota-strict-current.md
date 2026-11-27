# Strict quota verification after legacy notification index retirement

The user requested the strict account-wide target of at most 100,000 reads and 5,000 writes per day, not a claim about provider plan limits. The guarded index-only retirement [45381393251](https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45381393251) completed at provider-reported 2026-11-24T11:08:50Z; this is the conservative current backend boundary. Some Android source fixes remain unshipped and there has been no representative device-session trace on the latest Android code.

Owner-requested strict verifier [45384774867](https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45384774867) reports **FAIL**, with delayed/adaptive account-wide GraphQL analytics queried at 2026-11-24T11:31:00Z:

| UTC day | Complete? | Account reads | Account writes | Status |
|---|---|---:|---:|---|
| 2026-11-22 | yes, pre-boundary | 2,260,402 | 105,706 | FAIL |
| 2026-11-23 | yes, pre-boundary | 739,430 | 17,444 | FAIL |
| 2026-11-24 | no, straddles boundary | 424,421 | 6,514 | FAIL |

The partial day includes earlier versions, production traffic, maintenance and engineering work, including the one-time 623-read / 1-write index DROP; it does not isolate post-fix usage. No complete post-boundary day is available. A later within-budget quiet day alone would still not prove a representative workload, installed-client behavior, a subminute spike fix, or future capacity. The thresholds were not relaxed, and the failed step was not converted to success. Worker and Python regressions, 10/30/100-user network-disabled Worker replays and dry-run compilation succeeded in the same run; those synthetic replays are not billed-quota proof.

## Owner-requested refresh and remaining hotspot

In analytics-only [45384774870](https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45384774870), ending 11:30:33Z, the preceding 5 minutes reported 671 reads / 0 writes, 15 minutes 7,399 / 284, and 60 minutes 19,382 / 345. These windows are delayed, overlap older activity and engineering work, and do not constitute an observed device session or full-day pass. Observed 15-minute push-claim INSERT shape: 184 executions / 184 writes; claim logic atomically deduplicates delivery per event/device and retains/releases claims based on delivery outcomes. Shape totals do not show a repeated event or prove those writes are avoidable. No push delivery or deduplication was disabled.

A fixed-vocabulary, value-redacted reporter improvement identified a high-volume generic posts collection scan in [45385216292](https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45385216292): four executions / 2,996 reads / 2,992 returned in the hour ending 11:33:16Z. Source uses a fixed `quota_root_posts` alias in the generic `readCollectionRows` fallback; this fingerprint does not distinguish a particular query specification, route, installed app version or caller. Full counts, Saved Posts, pages, unsupported cursors and other compatible fallbacks must not be capped or rewritten without exact-filter, cursor, privacy and access-plan proof. Added tests prevent logging arbitrary identifiers, literals or bound values. No Worker, schema, app traffic, or data was changed in this refresh. A representative installed-device trace and completed post-boundary day are still unavailable.

The overlapping 24-hour analytics-only [45380671427](https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45380671427) reported 297,304 reads / 11,858 writes from 92,910 executions in the primary D1 query dataset, with top-shape lists truncated. It is neither a UTC-day billing total nor a device-attributed sample. Large shapes included point lookups, engagement summary, legacy comment counts, feed exceptions and notification writes. The window includes older versions; existing source/server fixes, including the late people-head and comment batching changes, should not be treated as if installed clients had already exercised them throughout the window. Hashtag all-match ordering, representative installed-client behavior, and global 100% completion remain unresolved.
