import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { DatabaseSync } from 'node:sqlite';
import { test } from 'node:test';
import { resetViewState, viewCounterState, viewCounters } from '../view-counters.js';

// Node's test runner cannot import CommonJS from an ESM test file with a bare specifier, so the
// worker (which imports `jose`, a CJS-only package here) is loaded through createRequire.
const require = createRequire(import.meta.url);
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const { createWorker } = require(resolve(root, 'worker.js'));

const MIGRATIONS = ['0012_additive_view_counters.sql', '0013_additive_view_last_seen.sql'];
const HOUR = 3_600_000;
const KV_WINDOW = 300_000;
const T0 = 1_700_000_000_000;

/**
 * In-memory D1 + KV fixture that classifies every statement, so the tests can prove the two things
 * this design exists for: counting or serving a view performs ZERO D1 SELECTs, and the counting rule
 * is ONE view per user per resource forever (checked by a write-only insert, never by a read).
 */
function fixture({ withKv = true, kvSeed = null, gate = null } = {}) {
  const sql = new DatabaseSync(':memory:');
  const queries = { selects: 0, schema: 0, dedupe: 0, totals: 0 };
  const seenSql = [];
  const store = new Map();
  if (kvSeed) store.set('views:live:v1', JSON.stringify(kvSeed));
  const kv = { reads: 0, writes: 0 };

  function statement(text) {
    seenSql.push(text);
    const normalized = text.replace(/\s+/g, ' ').trim();
    const isSelect = /^SELECT/i.test(normalized);
    const isSchema = /^CREATE/i.test(normalized);
    const isDedupe = /friendbook_cf_view_last/i.test(normalized) && !isSelect && !isSchema;
    const isTotals = /friendbook_cf_view_counters/i.test(normalized) && !isSelect && !isSchema;
    const count = () => {
      if (isSelect) queries.selects += 1;
      else if (isSchema) queries.schema += 1;
      else if (isDedupe) queries.dedupe += 1;
      else if (isTotals) queries.totals += 1;
    };
    // `changes()` is what tells the Worker whether a (resource, uid) pair was new - the write is the
    // de-dupe check, which is why no SELECT is ever needed.
    const changes = () => sql.prepare('SELECT changes() AS n').get().n;
    // `gate` holds the hourly totals write in flight so a test can prove it was deferred to
    // ctx.waitUntil instead of delaying the response. The inline de-dupe insert is never gated.
    const bind = (...params) => ({
      async first() {
        if (gate && isTotals) await gate;
        await null; count();
        const row = sql.prepare(normalized).get(...params);
        return row === undefined ? null : row;
      },
      async all() { await null; count(); return { results: sql.prepare(normalized).all(...params) }; },
      async run() {
        if (gate && isTotals) await gate;
        await null; count();
        sql.prepare(normalized).run(...params);
        return { success: true, meta: { changes: changes() } };
      },
    });
    return {
      bind,
      async first() { return bind().first(); },
      async all() { return bind().all(); },
      async run() { return bind().run(); },
    };
  }

  const db = {
    prepare: statement,
    async batch(statements) {
      for (const entry of statements) await entry.run();
      return [];
    },
  };

  // worker.js refuses every /v1 route without the project id, so the fixture carries it.
  const env = { DB: db, FIREBASE_PROJECT_ID: 'friendbook-17551' };
  if (withKv) {
    env.VIEWS = {
      async get(key, options) {
        await null;
        kv.reads += 1;
        const value = store.get(key);
        if (value === undefined) return null;
        return options?.type === 'json' ? JSON.parse(value) : value;
      },
      async put(key, value) { if (gate) await gate; await null; kv.writes += 1; store.set(key, value); },
    };
  }

  return { env, sql, queries, seenSql, store, kv, close: () => sql.close() };
}

async function call(env, { path = '/v1/views/reels/r1', method = 'GET', uid = 'user1', ids = null } = {}, now = Date.now, ctx) {
  const url = new URL(`https://worker.test${path}`);
  if (ids) url.searchParams.set('ids', ids);
  const request = new Request(url, { method });
  const response = await viewCounters(
    request, env, { uid, token: 'tok' }, path.match(/^\/v1\/views\/([a-z]+)\/([^/]+)$/), url, now, ctx);
  return { status: response.status, body: await response.json() };
}

/** One view POST from one distinct watcher: `viewer(3)` is the third different user. */
const viewer = (n) => `user${n}`;

