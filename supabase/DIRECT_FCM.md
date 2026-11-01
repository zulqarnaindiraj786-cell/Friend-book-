# Direct FCM — v2.8.10 (42)

## Deployment status (2026-11-01)

**NOT ACTIVATED / device delivery NOT verified.** Supabase management API returned 502 during this session. Saving the service-account secret failed, and subsequent project/secrets checks also returned 502. Do not mistake the APK build or mocked tests for a working production deployment.

- Android OneSignal SDK removed; all notifications use FirebaseMessagingService.
- Gateway source: `functions/send-fcm-notification/index.ts`.
- Server credential: `FCM_SERVICE_ACCOUNT_JSON` only in Supabase secret storage, never Android assets/source, git, build arguments or logs.
- Correct Firebase project: **friendbook-17551**, not 17591.
- Per-device tokens are held in a private Supabase table and written through the Firebase-authenticated Edge Function; no public token reads or Realtime Database rules changes needed.
- The former OneSignal function is not called by this APK. Its remote deletion was not attempted during the API outage.

## Authorized operator steps once the API is available

1. In Supabase project **yferhqhgonbcgdewxlfs**, set `FCM_SERVICE_ACCOUNT_JSON` through Dashboard → Edge Functions → Secrets to the complete service-account JSON. Do not paste it into repo files or command history. Use a messaging-only service account if possible; rotate admin keys that are no longer needed.
2. Apply `202611010001_push_delivery.sql` if not already present, then `202611010002_fcm_devices.sql`. Token table RLS allows no public access. Service role access is server-only.
3. Deploy `send-fcm-notification` with `verify_jwt=false` (Supabase Auth gateway verifier disabled; **Firebase JWT verification is enforced inside**). The handler is single-file and imports jose from npm. Import `index.ts` as entrypoint; it must execute `Deno.serve(handler)`.
4. Verify Firebase Cloud Messaging API HTTP v1 enabled and the service account has permission to send for friendbook-17551. No Firebase Cloud Functions/Blaze is needed for this design. Supabase free-tier quotas still apply; neither cost nor delivery is guaranteed forever.
5. Install the APK and sign in. Open **Settings → Push notification diagnostics → Refresh FCM registration**. Check server status: registered_devices must be at least 1 and server_configured must be true. HTTP 401 indicates invalid Firebase session; 404 missing function; 503 missing secret/OAuth/FCM/provider issue; 409 recipient not registered. The app displays exact gateway codes and queues transient retries.
6. Tap **Send a test notification to myself**. Require `accepted >= 1` AND visible notification on the phone. FCM acceptance alone is not device receipt. If permission was denied previously, use **Open Android notification settings**; once-per-install permission state is intentionally not reset on update.
7. Repeat with two real accounts/devices: request, follow, post/reel reaction/comment, text/image/sticker/voice/group messages, friend Public/Friends post. Verify foreground, background, swiped-away, locked screen and tap routing. Test logout/account switching and no duplicates. Force-stop in Android Settings is expected to block delivery until relaunch.

## Reliability and limits

- WorkManager provides a durable outgoing queue with network constraint and exponential retry for transient failures (up to 9 executions). It does not guarantee immediate execution or recover an event if the sender process dies between saving it and enqueueing work. For fully server-originated event delivery, add a durable source-database event/outbox trigger.
- High-priority, 24-hour TTL, data-only FCM messages let Android enforce account checks, user preferences, event dedup, icon/sound and precise destinations before showing an alert. Google Play Services, connectivity, notification permission, channel settings and OEM power restrictions still govern receipt.
- Self-test is authenticated/self-only. Sender and destination for social/chat notifications are derived from persisted Firestore events, not client-provided titles/routing. OAuth token is minted and cached server-side. Never deploy a public anonymous broadcast endpoint.
- Claims are per event/device. Transient provider errors release claims for retry; exact stale tokens are removed on UNREGISTERED. A process crash after claiming but before sending can leave a claimed event unresolved; this is not an exactly-once delivery guarantee.
- Diagnostics use no raw FCM tokens, private key values or bearer tokens. No notification previews are written to diagnostics (only gateway counts/codes).

## Local tests

`npm ci --prefix supabase && npm test --prefix supabase` runs mocked tests for authentication rejection, registration ownership, missing recipients, actor validation, self-test routing, chat routing, transient retry and stale-token cleanup. These do **not** contact Firebase, Supabase or physical devices.
