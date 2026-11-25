# App profile reads no longer write an unused projection

Application source4b328a92f64e510d5bcb744936faef199ebb2923. Android compile/full JVM run45318025187 SUCCESS. Additional source guards7440db8; full436 Python tests PASS.

## Confirmed demand path

Fb.startMeListener processed a users/{actor} snapshot, saved/rendered the profile, then called syncProfileToCloudflare. That helper acquired an auth token and sent PUT /v1/me with display name/photo. It deduplicated only one profile tuple in process memory and marked the tuple before successful acknowledgement. A profile read could therefore create extra HTTP/auth/status/projection work, and cached then fresh profile values could cause separate write attempts.

Repository callsite tracing found no Android reader of GET /v1/me or friendbook_cf_profiles. Android profile rendering and saves use canonical users documents; the projection is a separate convenience endpoint. Worker readers of the projection are limited to /v1/me itself, not content/profile ACL or FCM payload lookups.

## Change

Removed the snapshot-to-projection call, its raw HTTP/token helper, memory key and dedicated executor from Fb. Cached/server profile delivery now updates local Models.User/FastCache and existing observers without initiating a second remote profile mutation. Canonical patchMe, missing-profile create-only bootstrap and ban/sign-out logic remain unchanged.

No projection table/data deletion or backend API removal: /v1/me GET/PUT and saveProfileProjection remain available, and existing compatibility /v1/docs profile writes retain their backend projection behavior. The deliberate Android behavior change is that passive canonical profile reads no longer maintain that unused convenience projection; no claim that the projection will stay synchronized by this Android reader.

## Verification

- Actual Fb.startMeListener callback path:100 alternating cached/server snapshots with changing name/photo update the local user and FastCache100 times, with zero extra document-store operations and zero projection token/auth acquisition. This is a callback test, not100 observed production launches or billed rows.
- Authoritative banned-user callback still clears local user/cache and signs out through FirebaseAuth.
- Source guards forbid the removed projection helper/executor/raw transport in Fb while retaining canonical writes, observers, bootstrap and ban handling.
- Backend projection API/projection helper presence guarded separately; no Worker code changed.
- Android full compile/JVM45318025187 SUCCESS;436 Python tests PASS; diff check PASS.

## Limits

This is an app-side read-induced write removal, not proof that it explains the whole reported spike. No APK build, Worker deployment, schema operation or PR merge. Installed APK178 still contains the prior behavior. Representative installed-client daily <=100kreads/<=5kwrites acceptance remains unverified.
