# Chat quota source / automated checklist

Scope: Conversations inbox, open direct/group chat, message history, read/delivery receipts, send/recovery, live-invite cards, team reaction hydration, new-chat and group member pickers. No APK, release, merge, backend deployment or owner trace requested.

Verified code revision **df5f32c**: Android compile + full JVM workflow **45334731919 SUCCESS**; Python suite **455 passed**; `git diff --check` clean. An intermediate JVM regression exposed that a View-posted timeout waits for view attachment; the unlock now uses the main Handler directly with actor/session guards. The final run above includes that correction and the regression tests.

## Changes

- **Inbox:** one active request, actor/started-session ownership, ten rows per page (removed eleventh-row lookahead), one-shot 20-second UI unlock. Timeout invalidates the old callback and does not issue another read. Stopped inbox rejects loads. Real push refresh remains visible-inbox-only and coalesced.
- **Open chat:** message window remains last ten, push-driven; no typing/presence or periodic reads added. Room, peer-profile and message callbacks now share actor/session ownership; stopped/replaced-session callbacks cannot act on UI/call signaling. Existing peer document cache is 300 seconds; room signaling remains authoritative on open.
- **History:** ten rows rather than eleven, one active page, actor/started ownership, unchanged cursor on failure, manual/user-scroll next page only; no bulk history receipts/sounds.
- **Receipts:** successful unread reset is retained across ordinary resumes instead of writing zero on every resume. A failed reset stays suppressed across snapshot repaints and is retried only on foreground demand. Existing per-message pending/confirmed/failed receipt guards retained. This is activity-lifetime suppression, not a claim of durable cross-process exactly-once writes.
- **Sending:** same actor/chat/type/payload intent has a one-second cooldown; distinct messages are not throttled by this gate. Text, sticker and voice sends reject stopped/old-account activity. Photo/voice upload completions retain initiating actor. Existing stable-ID atomic send, optimistic rendering and one-shot uncertain-send reconciliation retained; no automatic resend added.
- **Live invites:** RecyclerView bind no longer reads the live document or embedded base64 frame; displays embedded invite metadata/photo. Actual destination validates live status after opening. Missing-ID lookup remains an explicit bounded user action with cooldown and actor/activity checks.
- **Team reactions:** one batched document-ID query for up to ten current-window messages, instead of one GET per message or hydration of all accumulated history. Repeated render does not refetch attempted message IDs; late response is session/actor guarded. This bounds request fanout, not necessarily billed matching-row reads.
- **People/member screens:** removed create-group 250-user and add-member 200-user scans. New-chat friends/users and create/add-member pickers use ten-row, explicit-more, account-scoped cache-first pagers, closed on stop/dismiss. Group member render uses embedded names/photos plus existing cache, not per-member profile requests. Group info transient failures do not masquerade as deletion. Group creation upload/save is actor guarded.
- **Initial picker stalls:** cache-first CursorPager now has the same one-shot unlock as subsequent pages; timeout emits an error, never a timed network retry.

## Behavioral coverage

- `ChatReadDemandTest`: 100 resumes -> one successful unread reset; failed reset plus 100 snapshot demands -> no replay writes until resume; 100 live-invite rebinds -> zero database reads; 100 duplicate send intents -> one admission while distinct text is admitted; 100 refresh gestures -> one refresh and deadline only unlocks UI; ten team messages -> one batch query and repeated hydration does not refetch.
- `InboxDemandTest`: 100 inbox demands -> one limit-ten query; timeout unlocks and rejects old completion without saving stale cache or automatically retrying; stopped demand rejected.
- `GroupMemberDemandTest`: 100 member-row renders -> no profile/database fetch.
- `ChatPeoplePendingDemandTest`: 100 initial people-page demands -> one cache read; timeout releases busy state and late cache completion cannot start network work.
- Existing pending-message, message-render, uncertain-send and document-listener tests remain in the full JVM suite. Python chat wiring guards additionally check page size, receipt suppression, no live frame hydration and removal of large picker scans.

## Limits of this verification

These are source and controlled JVM regression results, not installed-device telemetry, backend query plans or billed D1 rows. They cannot certify 100% production quota elimination or the full-day quota target. User-triggered send/receipt/signaling and push-driven reads remain functional; zero network traffic is not the goal. No additional download or diagnostic trace is required from the owner.
