# Friend Requests source / automated quota checklist

Verified application/test revision **9bb3014**, CI revision **57adcf6**. Android compile + full JVM workflow **45336961520 SUCCESS**. Python suite **464 passed**; `git diff --check` clean. No APK, release, merge, backend deployment or owner trace/download.

## Reviewed and fixed

| Path | Source contract |
| --- | --- |
| Received / Sent / Friends / Suggestions pages | Ten-row cursor pages for all four sections, not twenty. Five-minute cached head reuse. Request heads revalidate with a full bounded page rather than a new-ID-only delta, so accepted/cancelled/deleted requests are not retained by a stale merge. |
| Repeated tab / pull demand | Same active section is not rebuilt on duplicate entry. A pull while a page is pending joins that work; explicit refresh has a one-second guard. Section switches can still replace the old pager intentionally. |
| Lifecycle / ownership | Both main and inline pagers close on hide/stop, including pending inline suggestion work. Actor/started/visible/view/generation guards protect response and render paths. View destruction drops UI references. No polling, typing/presence or unseen prefetch added. RecyclerView layout prefetch disabled. |
| Request accept | Shared actor/target pending-action lock and one-second cooldown prevent duplicate/opposing actions. The existing server-authorized atomic request/relationship/counter batch is retained. Optimistic removal stays local; one successful ACK sends the acceptance notification, without reloading the section. Failure restores the request and local relationship state without a query. Pager records are forgotten only after ACK, so failed optimistic changes do not disappear on the next page. |
| Delete / cancel | Same pending-action lock; completed request IDs suppress replay. Failed delete restores the existing model locally, retaining the pager row, instead of issuing a section reload. Successful cancel removes the exact sent request locally. Hidden or old-account callbacks cannot repaint/send follow-on notifications as a replacement account. |
| Send request | One pending write per actor/target; known incoming/outgoing/friend state rejects redundant sends. Server duplicate-check remains authoritative. Success/ALREADY_EXISTS updates candidate state locally; failures do not trigger a fetch/retry loop. |
| Candidate eligibility | Three bounded relationship probes per new batch of up to ten candidate users, now each limit ten instead of twenty. Present/pending/failed candidate guards prevent render-driven rechecks. Cached/offline or saturated legacy results fail closed rather than offering an unverified Add button. Reuse across visits is reset by explicit pull, account switch or the five-minute check window. No recursive drain of filtered suggestions. |
| Friend profiles | Incoming requests continue to use embedded sender metadata; Sent uses cached/placeholder users. All-friends missing/stale details use submitted-page document-ID batches of up to ten instead of forced fresh GET per friend. Attempted IDs suppress callback/render replays; cache/explicit refresh control revalidation. Failed/missing profiles retain a usable placeholder row. No profile fetch from UsersAdapter bind. |
| Buttons | Primary and secondary controls disable briefly, in addition to action-level cooldown and pending locks. |
| Unfriend | Explicit confirmation only, shared pending lock, bounded ten-row relationship verification; saturated/offline results cause no partial mutation. Actor/session checked before starting writes from the verification response. No section reload on completion. |
| Request badge | Bootstrap reduced from 32 to ten rows with five-minute reuse and account/destroyed-activity callback guards. Badge dismissal is local. A bounded sample is not an exact total count. |

## Behavioral tests

`FriendRequestDemandTest` verifies:
- 100 Confirm demands -> one atomic batch and one acceptance notification; no automatic request-list query after success.
- Failed Confirm restores request/relationship locally without querying.
- 100 Delete demands -> one delete; failure restores the model without querying or forgetting the pager row.
- 100 Sent Cancel demands -> one delete and no section reload.
- 100 Send demands -> one write; old-account success cannot send a notification under the new account.
- 100 pull demands -> one pending limit-ten server head; stopped delivery is rejected.
- Ten missing friend profiles, repeatedly submitted 100 times -> one batched query and no forced per-user GET.

Existing paging, candidate fail-closed, row recycling, account scope and full-app JVM tests remain in the full run. Python tests cover ten-row source wiring, no post-action reload, lifecycle closure, profile batching and badge reuse. Two intermediate CI runs stopped before compilation while fetching Robolectric dependencies; the verified-download step now diagnoses malformed checksum metadata and retries failed downloads without disabling SHA-512 validation. The final run above passed compilation and all JVM tests.

## Boundaries

These are controlled regression/source results, not installed-device traffic or billed D1 rows. A ten-candidate page can require a user-page query, three eligibility queries and other genuine demands; ten is a per-query page limit, not a claim of ten total billed reads. Saturated duplicate relationship data deliberately requires verified cleanup rather than unsafe partial unfriend/eligibility decisions. Production quota savings and the full-day quota target remain unmeasured; this record does not certify 100% production quota resolution.
