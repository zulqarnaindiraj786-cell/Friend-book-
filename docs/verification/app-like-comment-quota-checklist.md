# Likes and comments verification

Date: 2026-11-19. Verified code/test head: `eb3f546`.

## Scope and changes

- Reviewed shared post/reel reaction paths, reaction queue, comment paging, post/reel comment composers, comment reaction/edit/pin/delete actions, and unsent-comment retries.
- Preserved existing post/reel like safeguards: actor-scoped state, one-second resource cooldown, serialized absolute-state writes, coalesced unsent intent, bounded failure recovery, optimistic state and server reconciliation. No background reaction polling was added.
- Comment edit/react/pin/remove now have an actor/resource/operation pending lock and one-second cooldown. Repeated pending taps cannot issue parallel copies of the same operation. Mutation failure releases the lock without automatically starting a read/retry loop.
- Failed comment reactions restore the previous viewer reaction, liked-by list and aggregate total. The comment Like button is briefly disabled and uses a shared resource cooldown as well.
- Fixed aggregate comment-like arithmetic: the D1 comment response contains only the viewer's reaction identity, not all reactor identities. Optimistic like/unlike adjusts the server total by the viewer delta; changing reaction type preserves the total. Values clamp to zero/Integer.MAX_VALUE.
- D1 edit/pin/delete retain confirmed visible state until the acknowledgement instead of showing a false successful deletion/edit/pin after a rejected write. Text mentions likewise remain confirmed until ACK.
- Hidden comment handles cancel pending page deadlines/conflict retries, invalidate late page callbacks, and refuse hidden load-more demand. Returning keeps loaded pages; an interrupted initial page can retry, and a return after 120 seconds can refresh on demand. No timer-driven refresh was added.
- Destroying either comment activity releases its handle and listener; removed handles leave the static active-handle registry. ACKs may still update a paused, actor-owned handle without rendering hidden UI.
- Post/reel comment screens check actor ownership on return, send and acknowledgement callbacks. Comment photo-upload completion cannot send under a changed account. A photo completed while hidden is kept for a later explicit send rather than posted from the hidden screen.
- Empty text+media sends are rejected before creating a comment ID/draft/write.
- Reply-to-owner sends one recipient notification rather than both a comment and a reply notification to the same owner.
- Draft retries keep the original comment ID and share a pending lock with the initial send. Multiple retry dialogs/taps cannot send the same pending draft concurrently. A confirmed ACK retires the original actor's private draft even after account change; callbacks/notifications do not cross to the new actor. Failures retain the draft for explicit retry.

## Automated evidence

- Android compile and full JVM suite **SUCCESS**: [45339563437](https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45339563437), head `eb3f546`.
- Python regression suite: `python3 -m unittest discover -s scripts/tests -q` — **474 tests PASS**.
- Six new `CommentMutationDemandTest` behavioral regressions cover: 100 pending edits/one write and failure unlock without GET; reaction rollback/account isolation; pause/late-page suppression/resume reuse; static handle cleanup; 100 draft retries/one original-ID request plus account-switch ACK cleanup; aggregate totals with viewer-only reaction maps.
- Five new Python wiring contracts cover mutation locks, lifecycle cancellation/TTL, reaction rollback/ACK-only delete, upload actor guards and retained post/reel absolute-state reaction queue.
- Existing reaction demand, write queue, summary reuse, ACK-demand, conflict-loop and other full-suite regressions remain green.
- `git diff --check` clean.

## Limits / deliberately retained behavior

- This is source/compile/mocked runtime verification, not device network or billed-row telemetry; no production quota certification is claimed.
- Comment create/remove may still invalidate and coalesce an aggregate-summary read for server-authoritative totals. Current ACKs do not include an authoritative aggregate count; inventing one or blindly incrementing idempotent replays would be incorrect.
- Pending locks/cooldowns are process-local, not a new cross-device exactly-once guarantee. Original draft IDs preserve the existing server idempotency contract.
- No APK, version bump, backend deployment, Supabase access, merge or owner diagnostic download was performed. The installed APK does not yet include these fixes.
