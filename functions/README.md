# Production FCM activation

The Android app alone cannot send secure remote pushes. Deploy these server-side Firestore triggers to **friendbook-17551**. Never embed service account keys or FCM server credentials in the APK.

## One-time deployment (project owner / authorized operator)

1. Enable the Firebase **Blaze billing plan**, Cloud Functions, Cloud Build, Artifact Registry and Firebase Cloud Messaging API (HTTP v1) for the project. Use Node 22 and an authorized Firebase CLI login.
2. From the repository root:
   ```sh
   npm ci --prefix functions
   npx firebase-tools deploy --project friendbook-17551 --only firestore:rules,functions:friendbook-push
   ```
   Review the checked-in Firestore rules before deployment. The added `pushDevices/{uid}/devices/{deviceId}` rules are required for token registration.
3. Install the new APK, sign in, and allow notifications. Verify an owner-only device record exists with token/platform/updatedAt. Each installation has its own registration; tokens refresh on login, app resume and FCM rotation. Sign-out removes the installation registration and clears displayed notifications; account filtering also rejects stale deliveries.
4. Use a second account/device to send a friend request, text/group/attachment message, post/reel reaction, post/reel comment and publish a Public/Friends post as an accepted friend. Repeat with recipient app foreground, background, swiped away, screen locked and after switching accounts. Tap each alert to verify the exact destination and back navigation.
5. Check Cloud Functions logs and Android Logcat `FriendBookPush` for errors. Invalid tokens are removed, transient server delivery failures retry, client event IDs suppress duplicate retry alerts.

## Behavior and limits

- Social triggers use the existing `notifications` documents; chat triggers use newly created messages; friends' posts fan out using accepted `friends.participants` records. Existing historical documents do not trigger pushes on deployment.
- Private (Only me), group/page and safety-flagged posts are excluded from friend-post fanout. Self alerts are skipped except login/security alerts. Recipient block lists are respected.
- Data-only, high-priority Android FCM messages let the service enforce current-account checks and recipient notification settings before showing an alert, in foreground and background. Text is bounded and no sender preview of a new post is included.
- Crown-F monochrome small icon, high-importance notification channel, default system notification sound/vibration. Android's notification settings, silent/DND mode and user channel choices take precedence.
- Android 13+ notification permission is requested by the main activity. If denied, enable it in Android Settings → Apps → friendbook → Notifications.
- **Force stop** in Android Settings blocks delivery until the user opens the app again. Delivery is not guaranteed offline, without Google Play Services or under manufacturer battery restrictions; high-priority FCM is not an absolute delivery-time guarantee.
- This checkout does not contain deployment credentials. A successful APK CI build does **not** mean the Functions or Firestore rules have been deployed. Verify all three functions in Firebase Console before claiming production delivery.

## Tests

`npm test --prefix functions` checks payload string compatibility, stable dedup IDs and private-post exclusions. Real-device delivery and authenticated Firestore rules need the project deployment/device test above.