test('counting and serving views issue zero D1 SELECTs, and totals wait for the hourly window', async t => {
  resetViewState();
  const f = fixture({ kvSeed: { 'reels/r1': 40 } }); t.after(f.close);
  let clock = T0;
  const now = () => clock;

  for (let i = 1; i <= 6; i++) {
    clock += 1000;
    const { status, body } = await call(f.env, { method: 'POST', uid: viewer(i) }, now);
    assert.equal(status, 200);
    assert.equal(body.counted, true, 'a first-time watcher counts');
    assert.equal(body.dedupe, 'once-per-user');
  }
  const batch = await call(f.env, { path: '/v1/views?collection=reels', ids: 'r1,missing' }, now);
  const single = await call(f.env, {}, now);

  assert.equal(batch.body.views.r1, 46);
  assert.equal(batch.body.views.missing, 0);
  assert.equal(single.body.views, 46);
  assert.equal(single.body.storage, 'kv+memory');
  assert.equal(f.queries.selects, 0, 'the view path must never SELECT from D1');
  assert.equal(f.queries.totals, 0, 'the KV seed counts as recently persisted, so the total waits out the hour');
  assert.equal(f.queries.dedupe, 6, 'one write-only de-dupe insert per first-time watcher, never a read');
  assert.equal(f.sql.prepare('SELECT COUNT(*) AS n FROM friendbook_cf_view_last').get().n, 6);
  assert.deepEqual([...viewCounterState().dirty], ['reels/r1'], 'the six views are queued for the hourly D1 write');
  assert.equal(f.kv.writes, 0, 'six seconds of counting did not need a KV write either');
});

test('a repeat watcher is refused by the durable de-dupe without any D1 read', async t => {
  resetViewState();
  const f = fixture({ kvSeed: { 'reels/r1': 40 } }); t.after(f.close);
  let clock = T0;
  const now = () => clock;

  const first = await call(f.env, { method: 'POST' }, now);
  assert.equal(first.body.counted, true);
  assert.equal(first.body.views, 41);

  // Same user, same second: the memory cache settles it, no storage call at all.
  const again = await call(f.env, { method: 'POST' }, now);
  assert.equal(again.body.counted, false);
  assert.equal(again.body.views, 41);
  assert.equal(f.queries.dedupe, 1, 'the repeat cost nothing');

  // A second watcher, five minutes later, so the aggregate is durable in KV before the isolate dies.
  clock += KV_WINDOW;
  const other = await call(f.env, { method: 'POST', uid: 'user2' }, now);
  assert.equal(other.body.counted, true);
  assert.equal(other.body.views, 42);
  assert.equal(f.kv.writes, 1, 'the snapshot that survives an isolate restart was written');

  // The first watcher comes back a month later, and this isolate has forgotten everything:
  // still ONE view for them, restored total, and still no D1 read.
  clock += 30 * 24 * HOUR;
  resetViewState();
  const muchLater = await call(f.env, { method: 'POST' }, now);
  assert.equal(muchLater.body.counted, false, 'ONE view per user per Reel, forever');
  assert.equal(muchLater.body.views, 42, 'the KV aggregate restored the total without touching D1');
  assert.equal(f.queries.selects, 0, 'the durable de-dupe is checked by a write, never by a read');
  assert.equal(f.queries.dedupe, 3, 'a cold isolate asks D1 once per watcher, and D1 answers "already counted"');
  assert.equal(f.sql.prepare('SELECT COUNT(*) AS n FROM friendbook_cf_view_last').get().n, 2,
    'no second row was ever written for the same user');

  // A brand new watcher is a real new view.
  const third = await call(f.env, { method: 'POST', uid: 'user3' }, now);
  assert.equal(third.body.counted, true);
  assert.equal(third.body.views, 43);
});

