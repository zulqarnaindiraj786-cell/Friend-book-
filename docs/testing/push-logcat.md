# Evidence-led notification debugging

## Live inspection in this debugging session

Project: friendbook-push / iqtccjgpbtnfavbkbogr. Active gateway: send-fcm-notification v1.

Read-only SQL returned 2 device rows, 1 distinct registered Firebase UID, latest registration 2026-11-02 00:06:33.449 UTC, 0 delivery claims. Actual token table is `public.fcm_device_tokens`, not `fcm_tokens`. This proves neither registration of a second account nor delivery. Zero claims does NOT prove zero requests: authentication, event verification, missing recipient tokens or provider configuration can reject before claiming.

The connected Supabase tools expose function metadata/SQL, not Edge runtime request logs. Runtime logs were not read. No adb binary or attached Android device is available in the workspace. No real-device test or logcat output is claimed.

## Source path

`Reactions.apply` -> `reactRef.set(data).addOnSuccessListener` -> `Notify.send(post.authorId, ...)` -> Firestore notifications document success -> `FCMPush.notification` -> `PushDeliveryWorker` -> `PushGateway.call` -> `/functions/v1/send-fcm-notification` with Firebase bearer authentication.

New posts write authorId=myUid; Models.Post parses authorId from the saved document. That is code-level consistency, not a live verification of a particular failing post. Self-like, unlike/reaction switch (delta<=0), missing owner and changed account intentionally skip push and now log a reason. To test a new like, unlike first, then like a post owned by a DIFFERENT account.

Token path: App.onCreate -> PushManager.init -> FCMTokenManager.init auth listener -> registration work -> FirebaseMessaging.getToken -> Firebase ID token -> gateway register -> upsert fcm_device_tokens(device_id,user_id,token,updated_at). Server derives user_id from verified Firebase session, not a client-supplied UID. App startup/resume, token refresh and periodic maintenance also trigger automatic registration.

## Capture (new instrumented source build required)

Connect each phone to a computer with Android platform-tools and USB debugging authorized. No in-app Diagnostics/manual token registration is needed.

    adb devices -l
    adb -s SENDER_SERIAL logcat -v threadtime FriendBookPush:D WorkManager:V WM-WorkerWrapper:V '*:S' > sender-push.log
    adb -s RECEIVER_SERIAL logcat -v threadtime FriendBookPush:D WorkManager:V WM-WorkerWrapper:V '*:S' > receiver-push.log

The serials above are placeholders for values shown by `adb devices -l`. Login on both phones using different Firebase accounts; leave receiver in background; create a fresh cross-account like. Stop each capture with Ctrl+C. Filtered logs still require review before sharing.

## Expected stages, NOT captured device output

- Sender: auth_state -> registration_enqueue -> queue_requested -> queue_operation_ok -> worker_start -> fcm_token_ready -> http_start -> http_response -> gateway_result (registered=true).
- Like: reaction_write_ok (actor and receiver different; self=false; delta=1) -> notification_write_start -> notification_write_ok -> send_enqueue -> queue_requested -> worker_start -> http_start -> http_response -> gateway_result.
- Receiver: fcm_received -> display_submitted, or explicit display_skipped reason.

IDs are SHA-256 fingerprints (12 hex), not raw UIDs. Compare sender reaction receiver fingerprint to receiver auth_state actor fingerprint. No FCM token, bearer token, private key, name or message body is intentionally logged. queue_operation_ok is not proof that this new request ran: KEEP can retain an older active request. display_submitted is not proof the user saw a notification.

Interpretation:
- reaction_push_skipped reason=self_like: same Firebase account, even if using two phones.
- notification_write_failed: saved inbox event failed; inspect safe Firestore error code.
- queue_requested but no worker_start: scheduling/constraints/KEEP; inspect WorkManager logs.
- http_start without http_response: network/timeout/TLS or transport failure.
- 401: Firebase session rejected. 403: event ownership/rules rejection.
- 400 INVALID_OR_UNAVAILABLE_EVENT: Firestore lookup/parse/verification failure; backend runtime logs needed to distinguish.
- 409 RECIPIENT_NOT_REGISTERED: no tokens for the specific receiver UID.
- 200 accepted>0: FCM provider acceptance only. duplicate>0 alone is not a new send.
- fcm_received plus display_skipped: account mismatch, notification preferences, OS permission or duplicate suppression.

For server evidence, open Supabase Dashboard -> Edge Functions -> send-fcm-notification -> Invocations/Logs at the same time as the test. Record HTTP status and safe error code, not tokens/Authorization headers. An ACTIVE function alone is not evidence of invocation.
