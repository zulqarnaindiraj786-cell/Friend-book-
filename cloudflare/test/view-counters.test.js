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
const AE_MINUTE = 60_000;
const KV_WINDOW = 300_000;
const T0 = 1_700_000_000_000;

/**
 * Fixture: Analytics Engine (the event store + the real count), Workers KV (the live snapshot) and
 * D1 (which the view path must NEVER write, and only ever reads as a last-resort seed). Every D1
 * statement and every AE event is recorded, and the AE query API is stubbed so a test decides what
 * Analytics Engine answers with - SQL TSV, GraphQL JSON, an error, or nothing at all.
 */
function fixture({
  withKv = true, kvSeed = null, withR2 = false, r2Seed = null,
  withAe = true, aeToken = true, sql = null, graphql = null,
} = {}) {
  const d1 = new DatabaseSync(':memory:');
  const queries = { selects: 0, writes: 0 };
  const seenSql = [];
  const store = new Map();
  if (kvSeed) store.set('views:live:v1', JSON.stringify(kvSeed));
  const kv = { reads: 0, writes: 0 };
  const r2Objects = new Map();
  const r2 = { reads: 0, writes: 0, keys: [] };
  if (r2Seed) r2Objects.set('views/live/v1.json', JSON.stringify(r2Seed));
  const ae = { events: [], sqlCalls: [], graphqlCalls: [] };
  const fetches = [];

  function statement(text) {
    seenSql.push(text);
    const normalized = text.replace(/\s+/g, ' ').trim();
    const isSelect = /^SELECT/i.test(normalized);
    const bind = (...params) => ({
      async first() {
        await null;
        if (isSelect) queries.selects += 1; else queries.writes += 1;
        const row = d1.prepare(normalized).get(...params);
        return row === undefined ? null : row;
      },
      async all() {
        await null;
        if (isSelect) queries.selects += 1; else queries.writes += 1;
        return { results: d1.prepare(normalized).all(...params) };
      },
      async run() {
        await null;
        if (isSelect) queries.selects += 1; else queries.writes += 1;
        d1.prepare(normalized).run(...params);
        return { success: true, meta: { changes: d1.prepare('SELECT changes() AS n').get().n } };
      },
    });
    return { bind, async first() { return bind().first(); }, async all() { return bind().all(); }, async run() { return bind().run(); } };
  }

  const env = {
    DB: { prepare: statement, async batch(entries) { for (const entry of entries) await entry.run(); return []; } },
    FIREBASE_PROJECT_ID: 'friendbook-17551',
    CF_ACCOUNT_ID: 'account-tag',
  };
  if (withAe) env.ANALYTICS_ENGINE = { writeDataPoint: (point) => { ae.events.push(point); } };
  if (aeToken) env.CF_AE_TOKEN = 'ae-read-token';
  if (withKv) {
    env.VIEWS = {
      async get(key, options) {
        await null;
        kv.reads += 1;
        const value = store.get(key);
        if (value === undefined) return null;
        return options?.type === 'json' ? JSON.parse(value) : value;
      },
      async put(key, value) { await null; kv.writes += 1; store.set(key, value); },
    };
  }

  // The existing R2 bucket, used ONLY as the view snapshot when Workers KV is not bound. It has no
  // delete method at all, and any key outside `views/` throws, so a test cannot pass by touching a
  // video object.
  if (withR2) {
    env.VIDEO_BUCKET = {
      async get(key) {
        await null;
        r2.reads += 1;
        r2.keys.push(`get ${key}`);
        const value = r2Objects.get(key);
        return value === undefined ? null : { key, async text() { return value; } };
      },
      async put(key, value) {
        await null;
        r2.writes += 1;
        r2.keys.push(`put ${key}`);
        if (!key.startsWith('views/')) throw new Error(`the view snapshot must never touch ${key}`);
        r2Objects.set(key, value);
        return { etag: 'test-etag' };
      },
    };
  }

  // The AE query API: `sql`/`graphql` may be a string body, { status, body }, null (refuse), or a
  // function of the call index, so a test can fail first and succeed on a later variant.
  const answer = (spec, callIndex) => {
    if (spec === null || spec === undefined) return { status: 403, body: '{"errors":[{"message":"refused"}]}' };
    if (typeof spec === 'function') return answer(spec(callIndex), 0);
    if (typeof spec === 'string') return { status: 200, body: spec };
    if (typeof spec === 'object' && !('status' in spec) && !('body' in spec)) return { status: 200, body: spec };
    return spec;
  };
  const realFetch = globalThis.fetch;
  globalThis.fetch = async (url, init) => {
    const target = String(url);
    fetches.push(target);
    if (target.includes('/analytics_engine/sql')) {
      const response = answer(sql, ae.sqlCalls.length);
      ae.sqlCalls.push({ url: target, init });
      return new Response(response.body, { status: response.status });
    }
    if (target.includes('/client/v4/graphql')) {
      const response = answer(graphql, ae.graphqlCalls.length);
      ae.graphqlCalls.push({ url: target, init });
      return new Response(typeof response.body === 'string' ? response.body : JSON.stringify(response.body), {
        status: response.status, headers: { 'Content-Type': 'application/json' },
      });
    }
    return realFetch(url, init);
  };

  return {
    env, d1, queries, seenSql, store, kv, r2, r2Objects, ae, fetches,
    close: () => { globalThis.fetch = realFetch; d1.close(); },
  };
}

