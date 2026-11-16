import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import worker from '../../worker.js';
import {androidRelease} from '../android-release.js';
test('public APK metadata matches Android version; no database, auth or website needed',async()=>{
 const gradle=readFileSync(new URL('../../app/build.gradle',import.meta.url),'utf8');
 assert.match(gradle,new RegExp('versionCode '+androidRelease.versionCode+'\\b'));
 assert.ok(gradle.includes("versionName '"+androidRelease.versionName+"'"));
 const response=await worker.fetch(new Request('https://test.invalid/v1/android-release'),{});
 assert.equal(response.status,200);assert.deepEqual(await response.json(),androidRelease);
 assert.equal(response.headers.get('Cache-Control'),'no-store');
 assert.equal(androidRelease.packageName,'com.friendbook.social');
 // The app must never point people at a GitHub APK: Play is the only update path.
 assert.match(androidRelease.apkUrl,/^https:\/\/play\.google\.com\/store\/apps\/details\?id=com\.friendbook\.social$/);
 // minVersionCode is the oldest build allowed to talk to this backend, so it only ever
 // moves up when an old build stops working. Tying it to the current version would block
 // every user the moment a new build ships, which is the opposite of a forced update.
 assert.ok(androidRelease.minVersionCode <= androidRelease.versionCode,
   'minVersionCode must never exceed the published version');
});
test('release metadata cannot be changed by callers',async()=>{
 assert.equal((await worker.fetch(new Request('https://test.invalid/v1/android-release',{method:'POST',body:'{}'}),{})).status,405);
 assert.equal((await worker.fetch(new Request('https://test.invalid/v1/android-release/private'),{})).status,404);
});
