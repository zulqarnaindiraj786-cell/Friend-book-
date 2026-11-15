# v2.8.31 /63 —10-chat /10-message windows and visible-Reel autoplay

- Inbox first cached/server window is10 chats, with a bounded10-document live head and
 10-record cursor history below it. Cached rows stay on re-entry; no clear/rebuild between
 receipt-only chat metadata updates. Normal paging uses a bottom spinner, not a fixed More bar.
 Inbox still uses document-ID cursors and client-side ordering inside loaded data; this does
 NOT claim globally newest-first ordering across every unloaded legacy room.
- Open chat listens to latest10; older10 load only upward. Same-account history survives
 stop/start; stable-ID maps merge rolling-window evictions. Equal render keys skip DiffUtil;
 identity is included so two identical messages with different IDs never collapse.
- Metadata-only acknowledgements do not rebuild message rows, while the chat sound gate still
 advances on canonical confirmation. Actual receipt changes remain live and render normally.
- A resume gap larger than the live window uses a fenced endBefore range to fetch only missing
 records, then reconnects cached older history. Cached disconnected segments are retained but
 temporarily withheld until their intervening gap is filled; nested gaps retain continuation
 cursors. Physical-device validation of this bridge/scroll behavior is still required.
- Older-page viewport restores the visible message ID/pixel offset, not a guessed row count.
 Spinner is at the top of messages only while that request runs; retry text appears on failure.
- Seen/delivered batch dispatch precedes list rendering, only for this foreground open chat's
 latest incoming window and enabled receipt preference. In-flight/failed IDs prevent repeat
 writes or permission-error loops; retry failures on re-entry. Room unread resets share that
 batch. Background chats/history are not mass-marked read; network receipt latency is not zero.
- Reels automatically prepare/play only the selected visible video, including delayed surface
 attachment; no tap gate. Hidden/background/detached videos pause, ads do not start a video,
 and raw-URL fallback retains selected-position guards. No offscreen full-media prefetch added.
- No source-data migration/reset/deletion or production backend deployment. Four legacy
 graph/badge/call paths remain unchanged. No Facebook-speed/real5000-device certification.

Local:70 Python guards,44 backend tests,145 Java syntax parses. Release CI must execute Java
 unit tests (including message identity/receipt render keys),8 isolated Firestore SDK tests
 including10+10 and missing-gap fence, R8 and signing. Video playback/network, end-to-end seen
 timing, viewport gestures and2G behavior still need physical-device checks.