/** Analytics Engine SQL answers as TSV: a header row, then one row per resource. */
const tsv = (rows) => 'resource\tviewers\tplays\n' + rows.map(([resource, viewers, plays]) => `${resource}\t${viewers}\t${plays ?? viewers}`).join('\n');
const graphqlRows = (rows) => ({
  data: {
    viewer: {
      accounts: [{
        views: rows.map(([name, uniq, count]) => ({
          dimensions: { name, count: count ?? uniq, uniq }, metrics: { count: count ?? uniq, sum: count ?? uniq },
        })),
      }],
    },
  },
});

async function call(env, { path = '/v1/views/reels/r1', method = 'GET', uid = 'user1', ids = null } = {}, now = Date.now, ctx) {
  const url = new URL(`https://worker.test${path}`);
  if (ids) url.searchParams.set('ids', ids);
  const request = new Request(url, { method });
  const response = await viewCounters(
    request, env, { uid, token: 'tok' }, path.match(/^\/v1\/views\/([a-z]+)\/([^/]+)$/), url, now, ctx);
  return { status: response.status, body: await response.json() };
}

test('every view is one Analytics Engine event and D1 is never written', async t => {
  resetViewState();
  const f = fixture({ kvSeed: { 'reels/r1': 40 }, sql: tsv([['reels/r1', 40]]) }); t.after(f.close);
  let clock = T0;
  const now = () => clock;

  for (const uid of ['user1', 'user2', 'user3']) {
    clock += 1000;
    const { status, body } = await call(f.env, { method: 'POST', uid }, now);
    assert.equal(status, 200);
    assert.equal(body.counted, true);
    assert.equal(body.event, 'analytics-engine');
    assert.equal(body.dedupe, 'once-per-user');
  }
  const served = await call(f.env, {}, now);

  assert.equal(f.ae.events.length, 3, 'one AE event per view');
  assert.ok(decodeURIComponent(f.ae.sqlCalls[0].url).includes('count(DISTINCT blob3) AS viewers'),
    'the exact de-dupe query is the first one asked');
  assert.deepEqual(f.ae.events[0].indexes, ['reels/r1'], 'the resource is the AE index (its grouping key)');
  assert.deepEqual(f.ae.events[0].dimensions, ['reels', 'r1', 'user1'], 'collection, id and uid travel as blobs');
  assert.deepEqual(f.ae.events[0].metrics, [1]);
  assert.equal(f.queries.writes, 0, 'the view path writes NOTHING to D1');
  assert.equal(f.queries.selects, 0, 'and reads nothing either: KV + AE served everything');
  assert.equal(f.seenSql.join(''), '', 'no D1 statement at all was prepared');
  assert.equal(served.body.views, 43, 'AE said 40, this isolate counted 3 more');
  assert.equal(served.body.storage, 'ae+kv+memory');
});

