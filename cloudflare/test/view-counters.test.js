import { test } from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import { viewCounters } from '../view-counters.js';
import { createWorker } from '../../worker.js';

// View counters are additive-only: the migration must not drop or rewrite existing rows.
function fixture() {
  const sql = new DatabaseSync(':memory:');
  sql.exec(readFileSync(new URL('../migrations/0012_additive_view_counters.sql', import.meta.url), 'utf8'));
  sql.exec(readFileSync(new URL('../migrations/0013_additive_view_last_seen.sql', import.meta.url), 'utf8'));
  const DB = {
    async batch(list) { sql.exec('BEGIN'); try { const r = []; for (const q of list) r.push(await q.run()); sql.exec('COMMIT'); return r; } catch (e) { sql.exec('ROLLBACK'); throw e; } },
    withSession: () => DB,
    prepare(query) {
      const stmt = sql.prepare(query);
      const bound = (args) => ({
        first: async () => stmt.get(...args) || null,
        all: async () => ({ results: stmt.all(...args) }),
        run: async () => stmt.run(...args),
      });
      return { ...bound([]), bind: (...args) => bound(args) };
    },
  };
  return { sql, env: { DB }, close: () => sql.close() };
}

async function call(env, { method = 'GET', path = '/v1/views/reels/r1', query = '', uid = 'user1' } = {}, now) {
  const url = `https://example.invalid${path}${query}`;
  return viewCounters(new Request(url, { method }), env, { uid },
    /^\/v1\/views\/(posts|reels)\/([^/]+)$/.exec(path), new URL(url), now);
}

test('migration is repeatable and keeps production view rows untouched', async t => {
  const f = fixture(); t.after(f.close);
  f.sql.exec("INSERT INTO friendbook_cf_view_counters VALUES ('reels/legacy', 42, 1)");
  f.sql.exec("INSERT INTO friendbook_cf_view_last VALUES ('reels/legacy', 'user9', 1)");
  for (const m of ['0012_additive_view_counters.sql', '0013_additive_view_last_seen.sql']) {
    f.sql.exec(readFileSync(new URL(`../migrations/${m}`, import.meta.url), 'utf8'));
  }
  assert.equal(f.sql.prepare("SELECT views FROM friendbook_cf_view_counters WHERE resource = 'reels/legacy'").get().views, 42);
  assert.equal(f.sql.prepare("SELECT last_at FROM friendbook_cf_view_last WHERE uid = 'user9'").get().last_at, 1);
  assert.equal(await (await call(f.env, { path: '/v1/views/reels/legacy' })).json().then((d) => d.views), 42);
});

test('one second of watching is one view and every re-watch counts again', async t => {
  const f = fixture(); t.after(f.close);
  let clock = 1_700_000_000_000;
  const now = () => clock;
  const post = (uid = 'user1') => call(f.env, { method: 'POST', uid }, now);
  // First watch reaches one second -> counted, and the real stored total comes back.
  assert.deepEqual(await (await post()).json(),
    { resource: 'reels/r1', views: 1, counted: true, gapMs: 1000 });
  // A double-fired callback inside the same second cannot write a second row.
  const duplicate = await (await post()).json();
  assert.equal(duplicate.counted, false);
  assert.equal(duplicate.views, 1);
  // The SAME user watching it again one second later is a real view and counts again.
  clock += 1000;
  assert.deepEqual(await (await post()).json(),
    { resource: 'reels/r1', views: 2, counted: true, gapMs: 1000 });
  clock += 4000;
  assert.deepEqual(await (await post()).json(),
    { resource: 'reels/r1', views: 3, counted: true, gapMs: 1000 });
  // Another viewer adds its own views on the same reel, independently.
  assert.deepEqual(await (await post('user2')).json(),
    { resource: 'reels/r1', views: 4, counted: true, gapMs: 1000 });
  assert.equal((await (await post('user2')).json()).counted, false, 'per-user one-second floor');
  clock += 1500;
  assert.equal((await (await post('user2')).json()).views, 5);
  // One stamp row per (resource, uid): the table cannot grow per view.
  assert.equal(f.sql.prepare('SELECT COUNT(*) AS n FROM friendbook_cf_view_last').get().n, 2);
  assert.equal(await (await call(f.env, { path: '/v1/views/posts/r1' })).json().then((d) => d.views), 0,
    'posts and reels counters are separate resources');
});

