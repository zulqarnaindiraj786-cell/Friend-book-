import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import { createWorker } from '../../worker.js';
import {
  COLLECTION_SPECS, SPEC_BY_COLLECTION, camel, mapRow, rebuildPage, verifyCollection,
} from '../document-rebuild.js';

// The migration tables (0014) and the app document store (0011) are two shapes of the same
// data. The rebuild converts rows into the documents the app actually reads, posts first,
// and refuses to touch the other nine collections until posts verifies.

const MIGRATION_0011 = readFileSync(new URL('../migrations/0011_additive_app_documents.sql', import.meta.url), 'utf8');
const MIGRATION_0014 = readFileSync(new URL('../migrations/0014_additive_core_schema.sql', import.meta.url), 'utf8');

const TOKEN = 'x'.repeat(48);

function makeStatement(sql, query, args = []) {
  const statement = sql.prepare(query);
  return {
    bind: (...next) => makeStatement(sql, query, next),
    run: async () => statement.run(...args),
    all: async () => ({ results: statement.all(...args) }),
    first: async () => statement.get(...args) || null,
    __run: () => statement.run(...args),
  };
}

function createSqliteD1() {
  const sql = new DatabaseSync(':memory:');
  sql.exec(MIGRATION_0014);
  sql.exec(MIGRATION_0011);
  const db = {
    prepare: query => makeStatement(sql, query),
    async batch(statements) {
      return statements.map(statement => statement.__run());
    },
  };
  return { sql, db };
}

function env(db, token = TOKEN) {
  return { DB: db, MIGRATION_ADMIN_TOKEN: token, FIREBASE_PROJECT_ID: 'friendbook-test' };
}

