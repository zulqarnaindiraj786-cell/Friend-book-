# Direct FCM — activated project, v2.8.12 (44)

## Deployment record — 2026-11-01

- Project: **friendbook-push**, `iqtccjgpbtnfavbkbogr`.
- App gateway: `https://iqtccjgpbtnfavbkbogr.supabase.co/functions/v1/send-fcm-notification`.
- Function created ACTIVE, version 1. Gateway Supabase Auth JWT verification is disabled because the app uses Firebase Auth; the handler verifies Firebase token signature, issuer, audience and expiry before processing every POST.
- Secret `FCM_SERVICE_ACCOUNT_JSON` was set by the user through Supabase Dashboard. It remains server-side only. No credential value was retrieved or placed in the app/repository.
- Token/claim tables created; token RLS enabled, anon/authenticated roles cannot read token table, service_role can execute claim_push.
- Live temporary diagnostic confirmed: JSON parses, project matches friendbook-17551, PKCS8 key parses, Google OAuth HTTP 200, FCM HTTP v1 **validate_only** HTTP 200, anonymous POST 401, invalid JWT POST 401. The temporary diagnostic function was deleted after testing.
- FCM dry-run topic was `friendbook_setup_validation` with `validate_only:true`: **no notification was delivered** and no subscription required/created.
- Registered device count at verification: **0**. Physical device delivery and authenticated app registration still need the new APK and user testing. No synthetic device/account/token was added.
- Old API 502 errors applied to the earlier deployment attempt; this new-project deployment succeeded.

## Automatic setup — v2.8.14 (46)

The Diagnostics screen/manual registration workflow has been removed. On app bootstrap Firebase Messaging auto-init is enabled. Restored/new Firebase login automatically queues device registration to this project's token table. Main resume, permission grant and token refresh also register automatically. Registration work waits for connectivity and retries transient errors without the old 9-attempt cutoff; unique 12-hour maintenance refreshes registration in the background. A rejected Firebase session is force-refreshed and retried once before backoff. Token-refresh events replace stale queued registration. Logout cancels the user's queued/periodic work; account checks prevent jobs from running under another user.

Permission is requested natively on Android 13+ after signed-in Main becomes focused for 2 seconds, including when focus returns after consent UI. Existing once-per-install denial behaviour is preserved: no repeated nagging and no forced permission grant. Users must choose Allow for system notifications. Normal notification preferences remain in Settings, with optional local sound preview (not a registration step).

## Install and acceptance test

1. Install v2.8.14 (46), sign in and tap Allow on the Android notification dialog. Do not open Settings or trigger any manual registration.
2. Operator verifies a token-table row appears automatically for the signed-in Firebase UID. Do not expose raw device tokens in screenshots/logs.
3. On phone A send a message/like/comment/request/follow to B. Check B in foreground, background, swiped-away and screen locked. Tap notifications: correct post/reel/chat/request. Repeat with network initially offline, then reconnect, with no Settings intervention.
4. Exercise token refresh, app restart, account switch and logout: latest device registration must belong only to the current account; stale jobs must not run for another UID.
5. Permission already denied/OS mute/DND/Force stop are system restrictions, not situations the app can bypass. Updates intentionally preserve earlier permission choices. Physical-device delivery must be tested separately from build/server acceptance.

## Reliability/security

- Direct FCM; no Android OneSignal SDK, no Google admin credentials in APK, no Firebase Cloud Functions/Blaze requirement. Supabase free-tier usage limits apply.
- Private per-device token table is accessed only by the server service-role credential. Registration derives account ownership from verified Firebase UID, never a body user ID. Account-switch registration reassigns the installation.
- WorkManager outbox survives normal sender process death after enqueue, constrains to network and retries outgoing events up to 9 executions and registration without that cutoff. Sender death between saving an event and enqueueing still requires a future server-side event/outbox integration for guaranteed recovery.
- Messages use high-priority data-only FCM and 24-hour TTL so the Android service checks account, preferences and dedup before displaying the original chime, icon and precise destination. Delivery time is not guaranteed.
- Social/chat events are verified against Firestore and canonical sender/content/routing is derived server-side. Self-test is authenticated and self-only.
- Claims are per event/device, with per-actor rate limiting. Transient failures release claims for retry; exact stale tokens are removed for UNREGISTERED. A server crash after claiming but before sending can leave an unresolved claim; not an exactly-once guarantee.
- Do not log raw FCM tokens, OAuth tokens, service keys or private keys. Keep secrets out of assets/git/build variables. Historical OneSignal function on the old project is not called by this APK.

## Tests

`npm ci --prefix supabase && npm test --prefix supabase` runs local mocked authentication/registration/ownership/routing/retry/stale-token/status tests. Live OAuth and FCM validate-only tests are additional checks, not substitutes for physical-device acceptance above.

## Real social events: client-only v2.8.15 (47)

No Blaze/Cloud Functions are installed. Native Java write-success hooks dispatch through the existing gateway; chat and friend-post recipient lookup now have durable retry, follow/reaction pushes wait for successful writes, and Android 12+ can expedite delivery work. Client `receiverId`/title/body are previews alongside the existing target/event contract, not permission to send arbitrary text. See [client-push acceptance and limitations](../docs/testing/client-push.md). This does not require a Supabase migration or Edge Function redeploy.
