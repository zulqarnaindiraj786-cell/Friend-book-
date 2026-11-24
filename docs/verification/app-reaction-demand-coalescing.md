# App-side reaction demand reduction

Source 9892d12fd441ebb83f659e33cef62c2b21228f4d; actual-path tests 28b80bbe67333ffd8861c24c95e0893c0ca749b7. Android compile/JVM run45309811324 SUCCESS.

## App defect and change

ReactionWriteQueue previously retained every intermediate absolute target from rapid taps, even while another request was still pending. A five-tap like/unlike burst became five sequential PUTs. Server idempotency cannot remove the cost of genuinely different intermediate targets requested by the client.

The queue now folds every tap into desired UI state, but retains at most one unsent absolute target. A request already in-flight remains untouched, including its existing retry/recovery contract. Pending desired state equal to the active target needs no follow-up PUT. Pending final state different from the active target sends one follow-up PUT. Taps before the initial read still use the actual server starting state and collapse to the final target; a net no-op sends no write.

No debounce timer, new background request, per-screen fixed polling, server index change, cross-resource queue, write fallback or change to normal separate interactions. A tap after a completed interaction still starts its normal new operation. Other posts do not wait.

## Verification

- Real D1Reactions.tapLike/PendingReaction path exercised in Robolectric with mocked transport and a deliberately unresolved first PUT.
- 100 taps while the first PUT is pending produce two PUTs: like then final unlike. No extra ordinary reaction GET.
- 101 taps while first PUT is pending produce one PUT: like. Final local mine remains like. No extra ordinary reaction GET.
- Queue-level 5,000 seeded intent bursts match sequential user intent with at most two writes per controlled burst, including only-add, type-switch and toggle combinations.
- Tests preserve null/unlike target, real starting state before initial read, independent resources and stale-completion protection.
- 433 Python tests PASS; git diff --check PASS.
- Two earlier Android runs were superseded/cancelled by subsequent test commits. Final run45309811324 is green. The test transport mock retains real JSON/key helpers without executing authenticated I/O during stubbing.

These are controlled demand reductions, not a measured claim about production tap frequency or billed daily savings. This source change is not in installed APK178. No APK build, Worker deploy, schema change or PR merge occurred for this app change. Full representative-day quota acceptance remains unverified.
