# v2.8.13 (45): icon acknowledgement is NOT reading all chats

Opening Messages clears only the account/device-scoped top icon badge through `chatBadgeClearedAt`. It does not write to Firestore or modify inbox `unreadCount`, message `isSeen`, or `isDelivered`.

Inbox bold rows/unread counters remain until that specific conversation is opened. Existing ChatActivity receipt processing remains scoped to `chatId`. The explicit per-conversation “Mark as read” menu action is unchanged.

Manual regression:
1. Receive messages from A and B without opening either chat.
2. Tap top Messages icon: outer badge disappears; both inbox rows remain unread/bold with their counters.
3. Back/reopen app: acknowledged outer badge stays dismissed; inbox rows still unread.
4. Open A: only A is marked seen/read; B remains unread. Sender B must not get a seen receipt.
5. Receive another message after dismissal: outer badge can appear again; B's inbox unread state remains independent.

Historical unread counters already zeroed by older app versions are not reconstructed by this update. This prevents future bulk clears; it does not fabricate previous unread/seen state.

Automated structural regression guard: `python3 scripts/tests/test_message_badge.py`.
