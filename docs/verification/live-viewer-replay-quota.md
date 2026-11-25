# Live viewer snapshot replay: confirmed repeated-write path

## Root cause

`LiveActivity.onChatSnapshot` iterates all rows of every received live-chat snapshot. It already tracked `processedPacketIds` and computed `isNewPacket`, but only reactions/gifts/etc. used that flag. Join/leave handlers always mutated `activeViewerIds` and immediately called `publishHostHeartbeat` for each mutation.

A snapshot containing an old viewer join followed by their leave therefore added and removed the viewer on every replay. On a host, each transition generated a heartbeat PATCH even though the final membership was unchanged. A snapshot with N first-time joins also issued N intermediate-state heartbeat writes. These are source-established repeated request paths, not a production frequency/device attribution claim. The earlier hourly SQL shape summaries do not prove which device/version executed this path.

## Fix

Source **0b1876c** (includes **769d07c**) adds `LiveViewerEvents.apply`: membership changes require a new packet and a nonempty actor. Activity still renders existing chat messages, while previously processed packets cannot mutate viewer membership again or inflate the join counter again.

The Activity snapshots membership before processing the received page and publishes a single host heartbeat only if final membership differs. This preserves genuine join/leave state changes, eliminates intermediate writes in a batch, and sends nothing when the final set is unchanged. Host/start/leave lifecycle behavior outside this callback is unchanged. The heartbeat method still guards ended/nonhost/not-started/missing-live-ID states. No media, gifts, security checks, polling fallback, cron or backend schema changes were introduced.

## Regression evidence

`LiveViewerEventsTest` covers:
- 1,000 replays of historical join+leave: only the first two membership transitions, rather than the old 2,000 transitions; new rejoin/leave packets still apply.
- Interleaved two-viewer history: replay never resurrects a departed viewer.
- Null/empty actors, absent leaves, duplicate joins and old packets do not mutate state.
- 100 joins followed by 999 identical replays: one final-state publish decision; a same-snapshot transient join+leave leaves final membership unchanged.

These are deterministic local behavioral counts, **not measured D1 billed daily savings**. Python wiring tests ensure both handlers use the real packet gate and there is exactly one final-state-guarded heartbeat call in `onChatSnapshot`.

Android CI https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45189312630 **SUCCESS**, completed **2026-11-23T14:00:10Z**: actual Android Java compilation and full JVM unit suite PASS. **388 Python tests PASS**, **281 Worker tests PASS**. Earlier run 45189218300 tested the intermediate gate-only source; 45189312630 is authoritative for the final coalescing source.

## Rollout boundary and remaining scope

This is an **Android source fix**, not a Worker-only fix. Existing 2.10.34/176 APK/AAB artifacts do not include it. No signed replacement was generated in this continuation, no Play upload occurred, and it is not claimed deployed to phones. An updated build/install is required before attributing any user-side quota benefit to it. PR remains open/unmerged; backend stays at f4cfb96 deployment 45188052011.

Timer audit inspected the 51 Java `postDelayed`/fixed-rate/Timer hits. Examples: call ring tones, live duration/countdown, playback watchdogs, upload cleanup and app wellbeing timer are not evidence of D1 polling. Document listener policy remains Long.MAX_VALUE with no periodic candidate; count and push retries are bounded. This is not a claim every possible runtime loop has been eliminated. Normal required network operations remain. Whole-app completed-day <=100k-read/<=5k-write acceptance is still unverified; this source fix does not change the existing strict daily FAIL or reset the backend proof date.
