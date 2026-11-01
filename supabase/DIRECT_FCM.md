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

## Install and acceptance test

1. Install **v2.8.12 (44)** on both phones, sign in with different accounts and allow notifications. Earlier APKs still call the old Supabase project and cannot register here.
2. Open **Settings → Push notification diagnostics → Refresh FCM registration**. Wait a moment, then **Check server status**. Expect HTTP 200, `registered_devices >= 1`, `server_configured:true`, `google_oauth_verified:true`. Screen displays the new project URL.
3. Tap **Send a test notification to myself**. Require `accepted >= 1` AND a visible notification on the phone. A repeated test within the same minute may return duplicate due to event dedup; wait until the next minute to test again. Acceptance is not proof of device display.
4. On phone A, send a message/like/comment/request/follow to B. Check B in foreground, background, swiped-away and screen locked. Tap notifications: verify correct post/reel/chat/request. Test account switching, sign-out, mute and no duplicates.
5. If Android notifications were previously denied, use **Open Android notification settings**. Updates intentionally do not reset the once-per-install prompt. Local sound preview does not test the backend.
6. HTTP 401: sign in again. 409: recipient has no valid registered token; install/register both phones. 503: inspect the error code (credential/OAuth/token-store/provider issue). Android Settings Force stop blocks push until reopening. Google Play Services, channel settings/DND and manufacturer power restrictions also apply.

## Reliability/security

- Direct FCM; no Android OneSignal SDK, no Google admin credentials in APK, no Firebase Cloud Functions/Blaze requirement. Supabase free-tier usage limits apply.
- Private per-device token table is accessed only by the server service-role credential. Registration derives account ownership from verified Firebase UID, never a body user ID. Account-switch registration reassigns the installation.
- WorkManager outbox survives normal sender process death after enqueue, constrains to network and retries transient failures up to 9 executions. Sender death between saving an event and enqueueing still requires a future server-side event/outbox integration for guaranteed recovery.
- Messages use high-priority data-only FCM and 24-hour TTL so the Android service checks account, preferences and dedup before displaying the original chime, icon and precise destination. Delivery time is not guaranteed.
- Social/chat events are verified against Firestore and canonical sender/content/routing is derived server-side. Self-test is authenticated and self-only.
- Claims are per event/device, with per-actor rate limiting. Transient failures release claims for retry; exact stale tokens are removed for UNREGISTERED. A server crash after claiming but before sending can leave an unresolved claim; not an exactly-once guarantee.
- Do not log raw FCM tokens, OAuth tokens, service keys or private keys. Keep secrets out of assets/git/build variables. Historical OneSignal function on the old project is not called by this APK.

## Tests

`npm ci --prefix supabase && npm test --prefix supabase` runs local mocked authentication/registration/ownership/routing/retry/stale-token/status tests. Live OAuth and FCM validate-only tests are additional checks, not substitutes for physical-device acceptance above.
