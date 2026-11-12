# Client-triggered real-event push — 2.8.15 (47)

This repository is native Android Java, not Flutter. No Firebase Cloud Functions, Blaze upgrade, SQL social tables/triggers, new server secrets or admin credentials in the APK are required. The existing Supabase `send-fcm-notification` gateway is reused unchanged.

## Flow and changes

- Post/reel reaction success -> persisted notification -> authenticated gateway send. Notifications are not dispatched on failed reaction writes. Optimistic UI remains immediate.
- Follow/unfollow uses a shared two-user WriteBatch across Profile, Feed, Reels and Live. Only successful follow commits emit a notification; unfollow emits none.
- Comment/reply callbacks already dispatch after CommentsEngine success; text/photo/voice/sticker messages already dispatch after batch commit; new post uploads dispatch after successful creation. These existing hooks remain intact.
- Chat recipient discovery and friend-post fan-out now run in a durable, account-tagged WorkManager job instead of an unretied success-only Firestore read callback. Failed lookups back off and retry. Friend-post fan-out uses stable notification IDs and a create-if-missing transaction, preserving read state/timestamp on replay.
- One-time delivery and fan-out request expedited execution on Android 12+ (normal work fallback when quota is exhausted). Older supported versions use normal work, avoiding an expedited-worker foreground-service requirement. Scheduling remains Android-controlled, not a promise of synchronous execution.
- The client submits receiverId, legacy target_user_id, title/body preview and persisted event identifiers. The existing gateway deliberately uses target_user_id plus verified Firestore event data, NOT untrusted preview text or receiver aliases; it finds private tokens server-side, checks sender/target/expiry and deduplicates by event/device. Display currently uses verified sender name as title and event text as body.
- The deployed gateway returns a generic 400 for ambiguous Firestore/event-read failures. The client now retries this specific error (bounded), without retrying malformed/forbidden events. This improves transient recovery but does not bypass denied Firestore rules.

## Verification

CI: all Python wiring/badge guards, retry-policy JVM tests, and mocked gateway tests including real like/comment/follow/friend-post routing, chat group routing, forged sender/text rejection, missing devices and transient failure responses.

Physical acceptance (not claimed by CI): update BOTH sender and receiver devices to 2.8.15, sign into different accounts with automatic registration and notification permission Allow. With B backgrounded, A likes/comments/follows/messages/posts. Verify B's actual receipt and tap routing. Repeat offline/reconnect, chat/group/voice/photo/sticker, failed/denied writes, unlike/unfollow, account switch, partial fan-out retry and Android 11/12+/13+.

## Client-only limits

There is still a crash window between a successful Firestore write callback and durable work enqueue. Killing/signing out/force-stopping the sender before dispatch can prevent delivery; this is not equivalent to a server database trigger or exactly-once delivery. FCM acceptance does not prove receipt. Permissions, target registration, preferences, OEM restrictions and Firestore rules still apply. No phone delivery was verified during development.
