# Live and login-history demand follow-up

## Changes

- Live discovery replaces its 40-room listener with user-requested pages of 10. A newly opened screen obtains an authoritative first page even when a cached page exists; no later page is prefetched. Search/category and count labels explicitly refer to loaded broadcasts, and Refresh is available. Live discovery can still be stale until opened or refreshed; it is not described as a global live count.
- Login history replaces a 30-item listener with cached, explicit ten-item pages. Login history remains distinct from revocable sessions. Neither screen owns a list listener after stop.
- Open live rooms now remove their D1 room/chat listeners when the activity stops, stop queued PK refreshes, and reattach/refresh once on a later visible start after the relay is ready. Late PK callbacks are discarded while hidden or under a different actor. On account change, teardown must not write a previous user's room using the new account.
- The initial live-room create already contains initial heartbeat, viewer IDs and peak; a redundant immediate heartbeat update was removed. A finishing activity skips the stop heartbeat because host teardown ends the room. Viewer membership changes retain their event-driven heartbeat write.
- All live reaction types share an actor-and-room one-second write cooldown. Local visual reactions remain immediate; suppressed rapid taps intentionally do not send a D1 reaction packet. This is process-local and not a cross-device write limiter.

## Verification

- Android compilation and complete JVM suite for `12282cc`: https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45341855864 — success. The added Robolectric test checks live discovery fetches a fresh ten-item head without automatically requesting the next page. Existing cooldown and pager tests run in the same suite.
- Python: 486 tests passed. New live lifecycle, actor guards, heartbeat and reaction checks are source-level wiring tests; they are not a live-video/device end-to-end test or a measurement of D1 billed rows.
- Earlier cancelled Android runs were superseded by the successful final run. No APK, deployment, database change, production probe or PR merge was performed. Installed APK 181 does not contain these client changes.

## Still open

This is an incremental fix, not proof that all application paths or billed quota are fixed. Admin/financial totals, moderation, settings insights/account deletion, live invitations and other actions still require correctness-preserving work and tests. The prior post-reset 20.5k read/504 write report remains unresolved at device-level attribution. The requested <=100k reads / <=5k writes per representative completed day has not been measured after these client changes. Do not infer billed-row savings from requested page sizes or test counts.
