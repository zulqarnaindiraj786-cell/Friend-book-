# Chat updates and friendbook chime — v2.8.11 (43)

- Message snapshots now parse changed documents only. Existing Firestore persistent cache remains enabled for offline/reopen loads.
- Bounded 200-message list uses DiffUtil instead of full redraw on each message/read receipt. Immutable render keys account for reaction mutation, neighbour grouping, and moving last-message/seen-avatar markers. Group metadata updates skip redraws when unchanged.
- Receipt-only updates no longer force-scroll the conversation. New messages scroll only when already near the bottom; reading older messages should preserve position.
- Stickers no longer block the next send while awaiting server acknowledgement. Uploaded voice notes show an optimistic bubble immediately. Text/image optimistic sends remain intact. No network speed or end-to-end latency guarantee is claimed.
- Original 0.82-second two-note WAV is generated deterministically by `scripts/create_notification_chime.py` (no third-party samples). Incoming likes, messages and other allowed alerts use it.
- Android 8+ sound belongs to a channel, not each notification. New `friendbook_chime_v2` channel picks up the bundled chime on update; existing channel importance, disabled sound and vibration choices are retained during migration. User settings on the new channel are not overwritten. DND, silent mode, permission and channel mute still apply.
- Settings → Push notification diagnostics → Preview friendbook notification sound (local) tests notification display/sound WITHOUT the remote backend. This does not verify FCM delivery. Direct-FCM backend activation remains blocked/unverified as described in supabase/DIRECT_FCM.md.

## Validation

`gradle testReleaseUnitTest` tests unchanged-row keys, neighbour grouping, moving seen markers and mutable reactions. Gateway mocked tests are unchanged. Validate audio PCM duration and clipping by regenerating the WAV.

Two-device manual checks: rapid text/sticker burst while offline and online; voice message after upload; replies, reactions, deletion; group metadata unchanged across messages; scroll midway through history while receipts/new messages arrive; update from an existing install and receive a local preview with notification permission enabled; muted channel/DND should remain silent.
