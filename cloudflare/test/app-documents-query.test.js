import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import { createWorker } from '../../worker.js';

const MIGRATIONS = ['0001_additive_profiles.sql', '0002_additive_copy_staging.sql',
  '0003_additive_interactions.sql', '0011_additive_app_documents.sql'];

function createSqliteD1() {
  const sql = new DatabaseSync(':memory:');
  for (const name of MIGRATIONS) {
    sql.exec(readFileSync(new URL(`../migrations/${name}`, import.meta.url), 'utf8'));
  }
  const db = {
    prepare(query) {
      const stmt = sql.prepare(query);
      return {
        bind(...args) {
          return {
            run: async () => stmt.run(...args),
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
  return { sql, db };
}

async function seed(db) {
  const docs = [
    { path: 'posts/p1', id: 'p1', body: { authorId: 'a1', content: 'one', privacy: 'Public', createdAt: 100, mediaType: 'none' } },
    { path: 'posts/p2', id: 'p2', body: { authorId: 'a1', content: 'two', privacy: 'Friends', createdAt: 200, mediaType: 'video' } },
    { path: 'posts/p3', id: 'p3', body: { authorId: 'a2', content: 'three', privacy: 'Public', createdAt: 300, mediaType: 'photo' } },
    { path: 'posts/p4', id: 'p4', body: { authorId: 'a2', content: 'gone', privacy: 'Public', createdAt: 400, archived: true } },
    { path: 'posts/p5', id: 'p5', body: { authorId: 'a3', content: 'tags', privacy: 'Public', createdAt: 500, taggedUids: ['a1', 'a9'] } },
  ];
  for (const d of docs) {
    await db.prepare(
      `INSERT INTO friendbook_cf_documents (path, collection, doc_id, owner_uid, document_json, updated_at)
       VALUES (?, 'posts', ?, ?, ?, ?)`,
    ).bind(d.path, d.id, d.body.authorId, JSON.stringify(d.body), d.body.createdAt).run();
  }
}

async function get(env, query) {
  const w = createWorker(async () => 'reader');
  const res = await w.fetch(new Request(`https://example.com/v1/docs?${query}`, {
    headers: { Authorization: 'Bearer test-token' },
  }), env);
  return { status: res.status, body: await res.json() };
}

test('the read engine orders by a document field and pages by the same cursor the app uses', async () => {
  const { db } = createSqliteD1();
  await seed(db);
  const env = { DB: db, FIREBASE_PROJECT_ID: 'friendbook-test' };

  const first = await get(env, 'collection=posts&order=createdAt&dir=desc&limit=2');
  assert.equal(first.status, 200);
  assert.deepEqual(first.body.documents.map(d => d.id), ['p5', 'p3']);
  assert.equal(first.body.cursor, 'p3');
  assert.equal(first.body.hasMore, true);

  const second = await get(env, `collection=posts&order=createdAt&dir=desc&limit=2&cursor=${first.body.cursor}`);
  assert.deepEqual(second.body.documents.map(d => d.id), ['p2', 'p1']);
  assert.equal(second.body.cursor, 'p1');
  assert.equal(second.body.hasMore, false);

  // Ascending means the other end of the same ordering.
  const asc = await get(env, 'collection=posts&order=createdAt&dir=asc&limit=2');
  assert.deepEqual(asc.body.documents.map(d => d.id), ['p1', 'p2']);
});

test('archived documents stay out of a page and a requested field filter is applied', async () => {
  const { db } = createSqliteD1();
  await seed(db);
  const env = { DB: db, FIREBASE_PROJECT_ID: 'friendbook-test' };

  const all = await get(env, 'collection=posts&order=createdAt&limit=50');
  assert.deepEqual(all.body.documents.map(d => d.id), ['p5', 'p3', 'p2', 'p1']);
  assert.ok(!all.body.documents.some(d => d.archived));

  const archived = await get(env, 'collection=posts&order=createdAt&limit=50&archived=1');
  assert.ok(archived.body.documents.some(d => d.id === 'p4'));

  const videos = await get(env, 'collection=posts&order=createdAt&where=mediaType:eq:video&limit=50');
  assert.deepEqual(videos.body.documents.map(d => d.id), ['p2']);
  assert.equal(videos.body.filtered, true);

  const publicOrFriends = await get(env, 'collection=posts&order=createdAt&where=privacy:in:Public|Friends&limit=50');
  assert.deepEqual(publicOrFriends.body.documents.map(d => d.id), ['p5', 'p3', 'p2', 'p1']);

  const tagged = await get(env, 'collection=posts&order=createdAt&where=taggedUids:contains:a1&limit=50');
  assert.deepEqual(tagged.body.documents.map(d => d.id), ['p5']);

  const newer = await get(env, 'collection=posts&order=createdAt&where=createdAt:gt:250&limit=50');
  assert.deepEqual(newer.body.documents.map(d => d.id), ['p5', 'p3']);
});

test('a filtered page is never served from the shared newest-N cache', async () => {
  const { db } = createSqliteD1();
  await seed(db);
  const env = { DB: db, FIREBASE_PROJECT_ID: 'friendbook-test' };

  const plain = await get(env, 'collection=posts&order=createdAt&limit=2');
  assert.equal(plain.body.cached, undefined);
  const plainAgain = await get(env, 'collection=posts&order=createdAt&limit=2');
  assert.equal(plainAgain.body.cached, true, 'the plain page may be cached inside the isolate');

  const mine = await get(env, 'collection=posts&order=createdAt&where=authorId:eq:a2&limit=50');
  assert.deepEqual(mine.body.documents.map(d => d.id), ['p3']);
  const other = await get(env, 'collection=posts&order=createdAt&where=authorId:eq:a1&limit=50');
  assert.deepEqual(other.body.documents.map(d => d.id), ['p2', 'p1']);
});

test('an unsupported or malformed query is refused instead of returning the wrong page', async () => {
  const { db } = createSqliteD1();
  await seed(db);
  const env = { DB: db, FIREBASE_PROJECT_ID: 'friendbook-test' };

  assert.equal((await get(env, 'collection=posts&order=document_json')).status, 400);
  assert.equal((await get(env, 'collection=posts&order=createdAt);DROP TABLE posts;--')).status, 400);
  assert.equal((await get(env, 'collection=posts&where=content:like:one')).status, 400);
  assert.equal((await get(env, 'collection=posts&where=content:eq')).status, 400);
  assert.equal((await get(env, 'collection=nope&order=createdAt')).status, 400);
  // A cursor must be a document id, not SQL.
  assert.equal((await get(env, 'collection=posts&order=createdAt&cursor=a%2Fb')).status, 400);
  // Sanity: the table is still there after the injection attempt.
  assert.equal((await get(env, 'collection=posts&order=createdAt&limit=50')).status, 200);
});