test('one user is one view forever, even when the same user watches ten times', async t => {
  resetViewState();
  const f = fixture({ kvSeed: { 'reels/r1': 0 }, sql: (index) => tsv([['reels/r1', index === 0 ? 0 : 1]]) });
  t.after(f.close);
  let clock = T0;
  const now = () => clock;

  const first = await call(f.env, { method: 'POST' }, now);
  assert.equal(first.body.counted, true);
  assert.equal(first.body.views, 1);

  for (let i = 0; i < 9; i++) {
    clock += 1000; // loops, rewatches, the whole video, coming back later
    const again = await call(f.env, { method: 'POST' }, now);
    assert.equal(again.body.counted, false, 'a repeat watcher never counts again');
    assert.equal(again.body.views, 1);
    assert.equal(f.queries.writes, 0, 'de-dupe costs no D1 write');
    assert.equal(f.queries.selects, 0, 'and no D1 read');
  }
  assert.equal(f.ae.events.length, 10, 'every real play is still recorded as an AE event');
  assert.equal(f.ae.events.filter((event) => event.metrics[0] === 1).length, 1,
    'but only the first play carries metric 1, so sum(double1) is viewers and count() is plays');
  assert.equal(f.ae.events.filter((event) => event.metrics[0] === 0).length, 9,
    'a repeat is still one event, but it carries metric 0 so plays never become viewers');
  assert.equal(viewCounterState().counted, 1, 'the isolate settled this user once');
});

test('Analytics Engine uniq(uid) is the real count, and a fresh isolate re-bases on it', async t => {
  resetViewState();
  // AE ingestion: nothing at the very first load, then the two distinct users, then all three.
  const f = fixture({
    kvSeed: { 'reels/r1': 0, 'reels/r2': 0 },
    sql: (index) => tsv(index === 0 ? [] : (index === 1 ? [['reels/r1', 2], ['reels/r2', 1]] : [['reels/r1', 3], ['reels/r2', 1]])),
  });
  t.after(f.close);
  let clock = T0;
  const now = () => clock;

  await call(f.env, { method: 'POST', uid: 'user1' }, now);
  clock += 1000;
  await call(f.env, { method: 'POST', uid: 'user2' }, now);
  assert.equal((await call(f.env, {}, now)).body.views, 2, 'two distinct users, counted the moment they watched');

  // A cold isolate that never saw them asks Analytics Engine - not D1 - for the true count.
  resetViewState();
  clock += 1000;
  assert.equal((await call(f.env, { path: '/v1/views/reels/r2' }, now)).body.views, 1);
  assert.equal((await call(f.env, {}, now)).body.views, 2);
  assert.equal(f.queries.selects, 0, 'AE replaced the D1 seed entirely');
  assert.equal(f.queries.writes, 0);
  assert.ok(f.ae.sqlCalls.length >= 2, 'each isolate queried AE once on load');

  // This isolate does not know user1, so it counts them once more: the live number may lead by one,
  // but AE's answer - and everything persisted - stays at the true unique count.
  clock += 1000;
  const drifted = await call(f.env, { method: 'POST', uid: 'user1' }, now);
  assert.equal(drifted.body.counted, true, 'memory alone cannot know a user this isolate never saw');
  assert.equal(drifted.body.views, 3, 'the live layer leads by one until AE answers again');

  clock += AE_MINUTE + KV_WINDOW;
  const settled = await call(f.env, { method: 'POST', uid: 'user3' }, now);
  assert.equal(settled.body.views, 4, 'AE absorbed one of the two bridged views; the truth is 3 users + this one');
  assert.equal(viewCounterState().aeBase.get('reels/r1'), 3, 'AE uniq(uid) is the durable, de-duplicated count');
  assert.equal(JSON.parse(f.store.get('views:live:v1'))['reels/r1'], 3,
    'the persisted snapshot carries AE truth, never an isolate drift');
});

