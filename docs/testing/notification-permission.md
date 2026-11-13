# v2.8.9 (39): Android notification permission acceptance checklist

- Fresh install, Android 13/14/15/16: sign in and keep Main visible. After 2 seconds, one native Android notifications permission dialog appears via OneSignal `requestPermission(true)`.
- Allow: push permission granted; app continues normally. Relaunch/recreate Main: no repeat dialog.
- Deny/dismiss: app remains usable without crashing. Relaunch, sign out/in, reopen Main or switch tabs: no automatic repeat dialog or settings loop. Manually enable notifications in Android system settings if desired.
- Already allowed: no dialog. If later revoked manually, request only occurs if this install has not already attempted it.
- Leave Main before two seconds / lock phone / background app: pending callback is cancelled; no background dialog. Return to Main: two-second timer starts again. Dialogs currently obscuring Main defer the attempt until a later focused resume.
- Android 12 and below: no runtime notification permission request.
- Uninstall/reinstall with cloud restore or device transfer: dedicated SharedPreferences prompt history is excluded from backup so a new install may prompt again. Clearing app data also resets it. In-place updates do not reset it.
- Confirm OneSignal App ID remains 6921b7fa-7c0a-40e3-8dd9-e698429a038a and no OneSignal REST secret is in the APK/repository.
- Test real push receipt separately after granting permission; build success does not establish provider acceptance/device receipt.

Implementation: MainActivity's resumed lifecycle schedules/cancels a main-thread callback; NotificationPermissionPrompt checks SDK, activity/focus, OS grant and install-scoped SharedPreferences, persists the attempt before requesting, and safely records allow/deny/error via OneSignal's Java continuation. There is no separate native request call because OneSignal itself invokes the native Android permission flow; issuing both would risk duplicate prompts.
