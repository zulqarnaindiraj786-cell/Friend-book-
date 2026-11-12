import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import worker from '../../worker.js';
import {androidRelease} from '../android-release.js';

/** applicationId is read from the build file, so this guard can never drift from the line it runs on. */
const applicationId = (gradle) => {
  const match = /applicationId\s+['"]([^'"]+)['"]/.exec(gradle);
  assert.ok(match, 'app/build.gradle must declare an applicationId');
  return match[1];
};

test('public APK metadata matches Android version; no database, auth or website needed',async()=>{
 const gradle=readFileSync(new URL('../../app/build.gradle',import.meta.url),'utf8');
 assert.match(gradle,new RegExp('versionCode '+androidRelease.versionCode+'\\b'));
 assert.ok(gradle.includes("versionName '"+androidRelease.versionName+"'"));
 const response=await worker.fetch(new Request('https://test.invalid/v1/android-release'),{});
 assert.equal(response.status,200);assert.deepEqual(await response.json(),androidRelease);
 assert.equal(response.headers.get('Cache-Control'),'no-store');
 // The metadata must describe THIS line's package, whatever the build file says.
 assert.equal(androidRelease.packageName,applicationId(gradle));
 assert.match(androidRelease.apkUrl,/^https:\/\/github\.com\/zulqarnaindiraj786-cell\/Friend-book-\/releases\/latest\/download\/friendbook\.apk$/);
});
test('release metadata cannot be changed by callers',async()=>{
 assert.equal((await worker.fetch(new Request('https://test.invalid/v1/android-release',{method:'POST',body:'{}'}),{})).status,405);
 assert.equal((await worker.fetch(new Request('https://test.invalid/v1/android-release/private'),{})).status,404);
});
