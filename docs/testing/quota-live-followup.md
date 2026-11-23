# Quota follow-up: live discovery and app-area checks

The read-only adaptive D1 analytics run at approximately 00:30 UTC showed 2,952 reads over 164 executions of one two-store live-discovery shape in its latest five-minute window. It also reported frequent point lookups and typing/presence upserts. These are sampled/adaptive query analytics, not the account dashboard totals; window timing and attribution are not a controlled comparison.

## Changes

- Live discovery now matches canonical rooms already in its result before looking up unknown legacy paths in bounded batches. A canonical ended room still shadows its stale live legacy mirror; the existing privacy and status checks are retained. This avoids a correlated canonical lookup for each already-known mirror, but real D1 rows saved have not been measured.
- Android's foreground live *discovery list* polls after 10 seconds and, after an unchanged server response, after 20 seconds. A changed response restores 10 seconds. Previously it polled after 5 seconds even when unchanged. An individual live room and live chat retain the existing 5-second interval, error backoff, and foreground restrictions. Local invalidations can still trigger reads without waiting. Other clients and existing installed APKs retain their old behavior until updated. Newly started live rooms may take longer to appear; this is a deliberate freshness trade-off, not instant push.
- No speculative changes to typing, presence TTL, chat membership, FCM security, privacy checks, feed page size, Reels page size, or Stories limits. Their costs remain and account-wide totals cannot be inferred from the local tests.

## Evidence and limits

The live test checks index plans, a legacy-only live room, an active canonical mirror, an ended canonical room shadowing stale legacy data, and room privacy. It asserts that the active mirror does not cause an extra shadow lookup. Android poll-budget tests cover backoff, reset after a change, foreground state and active-room cadence.

Offline replay uses isolated synthetic SQLite data, not production. A 1,000-user / 200-overlap run exercised 20,493 requests across 22 workloads including Feed, Reels, Stories, notifications, live discovery, chat, user/block checks, a private group projection, and indexed tag search. All 30 collected query plans completed without errors. Of 999 repeated story views, zero changed SQLite rows (the 999 first views changed 9,990 rows including triggers). These are local operation/change counts, **not** billed D1 rows and not a forecast or proof of production savings. The replay is not an exhaustive UI/device or security test and makes no production requests.