test('AE is queried at most once a minute per isolate and never blocks a response', async t => {
  resetViewState();
  const f = fixture({ kvSeed: { 'reels/r1': 5 }, sql: tsv([['reels/r1', 5]]) }); t.after(f.close);
  let clock = T0;
  const now = () => clock;

  await call(f.env, {}, now); // the load query
  const before = f.ae.sqlCalls.length;
  assert.equal(before, 1, 'loading an isolate costs exactly one AE query');

  await call(f.env, { method: 'POST', uid: 'user1' }, now);
  assert.equal(f.ae.sqlCalls.length, before, 'a view adds no AE query of its own');
  for (let i = 0; i < 10; i++) { clock += 1000; await call(f.env, { method: 'POST', uid: `u${i}` }, now); }
  assert.equal(f.ae.sqlCalls.length, before, 'ten seconds of counting added no AE query');

  clock += AE_MINUTE;
  const pending = [];
  await call(f.env, { method: 'POST', uid: 'late' }, now, { waitUntil: (promise) => pending.push(promise) });
  assert.equal(pending.length, 1, 'the refresh was deferred to ctx.waitUntil, not awaited inline');
  await Promise.allSettled(pending);
  assert.equal(f.ae.sqlCalls.length, before + 1, 'one AE query per minute at most');
  assert.equal(f.queries.writes, 0);
});

test('a GraphQL-only account is served too, and a remembered variant is reused', async t => {
  resetViewState();
  const f = fixture({ kvSeed: {}, sql: null, graphql: graphqlRows([['reels/r1', 7]]) }); t.after(f.close);
  const now = () => T0;

  const served = await call(f.env, {}, now);
  assert.equal(served.body.views, 7, 'the GraphQL dataset query supplied the count');
  assert.equal(viewCounterState().aeVariant, 'graphql');
  assert.ok(f.ae.sqlCalls.length >= 1, 'the SQL variants were tried first');
  assert.equal(f.ae.graphqlCalls.length, 1);

  resetViewState();
  const second = await call(f.env, {}, () => T0 + 1000);
  assert.equal(second.body.views, 7, 'a later isolate is served by GraphQL too');
  assert.equal(viewCounterState().aeVariant, 'graphql');
  assert.equal(f.queries.selects, 0, 'AE answered, so the read-only D1 seed never ran');
  assert.equal(f.queries.writes, 0, 'and D1 was never written');
});

test('without Analytics Engine the counters fall back to KV + memory, still with zero D1 writes', async t => {
  resetViewState();
  const f = fixture({ withAe: false, kvSeed: { 'reels/r1': 30 } }); t.after(f.close);
  let clock = T0;
  const now = () => clock;

  for (const uid of ['user1', 'user2']) {
    clock += 1000;
    const posted = await call(f.env, { method: 'POST', uid }, now);
    assert.equal(posted.body.counted, true);
    assert.equal(posted.body.event, 'none', 'no AE binding, so no event - and nothing invented');
  }
  const served = await call(f.env, {}, now);
  assert.equal(served.body.views, 32);
  assert.equal(served.body.storage, 'kv+memory');
  assert.equal(f.queries.writes, 0, 'D1 is never written in any mode');
  assert.equal(f.queries.selects, 0, 'KV already held the totals, so no seed read was needed');

  clock += KV_WINDOW;
  await call(f.env, { method: 'POST', uid: 'user3' }, now);
  assert.deepEqual(JSON.parse(f.store.get('views:live:v1')), { 'reels/r1': 33 },
    'with no AE the live total is the only record, so the snapshot keeps it');
});

test('the read-only D1 seed is a last resort and never writes', async t => {
  resetViewState();
  const f = fixture({ kvSeed: null, sql: tsv([]) }); t.after(f.close);
  f.d1.exec(readMigration(MIGRATIONS[0]));
  f.d1.exec("INSERT INTO friendbook_cf_view_counters VALUES ('reels/r1', 25, 1)");

  const seeded = await call(f.env, {}, () => T0);
  assert.equal(seeded.body.views, 25, 'history AE and KV do not have is restored once, read-only');
  assert.equal(f.queries.selects, 1);
  assert.match(f.seenSql.join('\n'), /ORDER BY views DESC LIMIT \?/);
  assert.equal(f.queries.writes, 0, 'no CREATE, no INSERT, no UPDATE: D1 writes stay at zero');
  assert.deepEqual(JSON.parse(f.store.get('views:live:v1')), { 'reels/r1': 25 }, 'the seed is cached in KV');

  resetViewState();
  const later = await call(f.env, {}, () => T0 + 5000);
  assert.equal(later.body.views, 25);
  assert.equal(f.queries.selects, 1, 'a later isolate never touches D1 again');
  assert.equal(f.queries.writes, 0);
});

