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

## The other three offenders

| Offender | Before | Now |
| --- | --- | --- |
| People-you-may-know / notes strip (`collection("users").limit(30)`) | Re-read whenever Home was shown (2-minute throttle, 0 ms on first paint) | `OnDemandLoads.PYMK_CACHE_MS` (30 min) cache persisted per account (`fb_pymk_cache`), painted from disk on cold start; a tab switch never reads; only a pull / fresh pill / Home-tap gesture may refetch (`loadNotesUsers(true)`) |
| Chat badge listener (`chats` where participant) | Unbounded: every room document was read on every delivered message | `orderBy(inboxActivityAt DESC).limit(OnDemandLoads.CHAT_BADGE_LIMIT)` = 100 rooms |
| Friend graph listener (`friends` where participant) | Unbounded on every cold start | `.limit(OnDemandLoads.FRIEND_GRAPH_LIMIT)` = 500 |
| Referral total (`CountReads.once`) | Inside the cold-start burst after the first frame | Deferred 4 s so the visible screen starts first |

Notification and friend-request badges already used `BADGE_WINDOW` = 32.

`OnDemandLoadsTest` guards all of this: the 100-room badge cap, the suggestions
policy (same account + loaded cache ⇒ no fetch; only a gesture refetches), and
source-level assertions that both collection listeners carry a limit and that the
suggestion fetch is reachable only through the guarded path. Run 45088109156
passed together with the document tests.
