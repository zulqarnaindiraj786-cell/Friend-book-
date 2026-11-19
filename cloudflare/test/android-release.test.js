import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import worker from '../../worker.js';
import {androidRelease} from '../android-release.js';
test('public Play metadata does not advertise an unreleased Android build',async()=>{
 const gradle=readFileSync(new URL('../../app/build.gradle',import.meta.url),'utf8');
 const candidate=Number(gradle.match(/versionCode\s+(\d+)/)?.[1]);
 assert.ok(androidRelease.versionCode<=candidate);
 const response=await worker.fetch(new Request('https://test.invalid/v1/android-release'),{});
 assert.equal(response.status,200);assert.deepEqual(await response.json(),androidRelease);
 assert.equal(response.headers.get('Cache-Control'),'no-store');
 assert.equal(androidRelease.packageName,'com.friendbook.social');
 assert.equal(androidRelease.storeUrl,'https://play.google.com/store/apps/details?id=com.friendbook.social');
});
test('release metadata cannot be changed by callers',async()=>{
 assert.equal((await worker.fetch(new Request('https://test.invalid/v1/android-release',{method:'POST',body:'{}'}),{})).status,405);
 assert.equal((await worker.fetch(new Request('https://test.invalid/v1/android-release/private'),{})).status,404);
});