test('D1 receives one totals write per resource per hour holding the full live total', async t => {
  resetViewState();
  const f = fixture({ kvSeed: { 'reels/r1': 10 } }); t.after(f.close);
  let clock = T0;
  const now = () => clock;
  let watchers = 0;
  const post = () => call(f.env, { method: 'POST', uid: viewer(++watchers) }, now);

  // A resource that was never persisted is written at once, so a brand new Reel cannot be lost.
  await call(f.env, { path: '/v1/views/reels/new1', method: 'POST', uid: 'fresh-eyes' }, now);
  assert.equal(f.queries.totals, 1, 'the first ever totals write of a resource happens at once');
  assert.equal(f.sql.prepare("SELECT views FROM friendbook_cf_view_counters WHERE resource = 'reels/new1'").get().views, 1);
  assert.ok(f.queries.schema >= 4, 'the Worker created its own tables and index without a migration run');

  // 54 minutes of watching by six different people: counted and served live, no totals write.
  for (let i = 0; i < 6; i++) { clock += 9 * 60_000; await post(); }
  assert.equal(f.queries.totals, 1, 'no totals write inside the hourly window');
  assert.equal((await call(f.env, {}, now)).body.views, 16, 'the live total still grows');
  assert.equal(viewCounterState().dirty.has('reels/r1'), true);

  // Crossing the hour writes the FULL live total in ONE statement - still no SELECT.
  clock += 10 * 60_000;
  const flushed = (await post()).body;
  assert.equal(f.queries.totals, 2);
  assert.equal(f.sql.prepare("SELECT views FROM friendbook_cf_view_counters WHERE resource = 'reels/r1'").get().views, 17);
  assert.equal(flushed.views, 17);
  assert.equal(flushed.d1Pending, false, 'the hourly write cleared the pending flag');
  assert.equal(viewCounterState().dirty.has('reels/r1'), false);
  assert.equal(f.queries.selects, 0);
  assert.ok(f.kv.writes <= 12, `the hourly writes added no unbudgeted KV writes (${f.kv.writes})`);
});

test('the hourly D1 write can never lower a stored total (MAX semantics)', async t => {
  resetViewState();
  const f = fixture({ kvSeed: {} }); t.after(f.close);
  f.sql.exec(readMigration(MIGRATIONS[0]));
  f.sql.exec("INSERT INTO friendbook_cf_view_counters VALUES ('reels/r1', 500, 1), ('reels/r2', 70, 1)");
  f.store.set('views:live:v1', JSON.stringify({ 'reels/r1': 500, 'reels/r2': 70 }));
  resetViewState();
  let clock = T0;
  const now = () => clock;

  // Only r1 is watched, by five different people over the next hour.
  for (let i = 1; i <= 5; i++) { clock += 2000; await call(f.env, { method: 'POST', uid: viewer(i) }, now); }
  // The first watcher coming back changes nothing.
  const repeat = await call(f.env, { method: 'POST', uid: viewer(1) }, now);
  assert.equal(repeat.body.counted, false);
  clock += HOUR;
  const flushed = (await call(f.env, { method: 'POST', uid: 'viewer6' }, now)).body;

  assert.equal(flushed.views, 506, 'the seeded total plus six real, distinct viewers');
  assert.equal(f.sql.prepare("SELECT views FROM friendbook_cf_view_counters WHERE resource = 'reels/r1'").get().views, 506);
  assert.equal(f.sql.prepare("SELECT views FROM friendbook_cf_view_counters WHERE resource = 'reels/r2'").get().views, 70,
    'a resource nobody watched is never rewritten');
  assert.equal(f.queries.selects, 0);
});

test('a cold isolate re-seeds from KV and never from D1', async t => {
  resetViewState();
  const f = fixture({ kvSeed: { 'reels/r1': 100, 'reels/r2': 5 } }); t.after(f.close);
  const first = await call(f.env, {}, () => T0);
  assert.equal(first.body.views, 100);
  assert.equal(f.queries.selects, 0, 'KV alone served a cold isolate');
  assert.equal(f.kv.reads, 1, 'exactly one KV read per isolate, not per request');
  resetViewState();
  const cold = await call(f.env, {}, () => T0 + 1000);
  assert.equal(cold.body.views, 100);
  assert.equal(f.queries.selects, 0);
  assert.equal(f.kv.reads, 2);
});

test('an empty KV seeds once from D1, bounded, and writes the seed straight back to KV', async t => {
  resetViewState();
  const f = fixture({ kvSeed: null }); t.after(f.close);
  f.sql.exec(readMigration(MIGRATIONS[0]));
  f.sql.exec("INSERT INTO friendbook_cf_view_counters VALUES ('reels/r1', 25, 1)");
  const seeded = await call(f.env, {}, () => T0);
  assert.equal(seeded.body.views, 25, 'the first isolate ever restores the historical total');
  assert.equal(f.queries.selects, 1, 'one bounded SELECT, ever');
  assert.match(f.seenSql.join('\n'), /ORDER BY views DESC LIMIT \?/);
  assert.deepEqual(JSON.parse(f.store.get('views:live:v1')), { 'reels/r1': 25 }, 'the seed is cached in KV');

  resetViewState(); // A later cold isolate must never touch D1 again.
  const later = await call(f.env, {}, () => T0 + 5000);
  assert.equal(later.body.views, 25);
  assert.equal(f.queries.selects, 1);
  assert.equal(viewCounterState().seedReads, 0, 'this isolate was served purely from KV');
});