function adminRequest(path, { method = 'GET', body, token = TOKEN } = {}) {
  const headers = {};
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  return new Request(`https://test.invalid${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}

function insertPost(sql, id, overrides = {}) {
  const row = {
    id,
    author_id: 'uid_author',
    author_name: 'Author',
    content: `content ${id}`,
    media_type: 'none',
    privacy: 'Public',
    post_type: 'regular',
    archived: 0,
    trashed_at: 0,
    likes: 3,
    comments: 0,
    shares: 0,
    views: 7,
    created_at: 1_700_000_000_000 + Number(String(id).replace(/\D/g, '') || 0),
    saved_by: '[]',
    media_urls: '["https://cdn.example/a.jpg"]',
    poll_votes: '{}',
    reactions_map: '{"uid_author":"like"}',
    author_verified: 1,
    author_tick_level: 'gold',
    ...overrides,
  };
  const columns = Object.keys(row);
  sql.prepare(
    `INSERT INTO posts (${columns.join(',')}) VALUES (${columns.map(() => '?').join(',')})`,
  ).run(...columns.map(column => row[column]));
  return row;
}

test('every migration table has one rebuild spec and posts is the test collection', () => {
  assert.deepEqual(COLLECTION_SPECS.map(spec => spec.collection), [
    'posts', 'users', 'comments', 'reels', 'stories', 'chats', 'messages', 'groups',
    'friend_requests', 'notifications',
  ]);
  assert.deepEqual(COLLECTION_SPECS.filter(spec => spec.testCollection).map(spec => spec.collection), ['posts']);
  for (const spec of COLLECTION_SPECS) assert.equal(spec.ownerColumns.length > 0, true);
});

test('snake_case columns map to the camelCase fields the app reads', () => {
  assert.equal(camel('author_photo'), 'authorPhoto');
  assert.equal(camel('is_admin'), 'isAdmin');
  assert.equal(camel('poll_votes'), 'pollVotes');
  assert.equal(camel('id'), 'id');
});

test('mapRow keeps JSON arrays/objects typed and turns 0/1 columns into booleans', () => {
  const spec = SPEC_BY_COLLECTION.get('posts');
  const { doc, warnings, error } = mapRow(spec, {
    id: 'post_1',
    author_id: 'uid_1',
    archived: 0,
    market_sold: '1',
    author_verified: 0,
    created_at: 1_700_000_000_000,
    media_urls: '["a","b"]',
    poll_votes: '{"opt":2}',
    reactions_map: 'not-json',
    likes: 4,
  });
  assert.equal(error, null);
  assert.equal(doc.id, 'post_1');
  assert.equal(doc.authorId, 'uid_1');
  assert.equal(doc.authorPhoto, undefined, 'absent columns stay absent');
  assert.equal(doc.archived, false);
  assert.equal(doc.marketSold, true);
  assert.equal(doc.authorVerified, false);
  assert.deepEqual(doc.mediaUrls, ['a', 'b']);
  assert.deepEqual(doc.pollVotes, { opt: 2 });
  assert.deepEqual(doc.reactionsMap, {}, 'malformed JSON falls back to the declared default');
  assert.equal(doc.likes, 4);
  assert.equal(doc.updatedAt, 1_700_000_000_000);
  assert.deepEqual(warnings, [{ column: 'reactions_map', reason: 'invalid-json' }]);
});

test('users documents carry both id and uid, plus real booleans for the ACL', () => {
  const spec = SPEC_BY_COLLECTION.get('users');
  const { doc } = mapRow(spec, {
    uid: 'uid_1', display_name: 'A', banned: 0, is_admin: 1, blocked: '["uid_2"]', created_at: 0,
  });
  assert.equal(doc.id, 'uid_1');
  assert.equal(doc.uid, 'uid_1');
  assert.equal(doc.displayName, 'A');
  assert.equal(doc.banned, false);
  assert.equal(doc.isAdmin, true);
  assert.deepEqual(doc.blocked, ['uid_2']);
  assert.equal(doc.updatedAt, 0);
});

test('documents with an unusable id are rejected instead of written', () => {
  const spec = SPEC_BY_COLLECTION.get('posts');
  assert.equal(mapRow(spec, { id: 'bad/id' }).error, 'invalid id');
  assert.equal(mapRow(spec, { id: '' }).error, 'invalid id');
});

test('rebuild is a dry run unless apply is sent, and posts paging uses a keyset cursor', async () => {
  const { sql, db } = createSqliteD1();
  for (const id of ['p1', 'p2', 'p3']) insertPost(sql, id);
  const spec = SPEC_BY_COLLECTION.get('posts');

  const preview = await rebuildPage(db, spec, { limit: 2 });
  assert.equal(preview.dryRun, true);
  assert.equal(preview.scanned, 2);
  assert.equal(preview.converted, 2);
  assert.equal(preview.written, 0);
  assert.equal(preview.nextCursor, 'p2');
  assert.equal(sql.prepare('SELECT COUNT(*) AS n FROM friendbook_cf_documents').get().n, 0);

  const applied = await rebuildPage(db, spec, { limit: 2, apply: true });
  assert.equal(applied.written, 2);
  const next = await rebuildPage(db, spec, { limit: 2, apply: true, after: applied.nextCursor });
  assert.equal(next.written, 1);
  assert.equal(next.nextCursor, null);
  assert.equal(sql.prepare('SELECT COUNT(*) AS n FROM friendbook_cf_documents').get().n, 3);

  const verify = await verifyCollection(db, spec, 10);
  assert.equal(verify.sourceRows, 3);
  assert.equal(verify.documentRows, 3);
  assert.equal(verify.checked, 3);
  assert.equal(verify.mismatchCount, 0);
  assert.equal(verify.sourceRowsWithoutDocument, 0);
});

test('re-running does not overwrite documents unless overwrite is explicit', async () => {
  const { sql, db } = createSqliteD1();
  insertPost(sql, 'p1', { content: 'relational copy' });
  sql.prepare(
    `INSERT INTO friendbook_cf_documents(path, collection, doc_id, owner_uid, document_json, updated_at)
     VALUES ('posts/p1','posts','p1','uid_author',?,1)`,
  ).run(JSON.stringify({ id: 'p1', content: 'preserved staging copy', authorId: 'uid_author', archived: false }));
  const spec = SPEC_BY_COLLECTION.get('posts');

  const first = await rebuildPage(db, spec, { apply: true });
  assert.equal(first.written, 0);
  assert.equal(first.skippedExisting, 1);
  assert.equal(JSON.parse(sql.prepare("SELECT document_json FROM friendbook_cf_documents WHERE path='posts/p1'").get().document_json).content,
    'preserved staging copy');

  const second = await rebuildPage(db, spec, { apply: true, overwrite: true });
  assert.equal(second.written, 1);
  assert.equal(JSON.parse(sql.prepare("SELECT document_json FROM friendbook_cf_documents WHERE path='posts/p1'").get().document_json).content,
    'relational copy');
});

test('the rebuild routes use the existing migration token rules, not a Firebase session', async () => {
  const { db } = createSqliteD1();
  const worker = createWorker(async () => 'uid_any');

  const unconfigured = await worker.fetch(adminRequest('/v1/admin/migrate/documents/plan'),
    { DB: db, FIREBASE_PROJECT_ID: 'friendbook-test' });
  assert.equal(unconfigured.status, 503, 'no MIGRATION_ADMIN_TOKEN means no migration API');

  const noToken = await worker.fetch(adminRequest('/v1/admin/migrate/documents/plan', { token: '' }), env(db));
  assert.equal(noToken.status, 401);

  const wrong = await worker.fetch(adminRequest('/v1/admin/migrate/documents/plan', { token: 'firebase-user' }), env(db));
  assert.equal(wrong.status, 403);

  const ok = await worker.fetch(adminRequest('/v1/admin/migrate/documents/plan'), env(db));
  assert.equal(ok.status, 200);
  assert.equal((await ok.json()).testCollection, 'posts');
});

test('non-posts collections stay blocked until the live posts gate passes', async () => {
  const { sql, db } = createSqliteD1();
  const worker = createWorker(async () => 'uid_any');
  sql.prepare("INSERT INTO users (uid, display_name) VALUES ('uid_1','A')").run();
  insertPost(sql, 'p1');

  const blocked = await worker.fetch(adminRequest('/v1/admin/migrate/documents/rebuild',
    { method: 'POST', body: { collection: 'users', apply: true } }), env(db));
  assert.equal(blocked.status, 409);
  assert.equal((await blocked.json()).gate.passed, false);
  assert.equal(sql.prepare("SELECT COUNT(*) AS n FROM friendbook_cf_documents WHERE collection='users'").get().n, 0);

  const posts = await worker.fetch(adminRequest('/v1/admin/migrate/documents/rebuild',
    { method: 'POST', body: { collection: 'posts', apply: true } }), env(db));
  assert.equal(posts.status, 200);
  assert.equal((await posts.json()).written, 1);

  const afterGate = await worker.fetch(adminRequest('/v1/admin/migrate/documents/rebuild',
    { method: 'POST', body: { collection: 'users', apply: true } }), env(db));
  assert.equal(afterGate.status, 200);
  assert.equal((await afterGate.json()).written, 1);

  const verify = await worker.fetch(adminRequest('/v1/admin/migrate/documents/verify?collection=posts'), env(db));
  assert.equal(verify.status, 200);
  assert.equal((await verify.json()).mismatchCount, 0);
});

test('rebuilt posts are served by /v1/docs, the endpoint the app actually reads', async () => {
  const { sql, db } = createSqliteD1();
  const worker = createWorker(async () => 'uid_author');
  insertPost(sql, 'p1', { content: 'hello from D1 rows' });

  await worker.fetch(adminRequest('/v1/admin/migrate/documents/rebuild',
    { method: 'POST', body: { collection: 'posts', apply: true } }), env(db));

  const list = await worker.fetch(new Request('https://test.invalid/v1/docs?collection=posts&limit=5',
    { headers: { Authorization: 'Bearer firebase-token' } }), env(db));
  assert.equal(list.status, 200);
  const data = await list.json();
  assert.equal(data.documents.length, 1);
  assert.equal(data.documents[0].content, 'hello from D1 rows');
  assert.deepEqual(data.documents[0].mediaUrls, ['https://cdn.example/a.jpg']);
  assert.equal(data.documents[0].archived, false);

  const read = await worker.fetch(new Request('https://test.invalid/v1/docs/posts/p1',
    { headers: { Authorization: 'Bearer firebase-token' } }), env(db));
  assert.equal(read.status, 200);
  assert.equal((await read.json()).document.authorName, 'Author');
});

test('the gate is exact: one missing posts document keeps every other collection closed', async () => {
  const { sql, db } = createSqliteD1();
  const worker = createWorker(async () => 'uid_any');
  insertPost(sql, 'p1');
  insertPost(sql, 'p2');

  // Only the first page is applied, so p2 has no document yet.
  const partial = await worker.fetch(adminRequest('/v1/admin/migrate/documents/rebuild',
    { method: 'POST', body: { collection: 'posts', apply: true, limit: 1 } }), env(db));
  assert.equal(partial.status, 200);
  assert.equal((await partial.json()).written, 1);

  const planPartial = await (await worker.fetch(adminRequest('/v1/admin/migrate/documents/plan'), env(db))).json();
  assert.equal(planPartial.gate.passed, false);
  assert.equal(planPartial.gate.sourceRowsWithoutDocument, 1);
  assert.equal(planPartial.collections.find(item => item.collection === 'users').ready, false);

  await worker.fetch(adminRequest('/v1/admin/migrate/documents/rebuild',
    { method: 'POST', body: { collection: 'posts', apply: true, limit: 10 } }), env(db));
  const planFull = await (await worker.fetch(adminRequest('/v1/admin/migrate/documents/plan'), env(db))).json();
  assert.equal(planFull.gate.passed, true);
  assert.equal(planFull.gate.sourceRowsWithoutDocument, 0);
  assert.equal(planFull.collections.find(item => item.collection === 'users').ready, true);
});

test('the plan reports row counts, pending work and the gate for every collection', async () => {
  const { sql, db } = createSqliteD1();
  const worker = createWorker(async () => 'uid_any');
  insertPost(sql, 'p1');
  insertPost(sql, 'p2');
  sql.prepare("INSERT INTO users (uid, display_name) VALUES ('uid_1','A')").run();

  const response = await worker.fetch(adminRequest('/v1/admin/migrate/documents/plan'), env(db));
  assert.equal(response.status, 200);
  const body = await response.json();
  assert.equal(body.collections.length, 10);
  assert.equal(body.totals.sourceRows, 3);
  assert.equal(body.totals.pending, 3);
  assert.equal(body.collections.find(item => item.collection === 'users').sourceRows, 1);
  assert.equal(body.collections.find(item => item.collection === 'users').ready, false);
  assert.equal(body.gate.collection, 'posts');
  assert.equal(body.gate.passed, false);
});

test('an unknown collection is rejected without touching the database', async () => {
  const { db } = createSqliteD1();
  const worker = createWorker(async () => 'uid_any');
  const bad = await worker.fetch(adminRequest('/v1/admin/migrate/documents/rebuild',
    { method: 'POST', body: { collection: 'admins', apply: true } }), env(db));
  assert.equal(bad.status, 400);
  const verifyBad = await worker.fetch(adminRequest('/v1/admin/migrate/documents/verify?collection=admins'), env(db));
  assert.equal(verifyBad.status, 400);
});
