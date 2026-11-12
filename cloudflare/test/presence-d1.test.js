import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import { createWorker } from '../../worker.js';

const MIGRATION_0010 = readFileSync(new URL('../migrations/0010_additive_presence_realtime.sql', import.meta.url), 'utf8');

function createSqliteD1() {
  const sql = new DatabaseSync(':memory:');
  sql.exec(MIGRATION_0010);
  return sql;
}

/**
 * A fresh wrapper around the SAME sqlite database models a different Worker isolate: module-level
 * caches are keyed by the binding object, so a new wrapper starts with empty caches while the rows
 * are shared - exactly what happens between two colos in production.
 */
function isolate(sql, counter) {
  return {
    prepare(query) {
      const stmt = sql.prepare(query);
      return {
        bind(...args) {
          return {
            run: async () => { if (counter) counter.writes += 1; return stmt.run(...args); },
            all: async () => ({ results: stmt.all(...args) }),
            first: async () => stmt.get(...args) || null,
          };
        },
        run: async () => stmt.run(),
        all: async () => ({ results: stmt.all() }),
        first: async () => stmt.get() || null,
      };
    },
  };
}

const envFor = (db) => ({ DB: db, FIREBASE_PROJECT_ID: 'friendbook-17551', REALTIME_WS_ENABLED: 'true' });
const call = (app, path, env, { method = 'GET', uid = 'user-a', body } = {}) => app.fetch(
  new Request(`https://example.invalid${path}`, {
    method,
    headers: { Authorization: `Bearer token-${uid}`, ...(body ? { 'Content-Type': 'application/json' } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  }),
  env,
);

test('presence heartbeats land in D1, so another isolate sees the same online state', async () => {
  const sql = createSqliteD1();
  const writer = createWorker(async (_t, _p, ...rest) => 'user-a');
  const reader = createWorker(async () => 'user-b');

  const put = await call(writer, '/v1/presence/me', envFor(isolate(sql)), { method: 'PUT', body: { online: true } });
  assert.equal(put.status, 200);
  assert.equal((await put.json()).online, true);

  const row = sql.prepare('SELECT online, visible, last_seen FROM friendbook_cf_presence WHERE uid = ?').get('user-a');
  assert.equal(row.online, 1);
  assert.ok(row.last_seen > 0);

  // A different isolate (empty caches, same rows) must still report the truth - this is the fact
  // that Firebase Realtime Database used to provide and Cloudflare now provides.
  const contact = await call(reader, '/v1/presence/contact/user-a', envFor(isolate(sql)), { uid: 'user-b' });
  assert.equal(contact.status, 200);
  const seen = await contact.json();
  assert.equal(seen.uid, 'user-a');
  assert.equal(seen.online, true);
  assert.equal(seen.lastSeen, row.last_seen);
});

test('going offline is written immediately and never overstates the session', async () => {
  const sql = createSqliteD1();
  const app = createWorker(async () => 'user-a');
  await call(app, '/v1/presence/me', envFor(isolate(sql)), { method: 'PUT', body: { online: true } });
  const onlineRow = sql.prepare('SELECT last_seen FROM friendbook_cf_presence WHERE uid = ?').get('user-a');

  const off = await call(app, '/v1/presence/me', envFor(isolate(sql)), { method: 'PUT', body: { online: false } });
  assert.equal((await off.json()).online, false);
  const row = sql.prepare('SELECT online, last_seen FROM friendbook_cf_presence WHERE uid = ?').get('user-a');
  assert.equal(row.online, 0);
  assert.ok(row.last_seen >= onlineRow.last_seen, 'last_seen must never move backwards');

  const contact = await call(app, '/v1/presence/contact/user-a', envFor(isolate(sql)), { uid: 'user-b' });
  assert.equal((await contact.json()).online, false);
});

test('repeat heartbeats inside the coalescing window cost one D1 write, not one per beat', async () => {
  const sql = createSqliteD1();
  const app = createWorker(async () => 'user-a');
  const counter = { writes: 0 };
  const db = isolate(sql, counter);
  await call(app, '/v1/presence/me', envFor(db), { method: 'PUT', body: { online: true } });
  const first = counter.writes;
  assert.equal(first, 1);
  await call(app, '/v1/presence/me', envFor(db), { method: 'PUT', body: { online: true } });
  await call(app, '/v1/presence/me', envFor(db), { method: 'PUT', body: { online: true } });
  assert.equal(counter.writes, 1, 'online heartbeats are coalesced');
});

test('typing is shared through D1 between the two participants of a chat', async () => {
  const sql = createSqliteD1();
  const a = createWorker(async () => 'user-a');
  const b = createWorker(async () => 'user-b');

  const put = await call(a, '/v1/presence/typing/chat-1', envFor(isolate(sql)), { method: 'PUT', body: { typing: true } });
  assert.equal(put.status, 200);
  assert.equal(sql.prepare('SELECT typing FROM friendbook_cf_typing WHERE chat_id = ? AND uid = ?').get('chat-1', 'user-a').typing, 1);

  const peer = await call(b, '/v1/presence/typing/chat-1', envFor(isolate(sql)), { uid: 'user-b' });
  assert.equal((await peer.json()).typing, true, 'the peer sees typing from D1, not from one isolate');

  // The typer's own read must not report itself as the peer.
  const self = await call(a, '/v1/presence/typing/chat-1', envFor(isolate(sql)), { uid: 'user-a' });
  assert.equal((await self.json()).typing, false);

  await call(a, '/v1/presence/typing/chat-1', envFor(isolate(sql)), { method: 'PUT', body: { typing: false } });
  const after = await call(b, '/v1/presence/typing/chat-1', envFor(isolate(sql)), { uid: 'user-b' });
  assert.equal((await after.json()).typing, false);
});

test('stale typing rows stop counting once the TTL passes', async () => {
  const sql = createSqliteD1();
  const app = createWorker(async () => 'user-b');
  sql.prepare('INSERT INTO friendbook_cf_typing(chat_id, uid, typing, updated_at) VALUES (?, ?, 1, ?)')
    .run('chat-9', 'user-a', Date.now() - 60_000);
  const res = await call(app, '/v1/presence/typing/chat-9', envFor(isolate(sql)), { uid: 'user-b' });
  assert.equal((await res.json()).typing, false);
});

test('presence writes are validated and bounded', async () => {
  const sql = createSqliteD1();
  const app = createWorker(async () => 'user-a');
  const env = envFor(isolate(sql));
  assert.equal((await call(app, '/v1/presence/me', env, { method: 'PUT', body: { online: 'yes' } })).status, 400);
  assert.equal((await call(app, '/v1/presence/me', env, { method: 'PUT' })).status, 400);
  assert.equal((await call(app, '/v1/presence/me?uid=victim', env, { method: 'PUT', body: { online: true } })).status, 400);
  assert.equal((await call(app, '/v1/presence/typing/chat-1', env, { method: 'PUT', body: { typing: 'yes' } })).status, 400);
  assert.equal((await call(app, '/v1/presence/me', env, { method: 'DELETE' })).status, 405);
  assert.equal(sql.prepare('SELECT COUNT(*) AS n FROM friendbook_cf_presence').get().n, 0);
});

test('presence needs a verified identity and a bound database', async () => {
  const sql = createSqliteD1();
  const anon = await createWorker(async () => 'user-a').fetch(
    new Request('https://example.invalid/v1/presence/me', { method: 'PUT', body: '{"online":true}' }),
    envFor(isolate(sql)),
  );
  assert.equal(anon.status, 401);
  const noDb = await createWorker(async () => 'user-a').fetch(
    new Request('https://example.invalid/v1/presence/me', {
      method: 'PUT', headers: { Authorization: 'Bearer t' }, body: '{"online":true}',
    }),
    { FIREBASE_PROJECT_ID: 'friendbook-17551', REALTIME_WS_ENABLED: 'true' },
  );
  assert.equal(noDb.status, 503);
});

test('/v1/config serves the runtime switches that Firebase Remote Config used to serve', async () => {
  const app = createWorker(async () => 'user-a');
  const res = await app.fetch(new Request('https://example.invalid/v1/config'), { DB: null });
  assert.equal(res.status, 200);
  assert.deepEqual(await res.json(), { adsEnabled: true, presence: true, cloudflareData: true, firebase: 'auth-only' });

  const off = await app.fetch(new Request('https://example.invalid/v1/config'), { ADS_ENABLED: 'false', REALTIME_WS_ENABLED: 'false' });
  assert.deepEqual(await off.json(), { adsEnabled: false, presence: false, cloudflareData: true, firebase: 'auth-only' });

  const post = await app.fetch(new Request('https://example.invalid/v1/config', { method: 'POST' }), {});
  assert.equal(post.status, 405);
});