test('the KV snapshot is written at most once per five minutes', async t => {
  resetViewState();
  const f = fixture({ kvSeed: { 'reels/r1': 0 }, sql: tsv([['reels/r1', 0]]) }); t.after(f.close);
  let clock = T0;
  const now = () => clock;
  let watchers = 0;

  for (let i = 0; i < 20; i++) { clock += 1000; await call(f.env, { method: 'POST', uid: `u${++watchers}` }, now); }
  assert.equal(f.kv.writes, 0, 'twenty seconds of counting never rewrote KV');
  clock += KV_WINDOW;
  await call(f.env, { method: 'POST', uid: `u${++watchers}` }, now);
  const writes = f.kv.writes;
  assert.ok(writes >= 1, 'the AE refresh also refreshed the snapshot');
  clock += 1000;
  await call(f.env, { method: 'POST', uid: `u${++watchers}` }, now);
  assert.equal(f.kv.writes, writes, 'inside the window nothing is rewritten');
  assert.equal(f.queries.writes, 0);
});

test('batched and top reads are served from memory with no storage call at all', async t => {
  resetViewState();
  const f = fixture({ kvSeed: { 'reels/r1': 12, 'reels/r2': 9, 'posts/p1': 4 }, sql: tsv([]) }); t.after(f.close);
  const now = () => T0;

  const batch = await call(f.env, { path: '/v1/views?collection=reels', ids: 'r1,r2,missing' }, now);
  assert.deepEqual(batch.body.views, { r1: 12, r2: 9, missing: 0 });
  assert.equal(batch.body.storage, 'ae+kv+memory');
  const top = await call(f.env, { path: '/v1/views?collection=reels&top=1' }, now);
  assert.deepEqual(top.body.views, { r1: 12 });
  const posts = await call(f.env, { path: '/v1/views?collection=posts', ids: 'p1' }, now);
  assert.deepEqual(posts.body.views, { p1: 4 });
  assert.equal(f.queries.writes, 0);
  assert.equal(f.kv.reads, 1, 'one KV read for the whole isolate, not one per request');
});

test('input validation, methods and auth fail closed with no event and no storage write', async t => {
  resetViewState();
  const f = fixture({ kvSeed: { 'reels/r1': 0 }, sql: tsv([]) }); t.after(f.close);
  const now = () => T0;

  assert.equal((await call(f.env, { path: '/v1/views/stories/x', method: 'POST' }, now)).status, 400);
  assert.equal((await call(f.env, { path: '/v1/views/reels/..', method: 'POST' }, now)).status, 400);
  assert.equal((await call(f.env, { path: '/v1/views/reels/a,b', method: 'POST' }, now)).status, 400);
  assert.equal((await call(f.env, { path: '/v1/views/reels/' + 'x'.repeat(129), method: 'POST' }, now)).status, 400);
  assert.equal((await call(f.env, { path: '/v1/views/reels/', method: 'POST' }, now)).status, 405);
  assert.equal((await call(f.env, { method: 'DELETE' }, now)).status, 405);
  assert.equal((await call(f.env, { path: '/v1/views', method: 'POST' }, now)).status, 405);
  assert.equal((await call(f.env, { path: '/v1/views', uid: 'user1' }, now)).status, 400, 'collection is required');
  assert.equal((await call(f.env, { method: 'POST', uid: '' }, now)).status, 401);
  assert.equal((await call(f.env, { method: 'POST', uid: 'x'.repeat(129) }, now)).status, 401);
  const many = Array.from({ length: 51 }, (_, i) => `r${i}`).join(',');
  assert.equal((await call(f.env, { path: '/v1/views?collection=reels', ids: many }, now)).status, 400);

  assert.equal(f.ae.events.length, 0, 'a rejected request records no view event');
  assert.equal(f.queries.writes, 0);
  assert.equal(f.queries.selects, 0);
});

