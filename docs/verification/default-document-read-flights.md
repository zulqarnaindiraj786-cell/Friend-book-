# Overlapping default document reads

## Source-level cause and fix

`DocumentReference.get(DEFAULT)` previously scheduled an independent network request for every invocation, even when another reference for the same authenticated account and exact path already had a pending read. Profile-specific `Fb.getUser` sharing did not cover the general document API.

`DocumentReadFlights` now shares only active DEFAULT reads, keyed by account and path. This is not a TTL cache and does not suppress distinct document reads. CACHE remains local; SERVER always starts independent work. Success, missing results, failure, cancellation and synchronous executor/factory rejection remove the flight before consumers are notified. Invalidation detaches a pre-mutation flight without cancelling its existing consumers. Its later completion cannot evict a replacement flight. Existing asynchronous account checks remain, with an additional check before queued network work starts.

Scope: explicit DocumentReference reads, not listener reads, transactions or a new batching endpoint. Existing cache behavior and mutation paths remain unchanged apart from detaching active explicit reads during invalidateDoc. No schema, Worker deployment, APK/AAB or PR merge.

## Regression evidence

Five Robolectric test methods cover 100 overlapping calls sharing one operation; independent accounts/paths; explicit SERVER bypass; post-invalidation replacement and late old completion; missing/error/rejected operations and retry; and completion callbacks starting a new read. Two Python wiring tests verify CACHE bypass, DEFAULT-only sharing, account checks and mutation invalidation wiring.

- Worker: 290 passed locally.
- Python: 415 passed locally.
- Android compile/JVM suite: GitHub Actions [45213969429](https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45213969429), **success**, exact source `e13e06c35226538545d1f603e72eb4f15ba5a44f`.
- Packaging workflow for that push: build job **skipped** (45213969601).
- `git diff --check`: passed.

The suite succeeded, but downloading its detailed HTML artifact was blocked by an outbound EOF; no separate per-class report was inspected locally.

## Limits

This proves a reproducible duplicate-operation opportunity in source, not the prevalence or caller attribution of the supplied production point-lookup bursts. Distinct required documents still incur their genuine reads. Installed Android does not receive this change until a later user-authorized release. Neither this result nor bursty aggregate analytics proves app-wide absence of loops or the completed-day 100k reads/5k writes target.
