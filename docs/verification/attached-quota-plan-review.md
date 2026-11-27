# Attached quota plan: repository reconciliation and request measurement

## Inputs and scope

The uploaded `text.txt` contains an external-agent report and proposed work order, not its claimed five-file `friendbook-quota-fix/` package. No package, SQL file, batch contract, cache-rules file or `measure-quota.mjs` was present in this checkout at review time. Do not apply unseen SQL/contracts or call the external numbers independently verified telemetry.

The claimed ~550 requests/~860 queries **per app-open** are not established by the supplied aggregate minute counts without an app-open/session marker. Quiet gaps and CV alone do not rule out all lifecycle-dependent loops. A minute with 606 D1 executions does not identify an HTTP request count, device or user. Nor does a limited query-shape list prove every current query index-optimal. The 1,000-user/5-request capacity table is a hypothetical budget, not a capacity test or guarantee. WebSocket/DO traffic is not universally free; do not repurpose authenticated call/live relays as generic notification buses without a separate protocol, authorization and billing design.

## Work order reconciliation

1. **Inline feed documents:** already implemented. `Query.parseQuerySnapshot` consumes full `data` documents; Home also receives page engagement. Do not add another competing feed API on an unproven premise.
2. **Batch documents:** an unused endpoint alone saves nothing. Distinguish requests for distinct required records from duplicate reads and authorization dependencies. Batch ACL and canonical-only behavior need the actual client consumer and regression contract before implementation.
3. **Badges/chat aggregation:** aggregate only screens that actually request those datasets together; never load hidden lists merely to reduce request count.
4. **WebSockets:** periodic document listeners are disabled in current source. Preserve push invalidation and call/live protocols; no speculative socket rewrite.
5. **Cache-first:** profile cache-first and active document-flight sharing already exist. No blanket authorization/profile/chat TTL change without freshness tests.
6. **App shell assets:** this is a native Android app, not an HTML feed shell. ASSETS does not make authenticated native D1 API calls free.

## Added source-only measurement

`RequestWindowCounter`, `CloudflareRequestTrace`, `CloudflareHttp` and `SplashActivity` provide an **explicit debug-only** 60-second observation window beginning at the exported launcher entry. The shared HTTP **network interceptor** counts actual attempted exchanges, including auth retries, rather than logical reads before coalescing. It counts document reads by a fixed root allowlist, generic query/count/batch/write requests and other shared API requests.

Nothing is uploaded. There is one delayed local log summary, no polling, no extra HTTP requests, no D1 writes, no changed response handling, and no token/header/body/URL/document-ID logging. Only fixed category labels and counts are retained. Activity recreation/re-entry cannot reset an active window. After 60 seconds no further attempts are accumulated until explicitly enabled again.

### Run later, only after user authorizes an instrumented app

No APK/AAB was built for this task. On an already-installed **debug** build containing this source, with the test account signed in:

```sh
adb shell am force-stop com.friendbook.social
adb logcat -c
adb shell am start -W -n com.friendbook.social/.SplashActivity --ez quota_trace true
adb logcat -s FBQuotaWindow:I '*:S'
```

Wait for the JSON summary with `scope: app_entry_60s_shared_http_attempts`. Do not navigate away/change account while measuring a Home-only scenario. Record cold/warm state, actions, network and cache conditions manually; repeat comparable runs. Run normal uninstrumented sessions separately for production acceptance. The debug guard prevents this intent extra from enabling any trace in release builds.

**Coverage limit:** only `CloudflareHttp` document/engagement traffic during this window is counted. Direct OkHttp/URL clients, WebSockets, FCM, media/CDN traffic and startup work before Splash are not covered. Network-interceptor attempts are not server-confirmed invocations or billed requests, and document-root categories do not establish a callsite or prove duplication. No actual device run has occurred here; no measured per-open result is claimed.

## A–G status requested in attachment

A/B. Historical measured large consumers and attribution remain in `external-quota-audit-review.md`, `dominant-write-promotion-attribution.md` and `wider-quota-consumer-review.md`. Promotion/coverage/backup maintenance must not be relabeled active Android loops. Current burst caller attribution remains unresolved.

C. Recent proven fixes: zero-visible preview history avoidance (`29d7ecf`); active DEFAULT document-read sharing (`e13e06c`); early denied-audience ACL rejection (`e0d6484`). Their dedicated verification documents contain regression details. This patch adds bounded local measurement, not another claimed quota saving.

D. Worker `e0d6484` rollout 45215521957 succeeded. Android document-flight and measurement changes are source-only until a user-authorized release/install. PR36 remains open/unmerged.

E. Local evidence: 3,000 empty-preview row visits to zero; 100 overlapping default callers share one operation; denied-page document lookups 42 to 20. These are not measured production savings. No ~550-to-5 result exists.

F. Next verification: compile/JVM suite for the new measurement code; later an authorized device trace with explicit window and scenario, then investigate the dominant category's actual callers. The unavailable external package was not fabricated or executed.

G. Daily target **UNVERIFIED**. Account-wide diagnostic/deploy traffic counts, source-only Android fixes are not installed, and no representative completed post-change UTC day has been established. Preserve the user's stricter 100k reads/5k writes goal separately from provider plan limits. No paid upgrade, schema migration, presence/typing feature, cron change or packaging requested/performed.

## Validation completed for this patch

- Worker: **292 passed** locally.
- Python: **416 passed** locally, including opt-in/privacy/interceptor wiring.
- Real Worker/local SQLite replay: **100 users / 5 concurrent / 48 workloads passed**.
- Android compile/JVM suite: [45218306666](https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45218306666), **success**, exact source `2c83588b058b04b9f6be12a7bfdc4151022dcccc`. Four new pure-JVM counter tests cover opt-in/window boundaries, recreation/no reset, fixed privacy-safe categories and 8,000 concurrent attempts counted without loss.
- Packaging workflow 45218307840: **skipped**. No APK/AAB built.
- Worker validation workflows 45218317620 and 45218307111: **success**.
- Diff whitespace check: passed.

This resolves the compile/JVM prerequisite in section F; the later authorized **device trace remains pending**. No new Worker deployment or production per-open measurement was performed in this patch. Measurement is not itself a quota reduction.