test('migration stays repeatable and the view path never needs it', async t => {
  const sql = new DatabaseSync(':memory:');
  t.after(() => sql.close());
  sql.exec('CREATE TABLE posts (id TEXT PRIMARY KEY, views INTEGER)');
  sql.exec("INSERT INTO posts VALUES ('p1', 900)");
  const text = MIGRATIONS.map((name) => ({ name, text: readMigration(name) }));
  for (const pass of [1, 2]) for (const migration of text) sql.exec(migration.text);
  assert.equal(sql.prepare('SELECT views FROM posts WHERE id = ?').get('p1').views, 900);

  // The Worker no longer creates or writes these tables: a view works on a database that lacks them.
  resetViewState();
  const bare = fixture({ kvSeed: { 'reels/r1': 3 }, sql: tsv([['reels/r1', 3]]) }); t.after(bare.close);
  const served = await call(bare.env, { method: 'POST', uid: 'user9' }, () => T0);
  assert.equal(served.body.views, 4);
  assert.equal(bare.queries.writes, 0);
  assert.equal(bare.seenSql.length, 0, 'not one D1 statement was prepared for a counted view');
});

test('worker exposes /v1/views behind the Firebase token gate and reports its bindings', async t => {
  resetViewState();
  const f = fixture({ kvSeed: { 'reels/r1': 12 }, sql: tsv([['reels/r1', 12]]) }); t.after(f.close);
  const worker = createWorker(async () => 'user1');
  const fetchWith = async (path, method, token = 'id-token') => {
    const response = await worker.fetch(
      new Request(`https://worker.test${path}`, { method, headers: token ? { Authorization: `Bearer ${token}` } : {} }),
      f.env, { waitUntil: () => {}, passThroughOnException: () => {} },
    );
    return { status: response.status, body: response.headers.get('content-type')?.includes('json') ? await response.clone().json() : null };
  };

  const counted = await fetchWith('/v1/views/reels/r1', 'POST');
  assert.equal(counted.status, 403);
  assert.equal((await fetchWith('/v1/views?collection=reels&top=10', 'GET')).status, 400);
  assert.equal(f.ae.events.length, 0);

  const health = await fetchWith('/health', 'GET', null);
  assert.equal(health.status, 200);
  assert.deepEqual(health.body.views, { ae: true, kv: true, r2Snapshot: false, aeReadToken: true },
    '/health proves which view bindings a deploy actually shipped');
  assert.equal(f.queries.writes, 0);

  resetViewState();
  assert.equal((await fetchWith('/v1/views/reels/r1', 'POST', null)).status, 401, 'no token, no view');
  assert.equal(f.ae.events.length, 0, 'an unauthenticated request recorded nothing new');
});

test('when Workers KV is not bound, the R2 snapshot keeps the counts across isolates', async t => {
  resetViewState();
  const f = fixture({ withKv: false, withR2: true, r2Seed: { 'reels/r1': 30 }, withAe: false, aeToken: false });
  t.after(f.close);
  let clock = T0;
  const now = () => clock;

  const first = await call(f.env, { method: 'POST', uid: 'user1' }, now);
  assert.equal(first.body.counted, true);
  assert.equal(first.body.views, 31, 'the snapshot supplied the history, memory supplied the new view');
  assert.equal(first.body.storage, 'r2+memory');
  assert.equal(first.body.event, 'none', 'no AE binding, so no event - and nothing invented');

  clock += KV_WINDOW;
  const second = await call(f.env, { method: 'POST', uid: 'user2' }, now);
  assert.equal(second.body.views, 32);
  assert.deepEqual(JSON.parse(f.r2Objects.get('views/live/v1.json')), { 'reels/r1': 32 },
    'the live total is written back to the snapshot object');
  assert.equal(f.r2.writes, 1, 'one snapshot write per 5 minute window');
  assert.ok(f.r2.keys.every((key) => !key.includes('videos/')), 'no video key was ever touched');
  assert.equal(typeof f.env.VIDEO_BUCKET.delete, 'undefined', 'the snapshot store cannot delete anything');

  resetViewState(); // a cold isolate, exactly what a real Worker restart looks like
  const afterRestart = await call(f.env, {}, () => clock + 1000);
  assert.equal(afterRestart.body.views, 32, 'the count survived the isolate, it never went backwards');
  assert.equal(viewCounterState().snapshotStore, 'r2');
  assert.equal(f.queries.writes, 0, 'D1 is never written in any mode');
  assert.equal(f.queries.selects, 0, 'and the snapshot meant no D1 seed read either');
});

