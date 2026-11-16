import { test } from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { buildQuery, buildCountQuery, parseWhere, parseOrderBy, parseQueryParams, decodeCursor, encodeCursor, QueryError } from '../doc-query.js';

// Minimal D1-shaped adapter over node:sqlite, matching what the worker receives.
function db() {
  const sql = new DatabaseSync(':memory:');
  sql.exec(`CREATE TABLE friendbook_cf_documents (
    path TEXT PRIMARY KEY NOT NULL,
    collection TEXT NOT NULL,
    doc_id TEXT NOT NULL,
    owner_uid TEXT NOT NULL DEFAULT '',
    document_json TEXT NOT NULL,
    updated_at INTEGER NOT NULL DEFAULT 0
  )`);
  return {
    sql,
    prepare(query) {
      const stmt = sql.prepare(query);
      let bound = [];
      const api = {
        bind(...params) { bound = params; return api; },
        async all() { return { results: stmt.all(...bound) }; },
        async first() { return stmt.get(...bound) ?? null; },
        async run() { stmt.run(...bound); return { success: true }; },
      };
      return api;
    },
    insert(collection, id, doc) {
      sql.prepare('INSERT INTO friendbook_cf_documents(path,collection,doc_id,owner_uid,document_json,updated_at) VALUES (?,?,?,?,?,?)')
        .run(`${collection}/${id}`, collection, id, '', JSON.stringify({ id, ...doc }), 0);
    },
  };
}

async function run(d, params) {
  const built = buildQuery(params);
  const rows = await d.prepare(built.sql).bind(...built.params).all();
  return rows.results.map(r => JSON.parse(r.document_json));
}

test('equality filter returns only matching documents', async () => {
  const d = db();
  d.insert('posts', 'a', { authorId: 'u1', createdAt: 10 });
  d.insert('posts', 'b', { authorId: 'u2', createdAt: 20 });
  d.insert('posts', 'c', { authorId: 'u1', createdAt: 30 });

  const got = await run(d, { collection: 'posts', wheres: ['authorId:eq:u1'], orderBy: { field: 'createdAt', dir: 'desc' } });
  assert.deepEqual(got.map(x => x.id), ['c', 'a']);
});

test('numeric range operators compare numerically, not as strings', async () => {
  const d = db();
  for (const [id, n] of [['a', 2], ['b', 10], ['c', 100]]) d.insert('posts', id, { createdAt: n });
  const got = await run(d, { collection: 'posts', wheres: ['createdAt:gt:9'], orderBy: { field: 'createdAt', dir: 'asc' } });
  // String comparison would wrongly include '2' and order '10' before '2'.
  assert.deepEqual(got.map(x => x.id), ['b', 'c']);
});

test('array_contains matches membership like Firestore whereArrayContains', async () => {
  const d = db();
  d.insert('chats', 'one', { participants: ['u1', 'u2'] });
  d.insert('chats', 'two', { participants: ['u2', 'u3'] });
  d.insert('chats', 'none', { participants: [] });
  const got = await run(d, { collection: 'chats', wheres: ['participants:array_contains:u1'] });
  assert.deepEqual(got.map(x => x.id), ['one']);
});

test('array_contains_any matches any of several values', async () => {
  const d = db();
  d.insert('chats', 'one', { participants: ['u1'] });
  d.insert('chats', 'two', { participants: ['u9'] });
  d.insert('chats', 'three', { participants: ['u7'] });
  const got = await run(d, { collection: 'chats', wheres: ['participants:array_contains_any:u1,u7'] });
  assert.deepEqual(got.map(x => x.id).sort(), ['one', 'three']);
});

test('pagination with cursors never skips or repeats a document', async () => {
  const d = db();
  const ids = [];
  for (let i = 0; i < 25; i++) { const id = `p${String(i).padStart(2, '0')}`; ids.push(id); d.insert('posts', id, { createdAt: i * 10 }); }

  const seen = [];
  let after = null;
  for (let page = 0; page < 10; page++) {
    const built = buildQuery({ collection: 'posts', orderBy: { field: 'createdAt', dir: 'asc' }, limit: 7, after });
    const rows = await d.prepare(built.sql).bind(...built.params).all();
    const results = rows.results;
    const hasMore = results.length > built.limit;
    const pageRows = hasMore ? results.slice(0, built.limit) : results;
    const docs = pageRows.map(r => JSON.parse(r.document_json));
    seen.push(...docs.map(x => x.id));
    if (!hasMore) break;
    const last = docs[docs.length - 1];
    after = encodeCursor(last.createdAt, last.id);
    assert.ok(decodeCursor(after));
  }
  assert.deepEqual(seen, ids, 'every document exactly once, in order');
});

