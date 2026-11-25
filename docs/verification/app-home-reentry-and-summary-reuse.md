# Source-only Home re-entry / summary reuse corrections

Owner clarified that actual app usage was LESS than one minute; elapsedMs60000 was the trace window, not a measured full minute of active use. Do not downplay the quota complaint based on 45 requests or turn that number into billed reads. No further device download/capture requested in this pass.

## Changes

1. Home secondary-section admission: startVisibleHomeContent was callable from lifecycle/visibility/refresh paths with no completed-admission check. It canceled a pending runnable, but after a runnable launched its work another entry could launch it again. New HomeSectionReadPolicy guards same actor/view re-entry. Admission is recorded only after visibility and viewport checks pass. Explicit refresh, new actor and a new view remain eligible. The new view resets admission and cancels prior pending secondary work. Existing section caches, first-load ordering and explicit recovery stay in place. This is an eliminated source-level repeat opportunity; the owner's 13 Main query calls are NOT proven identical or all caused by it.

2. Reaction-summary rebind reuse: shared summary TTL was 8 seconds, so non-bundled adapter rebinds could fetch the same summary again after only 8 seconds. It now uses the requested 120-second FeedReadPolicy reuse budget. This is demand caching, NOT evidence of an 8-second periodic timer/infinite loop. Mutation invalidation still removes the age and forces the relevant fresh lookup immediately; write ACK handling is unchanged. No query authorization/security check was removed.

## Tests/evidence

- HomeSectionReadPolicyTest: first admission allowed; 10,000 repeated same-actor entries denied; explicit refresh, changed actor and unclaimed/cancelled entry allowed. This is a pure admission-policy test, not a measured count of HTTP calls prevented.
- Source guards verify the policy runs before scheduling and admission is claimed after visibility checks; explicit refresh remains able to recover sections.
- Actual ReactionSummaryReuseTest: initial summary GET -> 100 bindings after9s add zero GET; mutation invalidation adds one GET immediately; expiry after120s allows next read.
- 446 Python tests PASS; whitespace check clean.
- Final source `80d27e08a95e5df5c45bc8ad3e444f0d58171ad2` includes Home `fd56005`.
- Android production compile and full JVM suite SUCCESS: https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45329577701

## Boundaries

Source only. No APK/release/backend deployment/merge/Supabase/calculator changes. The owner's installed APK has NOT changed, and these source changes cannot reduce its traffic until eventually packaged/installed. No new download requested now. Existing trace is insufficient to identify billed-row amplification, startup traffic or every remaining loop. Daily quota target and 100% root-cause resolution remain unverified.
