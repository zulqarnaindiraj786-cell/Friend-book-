> Current production route: **direct FCM on friendbook-push** (`iqtccjgpbtnfavbkbogr`). See [DIRECT_FCM.md](DIRECT_FCM.md) for deployment verification and device testing. The OneSignal instructions below are historical and are not used by the current Android build.

# OneSignal production activation

No OneSignal REST credential belongs in Gradle, Java, git, the APK or CI logs. The public App ID is `6921b7fa-7c0a-40e3-8dd9-e698429a038a`.

## Required setup by an authorized Supabase operator

1. Connect the target Supabase project and link this checkout (`supabase link --project-ref YOUR_PROJECT_REF`). Apply the migration with `supabase db push` after reviewing it.
2. In Supabase Dashboard → Edge Functions → Secrets, set `ONESIGNAL_REST_API_KEY` to a **rotated** provider REST key. A key pasted into chat should be rotated. Alternatively use `supabase secrets set --env-file` with a temporary file outside this repository. Do not paste keys into tracked scripts or shell history. Built-in `SUPABASE_URL` and `SUPABASE_SERVICE_ROLE_KEY` remain server-side.
3. Deploy `supabase functions deploy send-onesignal-notification --no-verify-jwt`. Gateway Supabase JWT verification is disabled because the app uses **Firebase Auth**. The handler verifies the Firebase token signature, audience, issuer and expiry itself. Unauthenticated calls are rejected. Keep the checked-in authentication code intact.
4. Set the public GitHub Actions repository variable `SUPABASE_URL` to the real project URL (`https://PROJECT_REF.supabase.co`) and rebuild the APK. This is a public endpoint, not a secret. Without this URL the build is installable but sending fails closed with `FriendBookPush` diagnostics. No project URL is fabricated or assumed.
5. In OneSignal configure Android Firebase/FCM v1 credentials for **friendbook-17551** and this App ID. Verify the subscription becomes subscribed on a real Google Play Services device. The Firebase google-services.json bundled in the APK is **not** a substitute for OneSignal's server FCM credentials. Consider OneSignal identity verification for external IDs before broad rollout.
6. Do not simultaneously deploy the previous `functions/` direct-FCM delivery triggers; that is an alternative backend, not a second delivery path.

## Design

Native Java uses HTTPS `/functions/v1/send-onesignal-notification`, equivalent to `supabase.functions.invoke`. Firebase UID becomes OneSignal external user ID at login; logout unlinks the installation. Existing Android 13 notification permission prompt remains. OneSignal displays foreground/background notifications; foreground settings are checked and clicks route through the existing account/PIN-protected router.

Requests contain `{ target_user_id, title, message, data }`. The Edge Function verifies a recent persisted social notification or chat message and derives the actual sender/title/content/routing from Firestore instead of trusting caller text. Social events include notificationId; messages include chatId/messageId. Service-role-only SQL claims suppress replay and limit each actor to 60 accepted recipients/minute. Claims currently persist and should be pruned using a scheduled database cleanup after an appropriate audit retention period. A claimed event rejected by the provider is not automatically retried; inspect diagnostics and re-test with a new event after fixing configuration.

For background notifications Android and OneSignal channel preferences control sound, vibration, mute and delivery. Android Settings force-stop prevents delivery until reopening. No guarantee can be made for offline devices or manufacturer power restrictions. Client-triggered sends require sender connectivity and a running sender process; guaranteed delivery after sender process death requires a server event/outbox integration.

## Acceptance test — TWO REAL DEVICES REQUIRED

- Install the newly configured signed APK on A and B; log in with separate Firebase accounts, allow notifications, verify both subscriptions/external IDs in OneSignal.
- A sends a friend request, follows B, likes/reacts/comments on B's post/reel, and sends text/image/sticker/voice/group messages. B receives exactly one push with icon and sound in foreground, background, screen locked, and after swiping the app away.
- Tap each notification: correct post/reel, chat or request tab, respecting app PIN/account. Test sign-out/account switching and disabled notifications too.
- Check Edge Function HTTP responses and OneSignal delivery reports. `accepted: true` only proves provider acceptance, NOT actual device delivery.
- Signed APK build success does not verify deployed secrets, Supabase migrations, OneSignal FCM credentials or real-device receipt. These remain unverified until the project is connected/configured and this test is completed.


## Deployment record — 2026-11-01

- User-selected project: `supabase-gray-lamp` (`yferhqhgonbcgdewxlfs`).
- Function `send-onesignal-notification`: confirmed ACTIVE, version 2; Firebase JWT verification is performed inside the handler.
- `ONESIGNAL_REST_API_KEY`: updated using the Supabase secret API; value never written to the checkout.
- Database guard table `push_delivery_claims` and function `claim_push(text,text)`: existence verified by SQL.
- Live endpoint GET check returns `{ "error": "POST required" }`, confirming the handler is running.
- Public project URL is now the Gradle fallback, so a missing Actions variable no longer produces an empty endpoint. An explicit SUPABASE_URL build environment override still works.
- Uses current OneSignal `Authorization: Key` and `include_aliases.external_id` with `target_channel: push` for the supplied v2 app key.
- Real Firebase-authenticated event sending, provider acceptance, and receipt on two physical devices remain unverified. The endpoint reachability check is not a push delivery test.
