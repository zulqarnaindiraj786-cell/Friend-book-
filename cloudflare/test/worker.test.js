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
  // Binding flags only: it proves which view-counter bindings a deploy actually shipped (Analytics
  // Engine + KV), and exposes no counter, no id and no user data.
  assert.deepEqual(await response.json(), { ok: true, views: { ae: false, kv: false, aeReadToken: false } });
  assert.equal(response.headers.get('Cache-Control'), 'no-store');
});
test('missing binding and failed DB return no internal details', async () => {
  assert.equal((await call('/health')).status, 503);
  const response = await call('/health', { DB: { prepare() { throw Error('secret'); } } });
  assert.equal(response.status, 503);
  assert.deepEqual(await response.json(), { ok: false, views: { ae: false, kv: false, aeReadToken: false } });
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
    assert.ok(sql.endsWith('WHERE uid = ?'));
    return { bind(uid) { assert.equal(uid, 'verified-user'); return { first: async () => null }; } };
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
test('realtime presence routes require verified Firebase identity and return fresh online state', async () => {
  const now = Date.now();
  const app = createWorker(async () => 'verified-user');
  const env = { FIREBASE_PROJECT_ID: 'friendbook-17551', REALTIME_WS_ENABLED: 'true', DB: { prepare() {
    return { bind(uid) { return { first: async () => ({ online: 1, visible: 1, last_seen: now - 5000 }) }; } };
  } } };
  assert.equal((await call('/v1/presence/me', env)).status, 401);
  const me = await call('/v1/presence/me', env, { Authorization: 'Bearer test' }, 'GET', app);
  assert.equal(me.status, 200);
  const body = await me.json();
  assert.equal(body.uid, 'verified-user');
  assert.equal(body.online, true);
  const contact = await call('/v1/presence/contact/peer-1', env, { Authorization: 'Bearer test' }, 'GET', app);
  assert.equal(contact.status, 200);
  assert.equal((await contact.json()).uid, 'peer-1');
  assert.equal((await call('/v1/presence/socket', env, { Authorization: 'Bearer test' }, 'GET', app)).status, 426);
});