test('Analytics Engine stays the truth over the R2 snapshot', async t => {
  resetViewState();
  const f = fixture({ withKv: false, withR2: true, r2Seed: { 'reels/r1': 5 }, sql: tsv([['reels/r1', 50]]) });
  t.after(f.close);
  let clock = T0;
  const now = () => clock;

  const served = await call(f.env, {}, now);
  assert.equal(served.body.views, 50, 'uniq(uid) from Analytics Engine wins over an older snapshot');
  assert.equal(served.body.storage, 'ae+r2+memory');
  assert.equal(viewCounterState().snapshotStore, 'r2');

  clock = T0 + KV_WINDOW + 1000;
  await call(f.env, { method: 'POST', uid: 'user9' }, () => clock);
  const snapshot = JSON.parse(f.r2Objects.get('views/live/v1.json'));
  assert.equal(snapshot['reels/r1'], 50,
    'with AE answering, the snapshot stores the de-duplicated truth, not this isolate\'s bridge');
  assert.equal(f.queries.writes, 0);
});

test('a refused viewer query is never replaced by a plays count', async t => {
  resetViewState();
  // This AE API answered `unknown function call: UNIQ`, so while every viewer query is refused the
  // counters must be served from the snapshot + memory - never from count(), which counts plays
  // (every rewatch) and would break "1 user = 1 view". The events keep being written either way, and
  // the moment a viewer query answers, AE becomes the source of truth.
  const f = fixture({
    kvSeed: { 'reels/r1': 4 },
    sql: (call) => (call < 4 ? null : tsv([['reels/r1', 9]])),
  });
  t.after(f.close);
  let clock = T0;
  const now = () => clock;

  const refused = await call(f.env, {}, now);
  assert.equal(refused.body.views, 4, 'the snapshot served the count, not a plays total');
  assert.equal(refused.body.storage, 'kv+memory', 'AE refused, so AE is not claimed as the source');
  assert.equal(viewCounterState().aeUsable, false);
  assert.equal(f.ae.sqlCalls.length, 4, 'every viewer variant was tried, and none was a plain count()');
  assert.ok(f.ae.sqlCalls.every((entry) => !/SELECT index1 AS resource, count\(\)/.test(decodeURIComponent(entry.url))),
    'a plays-only query is never used as the viewer count');

  const posted = await call(f.env, { method: 'POST', uid: 'user1' }, now);
  assert.equal(posted.body.counted, true);
  assert.equal(posted.body.views, 5, 'the live layer still counted the view while AE was refusing');
  assert.equal(posted.body.event, 'analytics-engine', 'the event was written regardless');
  assert.deepEqual(f.ae.events[0].metrics, [1], 'a counted view carries metric 1');

  clock += AE_MINUTE;
  const pending = [];
  await call(f.env, { method: 'POST', uid: 'user1' }, now, { waitUntil: (promise) => pending.push(promise) });
  await Promise.allSettled(pending);
  assert.equal(viewCounterState().aeUsable, true, 'the next refresh found a viewer query that answers');
  assert.equal(viewCounterState().aeVariant, 0);
  const served = await call(f.env, {}, () => clock + 1000);
  assert.equal(served.body.views, 9, 'sum(double1) - viewers, not plays - is what users see');
  assert.equal(served.body.storage, 'ae+kv+memory');
  assert.deepEqual(f.ae.events[1].metrics, [0], 'the same user watching again carries metric 0');
  assert.equal(f.queries.writes, 0, 'none of this ever writes to D1');
});

function readMigration(name) {
  const { readFileSync } = require('node:fs');
  return readFileSync(resolve(root, 'cloudflare', 'migrations', name), 'utf8');
}
