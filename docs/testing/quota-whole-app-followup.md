# Whole-app quota follow-up and operational limits

The read-only audit 44979000173 reported a UTC-day adaptive database aggregate of 326,786 reads and 19,304 writes. The sample is not an account-wide billed total and covers a different window from dashboard screenshots. Its latest five-minute top shapes included schema activity following migration 0076. One other query shape referring to the new index reported 10,296 reads and 423 writes; its operation was not unambiguously classified, so its exact cause is not established. The preflight itself previously reported 3,109 reads. Index builds and investigations are not free, and this sample does not quantify steady-state savings. The audit classifier now distinguishes identifiable CREATE/DROP INDEX statements from application comment-page reads.

In the 15-minute sample, a shared root-mirror upsert shape had 64 executions and 212 reported written rows. The SQL shape is used by multiple features; the aggregate does not reveal which requests were no-ops or which feature caused them. Do not globally suppress mirror writes: mirrored data, timestamps, authorization guards, promotion and realtime freshness are part of the current contract. The prior narrowly guarded no-op paths remain enabled. Other visible shapes include indexed live discovery and bounded people pages; some reads are necessary to show current user data. The sample is not a representative production workload or a reason to arbitrarily cap results.

A further code audit found legacy friendship acceptance and its atomic duplicate guard can examine historical relationship rows. They must preserve both native and wrapped legacy representations, canonical shadowing and malformed/duplicate records. No untested limit, index migration, privacy shortcut or duplicate-check removal was deployed. Measuring its actual usage and designing equivalent indexed/exception paths remain follow-up work.

## Forecast and operating procedure

A finite daily allowance cannot be guaranteed to cover unbounded users, posts, messages, retries or traffic spikes. The currently displayed free-plan example of 5 million read rows and 100,000 written rows per day would provide only 50 reads and one write per day per 100,000 daily-active users before fixed operations and safety headroom; actual plan limits and billing may change. Signed-up accounts are not the same as daily-active users. Additional services (Workers, R2, FCM/Auth and network costs) have separate limits and are not included.

`scripts/loadtest/d1_budget_forecast.py` is an **offline**, illustrative linear extrapolation using counters the operator supplies from the actual account-wide billing dashboard, hours since its reset, configurable limits and reserved headroom. Example with fabricated counters:

```sh
python3 scripts/loadtest/d1_budget_forecast.py --reads 100000 --writes 20000 --elapsed-hours 6 --reserve 0.2
```

It does not connect to Cloudflare, create alerts, block spending, enforce quotas or prove capacity. A quiet interval, delayed dashboard updates, weekend or peak traffic, and migrations can invalidate the projection. Do not feed adaptive GraphQL query samples or local SQLite row counts into it as billed usage. For a sustainable launch, collect representative billed peak and daily workloads (including messages, chat receipts, push, calls, Feed, media, background jobs, failures/retries and index maintenance), preserve headroom, set platform billing/usage alerts and confirm plan limits with the provider. Upgrade capacity or revise activity/freshness requirements when observed peak traffic exceeds the allowance. Test delayed push, quota exhaustion and recovery on staging with synthetic accounts—not production.

No new production app traffic, user-data export, new AAB, or speculative production mutation was used in this follow-up.
