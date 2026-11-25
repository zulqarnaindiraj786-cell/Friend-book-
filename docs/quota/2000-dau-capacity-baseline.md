# Friendbook 2,000 DAU quota baseline

Status: **planning estimate only; not a production capacity certification.**

Target: 2,000 daily active users (DAU), not 2,000 concurrent sessions. APK/AAB packaging is intentionally out of scope.

## Existing source baseline

The current `scripts/loadtest/five_user_budget.py` is a deterministic document-delivery model. It explicitly says it is not D1 `rows_read` telemetry and assumes a particular activity mix, cache behavior, and per-action costs.

Re-evaluating its current constants for one modeled user gives:

| Modeled metric | Per user/day | At 2,000 DAU |
|---|---:|---:|
| D1 document reads | 2,211 | 4,422,480 (includes 480 scheduled-cleanup reads) |
| D1 document writes | 299 | 598,000 |
| Worker invocations | Not modeled by this script | Not established |

These numbers are a **linear extrapolation of assumptions**, not observed production usage, not guaranteed billing rows, and not a claim that every DAU performs the full modeled activity. The model does not include all SQL scans, authorization reads, retries, media requests, cache evictions, other clients, or detailed Worker CPU/subrequest use. The write estimate is especially sensitive to the assumed number of reactions, notifications, receipts, and mark-read operations.

The estimate is enough to show that the existing five-user model cannot certify 2,000 DAU. Do not report “2,000 supported” from this arithmetic.

## Work already present on main

- PR #42 narrowed the legacy comment candidate de-duplication path and was deployed to production (Worker version `bfde2990-3743-4c5a-9723-2986dfb549b2`).
- PR #43 added an actor/resource-scoped gate for legacy Reel reaction fallback reads.
- PR #44 deferred legacy post-backed Reel reactor hydration until the viewer explicitly opens the reactor sheet. PR #44 is merged as `fae4a893379c819a7858377aae71ab4c828d64a6`.
- These changes reduce specific read demand; they do not establish whole-app DAU capacity.
- No APK/AAB build is part of this work.

## Required evidence before capacity sign-off

1. Capture a paired Android session trace (cold and warm) for a representative test account and dataset. Record screen actions, HTTP attempts, retries, cache state, and foreground/background time. Never include credentials or private message bodies.
2. Correlate that exact window with Cloudflare D1 `rows_read`, `rows_written`, Worker request count, CPU, errors, and R2/egress metrics. Keep diagnostic/inventory traffic separate from app traffic.
3. Attribute high-cost write families separately: messages, room previews, notifications, mark-read, reactions, receipts, posts/comments, profile/settings, and live state. SQL execution counts are not billed rows.
4. Run controlled staging tests at increasing daily-session volume and burst concurrency. DAU volume and simultaneous online users must be reported separately.
5. Test correctness during load: authorization, block/privacy checks, account switching, message delivery, notification fan-out, retries, and pagination. Do not trade these away to reduce quota.
6. Compare measured daily totals with the actual Cloudflare account limits and leave operational headroom for peaks, retries, scheduled jobs, and growth.

## Safe optimization rules

- Fix only a consumer demonstrated by paired traces or attributable D1 analytics.
- Prefer eliminating duplicate/no-op work while preserving the same server-side authorization and user-visible behavior.
- Do not turn off durable notifications, message delivery, security checks, or freshness just to make a quota chart look lower.
- Do not replace private server reads with public caching or trust client-side TTL for access control.
- Do not add speculative batching where it increases billed rows or risks lost updates.
- Keep production deployments separate from source changes and staging verification.

## Acceptance gate

The 2,000-DAU target is accepted only after a representative workload passes with measured headroom across D1 reads/writes, Worker requests/CPU, error rate, latency, and media egress. Until then the status is **not yet proven**.
