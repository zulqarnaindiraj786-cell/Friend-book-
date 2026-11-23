# Required Google Play updates

Build 122 (2.8.90) is the first candidate with the required-update gate. Older binaries without this implementation cannot be made mandatory retroactively. Users of those binaries must first install a version containing the gate.

The signed-in main screen asks Google Play asynchronously on resume. A newer version must be available to the current installation/account before the non-dismissible dialog is shown. The button opens the official Google Play listing; it does not download an APK. If Play cannot verify an update or the user is offline, app startup is not held pending the query. The Worker release metadata is not an input to the mandatory gate; do not advance that metadata for an unpublished build.

## Play device acceptance

1. Install the gate-enabled baseline **from Google Play** using an enrolled test account.
2. Make a same-signing-key build with a higher version code available to that account in a Play testing track. Wait for Play propagation.
3. Open the baseline online. Verify that Main is blocked, Back/outside tap cannot dismiss the dialog, and Update opens the FriendBook listing in Play Store. Returning without updating must leave the gate in place.
4. Update from Play and open the app again. Verify the prompt is gone and existing account data is retained.
5. Test an account not offered the newer build, and a fresh offline launch: neither should be blocked by an unverified update.
6. On a throttled connection, load Home and immediately open Friends. Confirm no Home retry toast appears over Requests, request rows use stored sender details where available, and inline suggestions load only on tap. Return to Home and verify late feed data still renders and cached posts remain usable.