test('without a KV binding the counters still work from memory and never read D1 per view', async t => {
  resetViewState();
  const f = fixture({ withKv: false, kvSeed: null }); t.after(f.close);
  f.sql.exec(readMigration(MIGRATIONS[0]));
  f.sql.exec("INSERT INTO friendbook_cf_view_counters VALUES ('reels/r1', 7, 1)");
  let clock = T0;
  const now = () => clock;
  for (let i = 1; i <= 3; i++) { clock += 1000; await call(f.env, { method: 'POST', uid: viewer(i) }, now); }
  const served = await call(f.env, {}, now);
  assert.equal(served.body.views, 10, 'the historical total was restored once, then counted in memory');
  assert.equal(served.body.storage, 'memory');
  assert.equal(f.queries.selects, 1, 'one bounded seed read for the whole isolate, not one per view');
  assert.equal(f.kv.reads, 0);
  for (let i = 4; i <= 13; i++) { clock += 1000; await call(f.env, { method: 'POST', uid: viewer(i) }, now); }
  assert.equal(f.queries.selects, 1, 'ten more viewers added zero D1 reads');
  assert.equal((await call(f.env, {}, now)).body.views, 20);
});

test('the KV aggregate is written at most once per five minutes of counting', async t => {
  resetViewState();
  const f = fixture({ kvSeed: { 'reels/r1': 0 } }); t.after(f.close);
  let clock = T0;
  const now = () => clock;
  let watchers = 0;

  for (let i = 0; i < 20; i++) {
    clock += 1000;
    await call(f.env, { method: 'POST', uid: viewer(++watchers) }, now);
  }
  assert.equal(f.kv.writes, 0, 'twenty seconds of counting never rewrote KV');

  clock += KV_WINDOW;
  await call(f.env, { method: 'POST', uid: viewer(++watchers) }, now);
  assert.equal(f.kv.writes, 1);
  assert.deepEqual(JSON.parse(f.store.get('views:live:v1')), { 'reels/r1': 21 },
    'the snapshot holds what was counted at flush time');

  const before = f.kv.writes;
  clock += 1000;
  await call(f.env, { method: 'POST', uid: viewer(++watchers) }, now);
  assert.equal(f.kv.writes, before, 'inside the five minute window nothing is rewritten');
  assert.equal(f.queries.selects, 0);
});

test('waitUntil defers the totals write so the response is never blocked by storage', async t => {
  resetViewState();
  let release;
  const gate = new Promise((resolve) => { release = resolve; });
  const f = fixture({ kvSeed: { 'reels/r1': 0 }, gate }); t.after(f.close);
  const pending = [];
  const ctx = { waitUntil: (promise) => pending.push(promise) };

  // The hourly totals write and the KV snapshot are both stuck behind the gate, yet the response
  // comes back at once (the inline de-dupe insert is not gated - it decides whether to count).
  const response = await call(f.env, { path: '/v1/views/reels/fresh', method: 'POST', uid: 'new-eyes' }, () => T0, ctx);
  assert.equal(response.status, 200);
  assert.equal(response.body.views, 1, 'the live count is served without waiting for storage');
  assert.equal(response.body.d1Pending, true, 'the totals write is still in flight, and the client is told so');
  assert.equal(pending.length, 1, 'the flush was handed to the runtime, not awaited inline');
  assert.equal(f.sql.prepare("SELECT COUNT(*) AS n FROM friendbook_cf_view_counters").get().n, 0,
    'nothing reached the counters table while the response was being served');

  release();
  await Promise.allSettled(pending);
  assert.equal(f.sql.prepare("SELECT views FROM friendbook_cf_view_counters WHERE resource = 'reels/fresh'").get().views, 1);
});