test('descending order and cursor walk agree', async () => {
  const d = db();
  for (const n of [1, 5, 3, 9]) d.insert('posts', `n${n}`, { score: n });
  const built = buildQuery({ collection: 'posts', orderBy: { field: 'score', dir: 'desc' }, limit: 2 });
  const first = (await d.prepare(built.sql).bind(...built.params).all()).results
    .slice(0, 2).map(r => JSON.parse(r.document_json));
  assert.deepEqual(first.map(x => x.score), [9, 5]);
  const after = encodeCursor(first[1].score, first[1].id);
  const second = await run(d, { collection: 'posts', orderBy: { field: 'score', dir: 'desc' }, limit: 2, after });
  assert.deepEqual(second.map(x => x.score), [3, 1]);
});

test('a filter is never satisfiable by injecting SQL through a field name', () => {
  assert.throws(() => parseWhere("authorId') OR 1=1--:eq:x"), /Invalid where field/);
  assert.throws(() => parseWhere('authorId:eq'), /Malformed/);
  assert.throws(() => parseWhere('authorId:unknown:x'), /Unsupported operator/);
  assert.throws(() => parseOrderBy("createdAt;DROP TABLE"), /Invalid orderBy/);
});

test('values stay bound parameters instead of being interpolated', () => {
  const built = buildQuery({ collection: 'posts', wheres: ["authorId:eq:o'brien"], limit: 5 });
  assert.ok(!built.sql.includes("o'brien"), 'value must not appear in SQL text');
  assert.ok(built.params.includes("o'brien"), 'value must be a bound parameter');
});

test('operator allowlist and limits are enforced', () => {
  assert.throws(() => buildQuery({ collection: 'posts', wheres: ['a:bogusOP:1'] }), QueryError);
  assert.throws(() => parseQueryParams(new URL('https://x/v1/docs?collection=posts&limit=abc')), QueryError);
  assert.throws(() => parseQueryParams(new URL('https://x/v1/docs?collection=posts&limit=0')), QueryError);
  const huge = buildQuery({ collection: 'posts', limit: 100000 });
  assert.equal(huge.limit, 100, 'limit is clamped');
});

test('query params parse a full Firestore-shaped request', () => {
  const url = new URL('https://x/v1/docs?collection=posts&where=authorId:eq:u1'
    + '&where=createdAt:lt:999&orderBy=createdAt:desc&limit=25');
  const q = parseQueryParams(url);
  assert.equal(q.collection, 'posts');
  assert.deepEqual(q.wheres, ['authorId:eq:u1', 'createdAt:lt:999']);
  assert.deepEqual(q.orderBy, { field: 'createdAt', dir: 'desc' });
  assert.equal(q.limit, 25);
  const built = buildQuery(q);
  assert.ok(built.sql.includes('ORDER BY'));
  assert.ok(built.sql.includes('DESC'));
});

test('exists and not_exists filter on field presence', async () => {
  const d = db();
  d.insert('posts', 'withMedia', { mediaUrl: 'https://x/y.jpg' });
  d.insert('posts', 'noMedia', {});
  assert.deepEqual((await run(d, { collection: 'posts', wheres: ['mediaUrl:exists:1'] })).map(x => x.id), ['withMedia']);
  assert.deepEqual((await run(d, { collection: 'posts', wheres: ['mediaUrl:not_exists:1'] })).map(x => x.id), ['noMedia']);
});

// A badge asks "how many chats have unread messages for me", and the answer lives at
// unreadCount.<uid> - a nested field. Without dotted paths that filter cannot be expressed as
// a COUNT, so the alternative was fetching every chat document to count them in the app.
test('a dotted field name filters a nested value', () => {
  const built = buildCountQuery({
    collection: 'chats',
    wheres: ['unreadCount.uid_a:gt:0'],
  });
  assert.match(built.sql, /json_extract\(document_json, '\$\.unreadCount\.uid_a'\)/);
  assert.deepEqual(built.params, ['chats', 0]);
});

// The dot must not become a way to inject arbitrary text into the generated JSON path.
test('a dotted field name is validated segment by segment', () => {
  for (const bad of [
    "unreadCount.uid') OR 1=1 --",
    'unreadCount.',
    '.unreadCount',
    'unreadCount.a.b.c.d.e.f',
    'unreadCount..uid',
    'unreadCount.uid;DROP',
  ]) {
    assert.throws(
      () => buildCountQuery({ collection: 'chats', wheres: [bad + ':gt:0'] }),
      /Invalid where field|Invalid field/,
      'must reject ' + bad
    );
  }
});
