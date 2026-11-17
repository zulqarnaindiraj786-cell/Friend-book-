// Public release metadata only. No users, database, credentials or migration control.
//
// minVersionCode is the oldest build allowed to talk to this backend. Anything below it gets a
// blocking update screen that only offers the Play Store. Raise it here, not in the app, so the
// floor can move without shipping a build - but note that a build too old to be told about the
// floor is exactly the build this protects against, which is why the app also hard-codes a
// minimum it can enforce offline.
export const androidRelease = Object.freeze({
  packageName: 'com.friendbook.social',
  versionCode: 138,
  versionName: '2.10.2',
  minVersionCode: 126,
  apkUrl: 'https://play.google.com/store/apps/details?id=com.friendbook.social',
});