test('worker self-creates the schema so counters work before a migration run', async t => {
  const f = fixture(); t.after(f.close);
  f.sql.exec('DROP TABLE friendbook_cf_view_last');
  f.sql.exec('DROP TABLE friendbook_cf_view_dedupe');
  f.sql.exec('DROP INDEX idx_friendbook_cf_view_counters_views');
  f.sql.exec('DROP TABLE friendbook_cf_view_counters');
  assert.equal((await call(f.env, { method: 'POST' })).status, 200);
  assert.equal(f.sql.prepare("SELECT views FROM friendbook_cf_view_counters WHERE resource = 'reels/r1'").get().views, 1);
  assert.equal(f.sql.prepare("SELECT COUNT(*) AS n FROM friendbook_cf_view_last WHERE resource = 'reels/r1'").get().n, 1);
});

test('batch and top reads never leak another collection or trust unknown ids', async t => {
  const f = fixture(); t.after(f.close);
  let clock = 1_700_000_000_000;
  const now = () => clock;
  for (const [uid, id] of [['user1', 'r1'], ['user2', 'r1'], ['user1', 'r2']]) {
    clock += 1000;
    await call(f.env, { method: 'POST', path: `/v1/views/reels/${id}`, uid }, now);
  }
  clock += 1000;
  await call(f.env, { method: 'POST', path: '/v1/views/posts/p1', uid: 'user1' }, now);
  const batch = await (await call(f.env, { path: '/v1/views', query: '?collection=reels&ids=r1,r2,r1,,bad%2Fid' })).json();
  assert.deepEqual(batch, { collection: 'reels', views: { r1: 2, r2: 1 } });
  const top = await (await call(f.env, { path: '/v1/views', query: '?collection=reels&top=1' })).json();
  assert.deepEqual(top.views, { r1: 2 });
  assert.deepEqual((await (await call(f.env, { path: '/v1/views', query: '?collection=posts' })).json()).views, { p1: 1 });
  assert.deepEqual((await (await call(f.env, { path: '/v1/views', query: '?collection=reels&ids=' })).json()).views, {});
});

test('input validation, methods and auth fail closed without writing data', async t => {
  const f = fixture(); t.after(f.close);
  assert.equal((await call({}, { method: 'POST' })).status, 503);
  assert.equal((await call(f.env, { uid: '' })).status, 401);
  assert.equal((await call(f.env, { path: '/v1/views/stories/s1' })).status, 400);
  assert.equal((await call(f.env, { path: '/v1/views/reels/..', method: 'POST' })).status, 400);
  assert.equal((await call(f.env, { path: '/v1/views/reels/a%2Cb', method: 'POST' })).status, 400);
  assert.equal((await call(f.env, { path: '/v1/views/reels/' + 'x'.repeat(200), method: 'POST' })).status, 400);
  assert.equal((await call(f.env, { method: 'DELETE' })).status, 405);
  assert.equal((await call(f.env, { path: '/v1/views', method: 'POST' })).status, 405);
  assert.equal((await call(f.env, { path: '/v1/views', query: '?collection=users' })).status, 400);
  assert.equal(f.sql.prepare('SELECT COUNT(*) AS n FROM friendbook_cf_view_last').get().n, 0);
  assert.equal(f.sql.prepare('SELECT COUNT(*) AS n FROM friendbook_cf_view_counters').get().n, 0);
});

test('worker exposes /v1/views behind the Firebase token gate', async t => {
  const f = fixture(); t.after(f.close);
  const env = { FIREBASE_PROJECT_ID: 'friendbook-test', DB: f.env.DB };
  const app = createWorker();
  assert.equal((await app.fetch(new Request('https://example.invalid/v1/views/reels/r1'), env)).status, 401);
  assert.equal((await app.fetch(new Request('https://example.invalid/v1/views/reels/r1',
    { headers: { Authorization: 'Bearer invalid' } }), env)).status, 401);
  assert.equal((await app.fetch(new Request('https://example.invalid/v1/views', { method: 'DELETE' }), env)).status, 401);
  const verified = createWorker(async () => 'user1');
  const ok = await verified.fetch(new Request('https://example.invalid/v1/views/reels/r1',
    { headers: { Authorization: 'Bearer test' } }), env);
  assert.equal(ok.status, 200);
  assert.deepEqual(await ok.json(), { resource: 'reels/r1', views: 0 });
  const posted = await verified.fetch(new Request('https://example.invalid/v1/views/reels/r1',
    { method: 'POST', headers: { Authorization: 'Bearer test' } }), env);
  assert.deepEqual(await posted.json(), { resource: 'reels/r1', views: 1, counted: true, gapMs: 1000 });
});
