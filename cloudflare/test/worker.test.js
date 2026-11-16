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
  // Engine, the KV snapshot, or the R2 snapshot fallback), and exposes no counter, no id and no data.
  assert.deepEqual(await response.json(),
    { ok: true, views: { ae: false, kv: false, r2Snapshot: false, aeReadToken: false } });
  assert.equal(response.headers.get('Cache-Control'), 'no-store');
});
test('missing binding and failed DB return no internal details', async () => {
  assert.equal((await call('/health')).status, 503);
  const response = await call('/health', { DB: { prepare() { throw Error('secret'); } } });
  assert.equal(response.status, 503);
  assert.deepEqual(await response.json(),
    { ok: false, views: { ae: false, kv: false, r2Snapshot: false, aeReadToken: false } });
});
test('health reports the R2 snapshot fallback when Workers KV is not bound', async () => {
  const db = { prepare: () => ({ first: async () => ({ connected: 1 }) }) };
  const response = await call('/health', { DB: db, ANALYTICS_ENGINE: {}, VIDEO_BUCKET: {}, CF_AE_TOKEN: 'tok' });
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(),
    { ok: true, views: { ae: true, kv: false, r2Snapshot: true, aeReadToken: true } });
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

test('data-api stays dark unless the deployment opts in', async () => {
  const w = createWorker(async () => 'uid_test');
  const base = { FIREBASE_PROJECT_ID: 'friendbook-test' };

  // Off by default: an unmigrated deployment must behave exactly as before.
  const off = await w.fetch(new Request('https://example.com/v1/posts/feed',
    { headers: { Authorization: 'Bearer test-token' } }), { ...base, DB: {} });
  assert.equal(off.status, 404);

  // Opted in, but still requires a verified Firebase identity.
  const noAuth = await w.fetch(new Request('https://example.com/v1/posts/feed'),
    { ...base, DB: {}, DATA_API_ENABLED: 'true' });
  assert.equal(noAuth.status, 401);

  const badAuth = await w.fetch(new Request('https://example.com/v1/posts/feed',
    { headers: { Authorization: 'Bearer ' } }), { ...base, DB: {}, DATA_API_ENABLED: 'true' });
  assert.equal(badAuth.status, 401);
});

test('data-api routing never steals the endpoints that already have handlers', async () => {
  const w = createWorker(async () => 'uid_test');
  const env = { FIREBASE_PROJECT_ID: 'friendbook-test', DB: null, DATA_API_ENABLED: 'true' };

  // /v1/docs keeps its own handler, so an unsupported collection is that handler's
  // 400 - not data-api's 404 and not a 503 from a missing DB binding.
  const docs = await w.fetch(new Request('https://example.com/v1/docs?collection=nope',
    { headers: { Authorization: 'Bearer test-token' } }), env);
  assert.equal(docs.status, 503);
  // app-documents.js owns this path; its own message proves data-api did not intercept it.
  assert.equal((await docs.json()).error, 'Database unavailable');

  // /v1/me still answers from the profile table, not from data-api.
  const me = await w.fetch(new Request('https://example.com/v1/me',
    { headers: { Authorization: 'Bearer test-token' } }), env);
  assert.equal(me.status, 503);
  assert.match((await me.json()).error, /existing app data is unchanged/);
});

test('the migration API fails closed without a configured token', async () => {
  const w = createWorker(async () => 'uid_test');
  const base = { FIREBASE_PROJECT_ID: 'friendbook-test', DB: {} };

  // No token configured at all: refuse, and do not fall back to a built-in default.
  const unconfigured = await w.fetch(new Request('https://example.com/v1/admin/migrate/verify',
    { headers: { Authorization: 'Bearer anything' } }), base);
  assert.equal(unconfigured.status, 503);

  // The old hardcoded placeholder must not be accepted anywhere.
  const placeholder = await w.fetch(new Request('https://example.com/v1/admin/migrate/verify',
    { headers: { Authorization: 'Bearer MIGRATION_ADMIN_TOKEN_CHANGE_ME' } }), base);
  assert.equal(placeholder.status, 503);

  // A short token is treated as unconfigured rather than as a weak secret.
  const shortToken = await w.fetch(new Request('https://example.com/v1/admin/migrate/verify',
    { headers: { Authorization: 'Bearer short' } }), { ...base, MIGRATION_ADMIN_TOKEN: 'short' });
  assert.equal(shortToken.status, 503);
});

test('the migration API rejects wrong tokens and needs no Firebase identity', async () => {
  const w = createWorker(async () => { throw new Error('must not verify a Firebase token here'); });
  const token = 'x'.repeat(48);
  const env = { FIREBASE_PROJECT_ID: 'friendbook-test', DB: {}, MIGRATION_ADMIN_TOKEN: token };

  // No header, and a header that is not a bearer token.
  assert.equal((await w.fetch(new Request('https://example.com/v1/admin/migrate/verify'), env)).status, 401);
  assert.equal((await w.fetch(new Request('https://example.com/v1/admin/migrate/verify',
    { headers: { Authorization: 'Basic abc' } }), env)).status, 401);

  // Wrong token of the same length must not be accepted.
  const wrong = await w.fetch(new Request('https://example.com/v1/admin/migrate/verify',
    { headers: { Authorization: 'Bearer ' + 'y'.repeat(48) } }), env);
  assert.equal(wrong.status, 403);

  // A prefix of the real token must not be accepted either.
  const prefix = await w.fetch(new Request('https://example.com/v1/admin/migrate/verify',
    { headers: { Authorization: 'Bearer ' + 'x'.repeat(47) } }), env);
  assert.equal(prefix.status, 403);
});