test('input validation, methods and auth fail closed without writing data', async t => {
  resetViewState();
  const f = fixture({ kvSeed: { 'reels/r1': 0 } }); t.after(f.close);
  const now = () => T0;

  assert.equal((await call(f.env, { path: '/v1/views/stories/x', method: 'POST' }, now)).status, 400);
  assert.equal((await call(f.env, { path: '/v1/views/reels/..', method: 'POST' }, now)).status, 400);
  assert.equal((await call(f.env, { path: '/v1/views/reels/a,b', method: 'POST' }, now)).status, 400);
  assert.equal((await call(f.env, { path: '/v1/views/reels/' + 'x'.repeat(129), method: 'POST' }, now)).status, 400);
  // A trailing slash is not a resource: worker.js 404s it, and the module itself refuses the POST.
  assert.equal((await call(f.env, { path: '/v1/views/reels/', method: 'POST' }, now)).status, 405);
  assert.equal((await call(f.env, { method: 'DELETE' }, now)).status, 405);
  assert.equal((await call(f.env, { path: '/v1/views', method: 'POST' }, now)).status, 405);
  assert.equal((await call(f.env, { path: '/v1/views', uid: 'user1' }, now)).status, 400, 'collection is required');
  assert.equal((await call(f.env, { method: 'POST', uid: '' }, now)).status, 401);
  assert.equal((await call(f.env, { method: 'POST', uid: 'x'.repeat(129) }, now)).status, 401);

  const many = Array.from({ length: 51 }, (_, i) => `r${i}`).join(',');
  assert.equal((await call(f.env, { path: '/v1/views?collection=reels', ids: many }, now)).status, 400);
  assert.equal(f.queries.totals, 0, 'rejected requests never write a total');
  assert.equal(f.queries.dedupe, 0, 'rejected requests never write a de-dupe row');
  assert.equal(f.queries.selects, 0, 'rejected requests never read');
});

test('migration is repeatable and keeps production view rows untouched', async t => {
  const sql = new DatabaseSync(':memory:');
  t.after(() => sql.close());
  sql.exec('CREATE TABLE posts (id TEXT PRIMARY KEY, views INTEGER)');
  sql.exec("INSERT INTO posts VALUES ('p1', 900)");
  const text = MIGRATIONS.map((name) => ({ name, text: readMigration(name) }));
  for (const pass of [1, 2]) {
    for (const migration of text) {
      sql.exec(migration.text);
      assert.ok(migration.name.length > 0);
    }
  }
  assert.equal(sql.prepare('SELECT views FROM posts WHERE id = ?').get('p1').views, 900);
  const columns = sql.prepare('SELECT name FROM pragma_table_info(?)').all('friendbook_cf_view_counters');
  assert.deepEqual(columns.map((row) => row.name), ['resource', 'views', 'updated_at']);
  const dedupeColumns = sql.prepare('SELECT name FROM pragma_table_info(?)').all('friendbook_cf_view_last');
  assert.deepEqual(dedupeColumns.map((row) => row.name), ['resource', 'uid', 'last_at']);
});

test('worker exposes /v1/views behind the Firebase token gate', async t => {
  resetViewState();
  const f = fixture({ kvSeed: { 'reels/r1': 12 } }); t.after(f.close);
  const worker = createWorker(async () => 'user1');
  const fetchWith = (path, method, token = 'id-token') => worker.fetch(
    new Request(`https://worker.test${path}`, { method, headers: token ? { Authorization: `Bearer ${token}` } : {} }),
    f.env, { waitUntil: () => {}, passThroughOnException: () => {} },
  );

  const counted = await (await fetchWith('/v1/views/reels/r1', 'POST')).json();
  assert.equal(counted.views, 13);
  assert.equal(counted.counted, true);
  assert.equal(counted.storage, 'kv+memory');
  const repeat = await (await fetchWith('/v1/views/reels/r1', 'POST')).json();
  assert.equal(repeat.counted, false, 'the same user watching again does not count again');
  assert.equal(repeat.views, 13);
  const served = await (await fetchWith('/v1/views/reels/r1', 'GET')).json();
  assert.equal(served.views, 13);

  resetViewState();
  assert.equal((await fetchWith('/v1/views/reels/r1', 'POST', null)).status, 401, 'no token, no view');
  const rejecting = createWorker(async () => { throw new Error('bad token'); });
  const rejected = await rejecting.fetch(
    new Request('https://worker.test/v1/views/reels/r1', { method: 'POST', headers: { Authorization: 'Bearer nope' } }),
    f.env, { waitUntil: () => {}, passThroughOnException: () => {} },
  );
  assert.equal(rejected.status, 401, 'an unverifiable token is refused before any counter runs');
});

function readMigration(name) {
  const { readFileSync } = require('node:fs');
  return readFileSync(resolve(root, 'cloudflare', 'migrations', name), 'utf8');
}
