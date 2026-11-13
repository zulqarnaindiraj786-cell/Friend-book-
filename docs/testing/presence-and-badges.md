# Fresh presence + badge-only dismissal — 2.8.17 (49)

## Presence implementation

Native Java app: `core/TimeAgoUtil.java` is the reusable equivalent of the requested Kotlin utility. `formatLastSeen(epochMillis)` returns Active now / Active 28m ago / Active 2h ago / Active 14d ago. Unknown/zero -> empty; short form returns now/28m/2h/14d. Days stay days beyond one week. Server-time-corrected overloads are used in live UI.

`PresencePolicy` is the deterministic shared rule: active=true AND nonzero timestamp younger than 5 minutes (strict boundary), tolerating only 30s of future skew. Known RTDB offline/missing/denied status cannot be overridden by a newer Firestore online flag. Firestore-only fallback requires a server-confirmed snapshot, not cache. Privacy is loaded before exposing status; disabled/blocked-by-privacy/deactivated/banned status hides both dot/time.

`Presence` listens at `/status/{uid}`. Each installation maintains its own `connections/{installId}` entry plus legacy root state/isActive/lastSeen/lastChanged for compatibility. Disconnect operations stamp the server timestamp and set only that installation offline; another fresh foreground device can keep the account online. Online writes wait for the onDisconnect registration acknowledgement; an obsolete account/session callback cannot publish a new online state. Heartbeat is 25s; UI expiry re-evaluates at the 5-minute deadline even with no new server event. Missing/deleted RTDB nodes clear presence rather than preserving the previous green flag.

Background transitions publish offline after a 500ms Activity-transition debounce. On process/network loss, Firebase detects the disconnect server-side; the receiving UI hides the dot as soon as the offline event arrives. This is NOT a guarantee of instantaneous physical network-loss detection. Stale data is bounded by the freshness TTL. Legacy clients that overwrite whole status nodes have reduced multi-device compatibility; upgrade both sides for acceptance testing.

Inbox: shared live watches drive both rows and Active Now strip, not cached User.isOnline. Offline people are excluded from the strip. Unread counts no longer hide the green dot. Offline last-active short text occupies the right-hand time slot; online/unknown rows retain ordinary message time without fabricating a last-seen time. Avatar dot and last-active TextView are actual XML resources (`view_presence_avatar`, `view_last_active`). Watchers close on Activity stop and are shared by UID; recycled rows are rebound safely. Chat headers and profile status already use Presence.watch and now inherit the corrected policy/formatting. No screenshot attachment was available to pixel-match.

No RTDB rule deployment is needed for the added child paths under the existing owner-writable `/status/{uid}` rule. Live RTDB connectivity/rules are still a device acceptance prerequisite; no Cloud Functions/billing changes are introduced.

## Bell / friend-request badge semantics

- Bell tab opening only dismisses the local per-account top badge. It does not mark cached or Firestore notification items read.
- Clicking one notification still marks only that notification read.
- The existing explicit Mark all read button is preserved; bulk read occurs only after that deliberate action (not tab open, resume, badge dismissal or incoming events).
- Friends tab opening only acknowledges the local badge (IDs/timestamp in preferences). No seenByReceiver, request status, pending list or notification document is changed. Confirm/Delete still operate only on the chosen request.
- Existing message badge-only behavior is retained; individual conversations keep unread counts/receipts until opened.
- Previously auto-read notification flags cannot safely be reconstructed; historical values are not reset/speculated.

## Verification

JVM: TimeAgoUtil minute/hour/day/future/unknown boundaries; PresencePolicy exact 5-minute expiry, stale online rejection, RTDB offline precedence over fresher Firestore, no cached-only green dots, privacy, another live device.

Python: badge dismissals have no entity writes, lifecycle callbacks cannot invoke mark-all, active-strip filtering uses live results, no unread-dependent dots, disconnect arming/timestamps and listener cleanup. Existing link/media/push/message-badge guards remain.

Physical acceptance (pending, not claimed by CI):
1. Different accounts A/B: B foreground -> A inbox dot; B background -> dot disappears and short age appears. Active Now excludes B; chat/profile show the same state.
2. Network loss / kill / screen lock / reconnect: inspect server disconnect event, timestamps and TTL; no perpetual green from a stale Firestore flag. Missing/deleted status node must clear the dot.
3. Advance test clock around 59s/60s, 59m/60m, 23h/24h, 7d/14d; check right-side text, not message sent-time confusion.
4. Same account on two devices: background one while the other remains active -> account stays online; background both -> offline. Toggle Active Status and check privacy; test stale legacy data and non-default device clock.
5. Recycle rows, navigate between screens, background/resume and logout/switch accounts: no stale holder callbacks or growing listeners.
6. Receive two notifications + two friend requests + messages in two chats. Tap each top tab: only outer badge clears. Items remain unread/pending. Open one notification/chat: only that entity changes. Confirm/Delete one request: other request stays pending. New arrivals can raise the badge again after leaving the tab. Explicit Mark all read is a separate deliberate action.
