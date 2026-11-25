# Admin on-demand quota follow-up

## Changes

- Reports and feedback no longer attach 100-item listeners on screen entry. Each now loads explicit ten-item pages, closes on stop and exposes Refresh/Load more. Empty labels refer to loaded pages, not the entire queue.
- Successful dismiss/archive operations remove only the confirmed item; failures preserve it. A process-local actor/collection/item gate prevents concurrent repeated actions. Account changes prevent a report's second moderation request from running under another user. For the current activity instance, a successful content action followed by a failed report dismissal can retry dismissal without repeating that content action. This is not durable exactly-once across process death or devices.
- Content moderation loads only the selected tab, ten items at a time. Tab switching clears the previous rows before the new tab can act; confirmed deletions are suppressed from stale cumulative pager rows. Duplicate deletions share the same gate.
- The default admin users tab no longer requests 300 users at entry. An observed ten-item head is reused by its cursor pager; further pages require a tap. The existing listener still updates the head after mutations. Search and counts explicitly apply to loaded users; an older user's updated state may require Refresh and loading that page again. Screen/actor and pending search callbacks are disposed on stop.
- No permissions, Worker authorization, financial totals, data deletion semantics or database schema were changed.

## Verification

- Android compile and complete JVM suite at `168846e`: https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45342890317 — success.
- Python: 490 tests passed. JVM coverage exercises 1,000 duplicate gate acquisitions, actor/collection separation, observed-head reuse without unseen prefetch and late next-page completion after close. Additional screen wiring checks are source-level, not full device moderation tests.
- No APK, Worker deployment, D1 migration, production quota probe or PR merge. Installed APK 181 predates these changes.

## Still open

Whole-app quota is not certified. The moderator desk and other admin fragments still have large reads; admin payments/earnings and author badge fan-out, settings insights/account deletion, live invitations and referral award behavior require separate correctness and authorization review. Do not blindly cap financial totals, revoke security checks, or remove badge synchronization without replacing its visible behavior. The earlier user-reported quota spike remains unattributed at device level and no representative completed-day billed quota proof exists. Source page size is not a D1 billed-row cap.
