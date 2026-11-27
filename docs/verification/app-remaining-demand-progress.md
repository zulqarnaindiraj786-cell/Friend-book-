# Remaining app demand: verified progress, not whole-app acceptance

## Changes

- `PagedLists` caps its requested page size at 10, reuses a fresh exact-query page for 120 seconds (friend lists: 300 seconds), offers an explicit server refresh, and suppresses callbacks after close, destruction or account change. Existing chat-people and groups cache policies remain separate. The cap applies to existing callers as well as new ones; they may require more user-requested pages than before.
- Events, Pages, Marketplace, Memories and Activity Log replace oversized initial lists with explicit pages. Their search, filters, sorting and displayed counts describe **loaded items**, not the entire corpus; more items require explicit demand. Activity Log starts one page per kind. Returned rows and underlying billed D1 reads are not necessarily equal.
- Story viewer no longer automatically drains successive empty or filtered story pages. The user can explicitly request more. Story-viewer profile lookups are limited to 10 per explicit dialog page and suppressed after dismissal, stop or account change.
- Birthday screen ignores obsolete account/lifecycle/generation callbacks, deduplicates IDs and completes rendering even when a profile lookup fails.
- Creator eligibility reads at most its five-reel threshold rather than up to 50 documents, displays the threshold as 5+, and updates correctly whether follower or reel data arrives first. This is threshold eligibility, not an exact reel count.
- Content-moderation tab setup no longer starts a listener in both `onCreate` and `onStart`.

## Verification

- Android compile and complete JVM suite at `f988bbd`: https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45340855695 — success.
- Python suite after `f988bbd`: 481 tests passed. `PagedListsDemandTest` exercises a capped cached head, explicit refresh and late actor/removal callbacks. The remaining new screen wiring tests are source-level guards, not device telemetry.
- No APK build, app deployment, Worker deploy, migration or PR merge accompanied these changes. Installed APK 181 predates them.

## Open work and caveats

This is not a whole-app quota fix or a daily acceptance result. Examples still needing separate correctness and quota review include large admin payments/gifts aggregates and moderation lists; settings insights and account deletion; live discovery/invitation flows; remaining admin and media actions; and production write/fan-out attribution. Do not simply reduce limits on financial totals, eligibility or bulk delete: that would silently misrepresent data or leave user data behind. Existing explicit bulk operations and authorization semantics must be preserved.

The prior post-reset spike remains unresolved. No installed-device or measured full-day billing experiment was performed here, so <=100k reads / <=5k writes and 100% app-wide correctness cannot be certified from these tests. No background polling or unseen-page prefetch was added.
