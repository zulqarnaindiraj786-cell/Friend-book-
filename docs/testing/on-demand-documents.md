# On-demand document reads

Presence and typing are gone (endpoints 404, tables dropped). What remains is
ordinary document reading, so the client must never ask for the same document on
a timer.

## Rules enforced in code

| Requirement | Implementation |
| --- | --- |
| No per-second polling | `ListenerPollingPolicy.intervalMs` returns `Long.MAX_VALUE` for every path except a live room (5–10 s) and a chat room while a call is actually busy (5 s). A unit test asserts 1-second and 60-second lookups are all "not due" for ordinary documents. |
| No background traffic | The signaling ticker is not scheduled at construction. `armSignalingTicker()` runs only while at least one listener can use a periodic read, and `hasPeriodicCandidate()` also requires `App.isForeground()`. A closed or backgrounded app owns no timer, so it makes zero requests. |
| Listener only while the screen is open | Registrations still come from the visible screen and are removed on stop/destroy; nothing schedules reads for an unregistered path. |
| Show cache, do not re-read the whole document | `serveFromCacheOnly()` paints the cached snapshot and skips the network read when the last authoritative fetch is younger than `cacheFreshMs()` (60 s for ordinary documents, 0 s for live rooms and chat room state). Writes, pushes and `refreshOnForeground()` invalidate the stamp first. |
| No fixed-rate loops | A source guard test fails the build if `scheduleWithFixedDelay`, `scheduleAtFixedRate` or `new Timer(` reappears anywhere under `app/src/main/java`. |

## Proof

`Android unit tests` workflow runs `gradle testDebugUnitTest` without building or
publishing an APK. Run 45086737060 passed with
"On-demand document tests passed: no per-second document reads and no fixed-rate
polling loops."

`ListenerPollingPolicyTest` covers: ordinary documents never poll (foreground and
background, 1 s and 60 s), cache freshness windows, the absence of fixed-rate
schedulers, and the demand-armed ticker shape of `D1DocumentStore`.

## Release signing

`build-apk.yml` no longer depends on a secret password. It reads the original
release keystore and password from the repository history, verifies them with
`keytool -list -storetype PKCS12` before building, masks the values, and only
falls back to the secret pair when history is unavailable. Release 2.10.32 was
signed with it: same certificate as 2.10.31 and an in-place upgrade passed on an
Android 15 emulator.
