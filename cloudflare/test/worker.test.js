import { test } from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPair, exportJWK, createLocalJWKSet, SignJWT } from 'jose';
import worker, { createWorker, verifyFirebaseToken } from '../../worker.js';
const call = (path, env = {}, headers = {}, method = 'GET', app = worker) =>
  app.fetch(new Request(`https://example.invalid${path}`, { method, headers }), env);
test('health executes only a constant read and never exposes data', async () => {
  const response = await call('/health', { DB: { prepare(sql) {
    assert.equal(sql, 'SELECT 1 AS connected'); return { first: async () => ({ connected: 1 }) };
  } } });
  assert.equal(response.status, 200);
  // Safe feature/binding flags only: confirms the D1 interaction gate and view-counter bindings,
  // without returning any records, counts, IDs or user data.
  assert.deepEqual(await response.json(),
    { ok: true, d1InteractionsEnabled: false, views: { ae: false, kv: false, r2Snapshot: false, aeReadToken: false } });
  assert.equal(response.headers.get('Cache-Control'), 'no-store');
});
test('missing binding and failed DB return no internal details', async () => {
  assert.equal((await call('/health')).status, 503);
  const response = await call('/health', { DB: { prepare() { throw Error('secret'); } } });
  assert.equal(response.status, 503);
  assert.deepEqual(await response.json(),
    { ok: false, d1InteractionsEnabled: false, views: { ae: false, kv: false, r2Snapshot: false, aeReadToken: false } });
});
test('health reports D1 interactions enabled without exposing data', async () => {
  const db = { prepare: () => ({ first: async () => ({ connected: 1 }) }) };
  const response = await call('/health', { DB: db, D1_INTERACTIONS_ENABLED: 'true' });
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(),
    { ok: true, d1InteractionsEnabled: true, views: { ae: false, kv: false, r2Snapshot: false, aeReadToken: false } });
});
test('health reports the R2 snapshot fallback when Workers KV is not bound', async () => {
  const db = { prepare: () => ({ first: async () => ({ connected: 1 }) }) };
  const response = await call('/health', { DB: db, ANALYTICS_ENGINE: {}, VIDEO_BUCKET: {}, CF_AE_TOKEN: 'tok' });
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(),
    { ok: true, d1InteractionsEnabled: false, views: { ae: true, kv: false, r2Snapshot: true, aeReadToken: true } });
});
test('unknown and write routes are closed', async () => {
  assert.equal((await call('/v1/users')).status, 404);
  assert.equal((await call('/health', {}, {}, 'POST')).status, 405);
});
test('profile route requires verified Firebase identity before accessing D1', async () => {
  const env = { FIREBASE_PROJECT_ID: 'friendbook-17551', DB: { prepare() { assert.fail('DB accessed'); } } };
  assert.equal((await call('/v1/me', env)).status, 401);
  assert.equal((await call('/v1/me', env, { Authorization: 'Bearer invalid' })).status, 401);
});
test('profile lookup binds only verified UID, not caller-supplied UID', async () => {
  const app = createWorker(async (token, project) => {
    assert.equal(token, 'test'); assert.equal(project, 'friendbook-17551'); return 'verified-user';
  });
  const env = { FIREBASE_PROJECT_ID: 'friendbook-17551', DB: { prepare(sql) {
    const statusRead=sql.includes('friendbook_cf_firestore_documents') || sql.includes('friendbook_cf_documents');
    assert.ok(statusRead || sql.endsWith('WHERE uid = ?'));
    return { bind(uid) { assert.equal(uid, statusRead ? 'users/verified-user' : 'verified-user'); return { first: async () => null }; } };
  } } };
  const response = await call('/v1/me?uid=victim', env, { Authorization: 'Bearer test' }, 'GET', app);
  assert.deepEqual(await response.json(), { uid: 'verified-user', profile: null });
  assert.equal((await call('/v1/me', { FIREBASE_PROJECT_ID: 'friendbook-17551' },
    { Authorization: 'Bearer test' }, 'GET', app)).status, 503);
});
test('real RSA verification rejects wrong project, issuer, expiry, signature and future auth time', async () => {
  const { publicKey, privateKey } = await generateKeyPair('RS256');
  const jwk = { ...await exportJWK(publicKey), kid: 'test' };
  const keys = createLocalJWKSet({ keys: [jwk] });
  const now = Math.floor(Date.now() / 1000);
  const claims = { sub: 'user1', aud: 'friendbook-17551', iss: 'https://securetoken.google.com/friendbook-17551',
    iat: now, exp: now + 3600, auth_time: now };
  const sign = (patch = {}, key = privateKey) => new SignJWT({ ...claims, ...patch })
    .setProtectedHeader({ alg: 'RS256', kid: 'test' }).sign(key);
  assert.equal(await verifyFirebaseToken(await sign(), claims.aud, keys), 'user1');
  for (const patch of [{ aud: 'wrong' }, { iss: 'wrong' }, { exp: now - 60 },
    { auth_time: now + 120 }, { sub: '' }, { iat: now + 120 }]) {
    await assert.rejects(verifyFirebaseToken(await sign(patch), claims.aud, keys));
  }
  const other = await generateKeyPair('RS256');
  await assert.rejects(verifyFirebaseToken(await sign({}, other.privateKey), claims.aud, keys));
});
test('retired routes do not access D1', async () => {
  let reads=0;
  const app=createWorker(async () => 'verified-user');
  const env={FIREBASE_PROJECT_ID:'friendbook-17551', DB:{prepare(){reads++; throw Error('unexpected D1');}}};
  for (const route of ['/v1/presence/me','/v1/presence/contact/peer','/v1/presence/typing/room','/v1/presence/socket'])
    assert.equal((await call(route,env,{Authorization:'Bearer test'},'GET',app)).status,404);
  assert.equal(reads,0);
});

test('Android public config keeps only its public boolean and rejects missing settings', async () => {
  const response=await call('/v1/android-config',{ADS_ENABLED_V3:'false',OTHER_SETTING:'not public'});
  assert.equal(response.status,200);
  assert.deepEqual(await response.json(),{ads_enabled_v3:false});
  assert.equal((await call('/v1/android-config')).status,503);
  assert.equal((await call('/v1/android-config',{}, {},'POST')).status,405);
});
