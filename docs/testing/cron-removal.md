# Presence and cron removal

Date: 2026-11-22. Scope: remove online/offline status everywhere and stop all
cron triggers. No other feature was changed.

## Android app

The presence client was already deleted in earlier work (`CloudPresenceSocket`,
`Presence`, `PresencePolicy`, `PresenceSessionPolicy`, `PresenceSocketController`,
`PresenceWritePolicy`, `view_presence_avatar`, `view_last_active`,
`presence_dot`). This change removes the last three visible leftovers:

| Item | Before | Now |
| --- | --- | --- |
| Composer avatar green dot (`FeedAdapter.HeaderVH`) | drawn always | removed |
| `Ui.onlineDot(Context)` helper | public unused helper | deleted |
| Settings row "Active status is off / Online green dot and last seen" | shown | removed |

No `presence`, `/v1/presence`, `onlineDot`, "Online", or "Last seen" string is
left under `app/src/main`. Chat read receipts ("Seen") are a different feature
and were not touched. Chat, feed, auth, live and messages were not modified.

Verification: `cloudflare/test/presence-and-cron-removal.test.js` scans every
`app/src/main` Java/XML source plus the Worker runtime for `/v1/presence`,
`presence`, and `onlineDot`, and fails on any hit. The release/debug APK is also
scanned by `.github/workflows/presence-cron-proof.yml` (`classes*.dex` must
contain zero `presence` hits) so the compiled artifact is checked, not just the
sources.

## Worker

* `/v1/presence/*` routes were already deleted; `cloudflare/test/worker.test.js`
  and the new test assert they return 404 and never touch D1.
* No Worker runtime file reads or writes `friendbook_cf_presence`,
  `friendbook_cf_presence_sessions`, `friendbook_cf_typing*` any more. The
  tables themselves are dropped by migration `0086` (not applied here).
* Old APKs can still send five stale status fields (`isOnline`, `isActive`,
  `showActiveStatus`, `lastSeenMs`, `lastSeen`) inside a profile PATCH. The
  compatibility guard now uses neutral naming and still ignores exactly that
  shape; it does not touch any status table.

## Cron

`wrangler.toml` no longer has a `[triggers]` block and `worker.js` no longer
exports `scheduled`, so no cron runs after the next Worker upload.

Consequences that the owner must accept (they follow directly from "no cron"):
* the 15-minute R2/Cloudinary temporary-video cleanup retry no longer runs;
* the per-minute official-broadcast pump (`resumeOfficialBroadcasts` /
  `drainBroadcastPush`) no longer runs, so queued official-broadcast pushes only
  move when a matching request path drains them;
* `cloudflare/maintenance.js` is kept (with its safety test) but is currently
  unreachable, so it can be re-wired on demand later.

## Proof status

Static and build proof (run 45028418352, commit on this branch):
* `npm test` 230/230, including the new scan for `/v1/presence`, `presence`
  and `onlineDot` in every app source file and Worker runtime file.
* `:app:assembleDebug` succeeded; the compiled `classes*.dex` contains no
  `/v1/presence`, no `com/friendbook…presence` class and no `onlineDot`
  identifier. Nine raw `presence` substrings remain in third-party library
  strings; the scan fails only on our own identifiers.
* `wrangler.toml` has no `[triggers]` block and no `crons=`, and `worker.js`
  exports no `scheduled` handler (checked by importing the Worker in CI).

Deployed cron state:
* `GET …/workers/scripts/rough-firefly-dd7b/schedules` returned two triggers
  (`* * * * *`, `*/15 * * * *`) before the change.
* `PUT` with an empty array cleared them; a re-read immediately after and 75
  seconds later both returned zero triggers, so no cron can run.
* A two-minute `wrangler tail` sample after that change reported
  `cron_invocations: 0`, `other_non_http_events: 0`.

Residual, not fixable from this repository alone:
* A deployed Worker still runs the previous code until it is uploaded, so
  `/v1/presence/socket` can still answer (a 403 was sampled). Deleting those
  routes needs a Worker upload, and the existing deploy workflow applies
  migration `0086` (the seven-table DROP) in the same run.
* Installed old APKs keep calling status endpoints; those request lines appear
  in Cloudflare logs even when the Worker answers 404. Only an app update
  removes them.
* Emulator proof of "ChatList open for two minutes" was not possible here: the
  sandbox has no Android SDK, and the app requires a real login on the emulator.
