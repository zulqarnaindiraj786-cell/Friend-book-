# Consolidated signed quota-fix candidate: 2.10.35 (177)

## Owner authorization and scope

Owner requested one consolidated APK/AAB after the accumulated quota work, rather than repeated user updates. This explicitly authorizes packaging, superseding the prior pause for this consolidated candidate only. PR36 is not merged. This is a candidate for user testing, not an assertion that all large production consumers are eliminated or that 1,000 users fit a plan.

## Exact build and artifacts

- Source: `61c0375bc4978663fd84e72c2ec24d3bee38f624` on `arena/b52bc74d-friend-book`.
- Version: **2.10.35 / 177**, application `com.friendbook.social`.
- Signed build [45223004835](https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45223004835): **success**.
- APK/AAB signature verification: **success**.
- Actual release-JVM test result gate: **success**, now also explicitly requires ProfileReadFlightsTest, AdSocialReadGateTest and RequestWindowCounterTest alongside the prior required core suites. All release JVM tests run through `testReleaseUnitTest`.
- Python 417 tests, 5,000-session pagination model, mocked FCM gateway and isolated Firestore SDK query tests: build gates succeeded. Local Worker 293 tests and 100-user/5-concurrent/48-workload actual-handler SQLite replay also passed before packaging.

Published as a prerelease, not Latest:

https://github.com/zulqarnaindiraj786-cell/Friend-book-/releases/tag/friendbook-2.10.35-signed-61c0375b

| Artifact | Bytes | GitHub-reported SHA-256 |
|---|---:|---|
| friendbook.apk | 4,970,624 | 5fe03cd18f806e71e7e5b005687a94309f5868112d0605087c3e50684144ab19 |
| friendbook-playstore.aab | 8,852,671 | e6cb3c9097ebf13dde5a4c2b9a4973ba096096275779ec8e661c12bb9981b288 |

Release also contains `friendbook-SHA256SUMS.txt` and `friendbook-mapping.txt` (retain mapping for this exact version). Asset upload states are `uploaded`. Sandbox download was blocked by release-asset-host EOF; artifact bytes were not rehashed independently here. Digests above come from GitHub's release API, and signature checks ran successfully on the build runner.

## Consolidated contents

Includes the accumulated client quota work: Reels cache-only painting and selected-visible hydration, comment-author/badge/profile-flight gates, pager lifecycle/account fences, stable push-work identity and receive refresh-burst gating, shared active DEFAULT document reads, and account-scoped ad-social hydration with bounded failure retries. It also includes the debug-only opt-in request-window source, which is disabled in this signed release configuration.

Backend source includes the deployed zero-visible comment-preview skip, early denied-audience ACL rejection and canonical-only profile audience. Last verified server rollout: **d9e685a / 45220396473 success**. Packaging does not redeploy the Worker.

## Distribution and acceptance

- Production Latest remains **v2.10.32**, independently checked after candidate publication. No automatic user update was enabled.
- No Google Play upload occurred; owner controls testing/distribution/upload.
- PR36 independently checked **OPEN**, mergedAt null.
- Built/uploaded is not installed. Real-device behavior, current burst caller attribution and a representative completed-day **100k reads / 5k writes** target remain **unverified**.
- APK is for direct Android installation/testing. AAB is for Play distribution, not direct installation.

Use this single version for the first consolidated real-device test; no further user package is promised or triggered by this documentation commit. Preserve normal feed/chat/calls/live/notifications/security in testing. Test results/local row-visit reductions are not Cloudflare billed savings or a 100% quota guarantee.
