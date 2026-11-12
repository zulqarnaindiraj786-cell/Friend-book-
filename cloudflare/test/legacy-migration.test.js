import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import { createWorker } from '../../worker.js';
import { legacyMigration, MIGRATION_LIMITS } from '../legacy-migration.js';

const MIGRATION_0001 = readFileSync(new URL('../migrations/0001_additive_profiles.sql', import.meta.url), 'utf8');
const MIGRATION_0011 = readFileSync(new URL('../migrations/0011_additive_app_documents.sql', import.meta.url), 'utf8');

function createSqliteD1() {
  const sql = new DatabaseSync(':memory:');
  for (const m of [MIGRATION_0001, MIGRATION_0011]) sql.exec(m);
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

const UID = 'uid_mig';
const CREATED = '2024-01-01T00:00:00.000Z';
const UPDATED = '2024-02-01T00:00:00.000Z';

/** One legacy document in the exported shape the REST API answers with. */
function legacyDoc(collection, id, fields, extra = {}) {
  return {
    document: {
      name: `projects/friendbook-test/databases/(default)/documents/${collection}/${id}`,
      fields,
      createTime: CREATED,
      updateTime: UPDATED,
    },
    ...extra,
  };
}

const text = (value) => ({ stringValue: value });
const int = (value) => ({ integerValue: String(value) });
const array = (...values) => ({ arrayValue: { values: values.map(text) } });

/**
 * A stand-in for the legacy database. It answers by the part of the URL after `/documents/`, records
 * every call it received, and refuses anything it was not told about with an empty page - the way an
 * empty collection answers.
 */
function legacyStore(answers, calls = []) {
  const fetcher = async (url, init = {}) => {
    const method = init.method || 'GET';
    calls.push({ url: String(url), method, body: init.body ? JSON.parse(init.body) : null });
    const key = String(url).split('/documents/')[1] || String(url);
    const answer = answers[key];
    if (answer && answer.status && answer.status >= 400) {
      return { ok: false, status: answer.status, text: async () => answer.body || '' };
    }
    const payload = answer === undefined ? [] : answer;
    return { ok: true, status: 200, json: async () => payload, text: async () => JSON.stringify(payload) };
  };
  return { fetcher, calls };
}

/** The documents one test user owns: a profile, two posts, a chat and its last two messages. */
function ownData() {
  return {
    [`users/${UID}`]: {
      name: `projects/friendbook-test/databases/(default)/documents/users/${UID}`,
      fields: { displayName: text('Migration User'), email: text('mig@example.com'), banned: { booleanValue: false } },
      createTime: CREATED,
      updateTime: UPDATED,
    },
    'posts:runQuery': [
      legacyDoc('posts', 'p1', { authorId: text(UID), content: text('first post'), likes: int(7), privacy: text('Public') }),
      legacyDoc('posts', 'p2', { authorId: text(UID), content: text('second post'), likes: int(0), privacy: text('Public') }),
    ],
    'chats:runQuery': [
      legacyDoc('chats', 'c1', { participants: array(UID, 'uid_peer'), lastMessage: text('hello') }),
    ],
    'chats/c1/messages:runQuery': [
      legacyDoc('messages', 'm1', { senderId: text(UID), body: text('hello') }),
      legacyDoc('messages', 'm2', { senderId: 'uid_peer' && text('uid_peer'), body: text('hi back') }),
    ],
  };
}

async function row(db, path) {
  const found = await db.prepare('SELECT * FROM friendbook_cf_documents WHERE path = ?').bind(path).first();
  if (!found) return null;
  return { ...found, doc: JSON.parse(found.document_json) };
}

async function countAll(db) {
  const found = await db.prepare('SELECT COUNT(*) AS total FROM friendbook_cf_documents').first();
  return Number(found?.total || 0);
}

function request(method = 'POST') {
  return new Request('https://example.com/v1/migrate/legacy', {
    method,
    headers: { Authorization: 'Bearer legacy-token' },
  });
}

test('the update carries a user\'s own legacy documents into D1, additively, without writing to the legacy store', async () => {
  const { db } = createSqliteD1();
  const { fetcher, calls } = legacyStore(ownData());
  const env = { DB: db, FIREBASE_PROJECT_ID: 'friendbook-test' };

  const answer = await legacyMigration(request(), env, { uid: UID, token: 'legacy-token' }, fetcher, () => 1750000000000);
  assert.equal(answer.status, 200);
  const body = await answer.json();

  assert.equal(body.ok, true, JSON.stringify(body.errors));
  assert.equal(body.additive, true);
  assert.equal(body.legacyWrites, 0);
  assert.equal(body.migrated.users, 1);
  assert.equal(body.migrated.posts, 2);
  assert.equal(body.migrated['chats/c1/messages'], 2);

  // The profile arrives as plain JSON, not as the exported wrapper, and keeps its own history.
  const profile = await row(db, `users/${UID}`);
  assert.equal(profile.collection, 'users');
  assert.equal(profile.doc_id, UID);
  assert.equal(profile.owner_uid, UID);
  assert.equal(profile.doc.displayName, 'Migration User');
  assert.equal(profile.doc.banned, false);
  assert.equal(profile.doc.createdAt, Date.parse(CREATED));
  assert.equal(profile.doc.updatedAt, Date.parse(UPDATED));

  // A post keeps the counters it already had - a carried-over like count must not come back as zero.
  const post = await row(db, 'posts/p1');
  assert.equal(post.owner_uid, UID, 'the author owns the row the access rules read');
  assert.equal(post.doc.likes, 7);
  assert.equal(post.doc.content, 'first post');
  assert.equal(post.doc.id, 'p1');

  // Messages live under their chat, exactly as the app reads a thread, and each one carries its chatId.
  const message = await row(db, 'chats/c1/messages/m1');
  assert.equal(message.collection, 'chats/c1/messages');
  assert.equal(message.doc_id, 'm1');
  assert.equal(message.doc.chatId, 'c1');
  assert.equal(message.doc.body, 'hello');
  const peerMessage = await row(db, 'chats/c1/messages/m2');
  assert.equal(peerMessage.owner_uid, 'uid_peer', 'somebody else\'s message keeps its own author');

  // Reads only. The legacy store is never sent a write, an update or a delete - a user who never
  // updates must find their data exactly where they left it.
  assert.ok(calls.length > 0);
  for (const call of calls) {
    const write = ['PUT', 'PATCH', 'DELETE'].includes(call.method);
    assert.equal(write, false, `the migration must not write to the legacy store: ${call.method} ${call.url}`);
    if (call.method === 'POST') assert.ok(call.url.endsWith(':runQuery'), call.url);
  }
  // And every query it ran was about this user alone.
  for (const call of calls.filter((c) => c.body)) {
    const filter = call.body.structuredQuery?.where?.fieldFilter;
    if (filter) assert.equal(filter.value.stringValue, UID);
  }
});

test('a document the user has already written to Cloudflare is left exactly as it is', async () => {
  const { db } = createSqliteD1();
  await db.prepare(
    `INSERT INTO friendbook_cf_documents(path, collection, doc_id, owner_uid, document_json, updated_at)
     VALUES ('posts/p1', 'posts', 'p1', ?, ?, ?)`,
  ).bind(UID, JSON.stringify({ authorId: UID, content: 'edited after the update', likes: 12 }), 1750000000000).run();

  const { fetcher } = legacyStore(ownData());
  const env = { DB: db, FIREBASE_PROJECT_ID: 'friendbook-test' };
  const body = await (await legacyMigration(request(), env, { uid: UID, token: 't' }, fetcher, () => 1750000000000)).json();

  assert.equal(body.present.posts, 1);
  assert.equal(body.migrated.posts, 1, 'only the post that was not there yet is carried over');

  const kept = await row(db, 'posts/p1');
  assert.equal(kept.doc.content, 'edited after the update', 'older legacy data must never overwrite newer D1 data');
  assert.equal(kept.doc.likes, 12);
  assert.equal(kept.updated_at, 1750000000000);
});

test('a tombstoned document stays deleted: the migration does not resurrect what the user removed', async () => {
  const { db } = createSqliteD1();
  await db.prepare(
    `INSERT INTO friendbook_cf_documents(path, collection, doc_id, owner_uid, document_json, updated_at)
     VALUES ('posts/p2', 'posts', 'p2', ?, ?, ?)`,
  ).bind(UID, JSON.stringify({ authorId: UID, deleted: true, kept: true }), 1750000000000).run();

  const { fetcher } = legacyStore(ownData());
  const env = { DB: db, FIREBASE_PROJECT_ID: 'friendbook-test' };
  await legacyMigration(request(), env, { uid: UID, token: 't' }, fetcher, () => 1750000000000);

  const kept = await row(db, 'posts/p2');
  assert.equal(kept.doc.deleted, true);
  assert.equal(kept.doc.content, undefined, 'the deleted post must not come back to life');
});

test('the run is bounded: pages per slice, chats, messages and a total document budget', async () => {
  const { db } = createSqliteD1();
  // A legacy store that always says "there is more" must not turn one request into a crawl.
  const answers = ownData();
  answers['posts:runQuery'] = [legacyDoc('posts', 'pX', { authorId: text(UID) }), { pageToken: 'more' }];
  const { fetcher, calls } = legacyStore(answers);
  const env = { DB: db, FIREBASE_PROJECT_ID: 'friendbook-test' };

  const body = await (await legacyMigration(request(), env, { uid: UID, token: 't' }, fetcher, () => 1750000000000)).json();

  const postPages = calls.filter((c) => c.url.endsWith('posts:runQuery')).length;
  assert.equal(postPages, MIGRATION_LIMITS.maxPages, 'a slice stops at maxPages even when more pages exist');
  assert.equal(body.scanned.posts, MIGRATION_LIMITS.maxPages);
  assert.ok(body.budgetLeft >= 0);
  assert.ok(body.budgetLeft <= MIGRATION_LIMITS.maxDocuments);
  // Every page asked for at most one page size of rows.
  for (const call of calls.filter((c) => c.body)) {
    assert.ok(call.body.pageSize <= MIGRATION_LIMITS.pageSize);
    assert.ok(call.body.structuredQuery.limit <= MIGRATION_LIMITS.maxDocuments);
  }
  assert.ok(await countAll(db) <= MIGRATION_LIMITS.maxDocuments);
});

test('a slice the legacy store refuses is reported, and the rest of the migration still happens', async () => {
  const { db } = createSqliteD1();
  const answers = ownData();
  answers['posts:runQuery'] = { status: 400, body: 'FAILED_PRECONDITION: missing index' };
  const { fetcher } = legacyStore(answers);
  const env = { DB: db, FIREBASE_PROJECT_ID: 'friendbook-test' };

  const answer = await legacyMigration(request(), env, { uid: UID, token: 't' }, fetcher, () => 1750000000000);
  const body = await answer.json();

  assert.equal(body.ok, false, 'a half-finished migration must not claim it finished');
  assert.equal(body.errors.length, 1);
  assert.equal(body.errors[0].slice, 'posts');
  assert.match(body.errors[0].error, /missing index/);
  assert.equal(body.migrated.users, 1, 'the slices that worked are still carried over');
  assert.equal(body.migrated.posts, undefined);
  assert.ok(await row(db, `users/${UID}`));
});

test('it refuses anything that is not an authenticated POST with a database to write into', async () => {
  const { db } = createSqliteD1();
  const { fetcher } = legacyStore(ownData());

  const wrongMethod = await legacyMigration(request('GET'), { DB: db, FIREBASE_PROJECT_ID: 'p' }, { uid: UID, token: 't' }, fetcher);
  assert.equal(wrongMethod.status, 405);

  const noDatabase = await legacyMigration(request(), { FIREBASE_PROJECT_ID: 'p' }, { uid: UID, token: 't' }, fetcher);
  assert.equal(noDatabase.status, 503);

  const noProject = await legacyMigration(request(), { DB: db }, { uid: UID, token: 't' }, fetcher);
  assert.equal(noProject.status, 503);

  const noToken = await legacyMigration(request(), { DB: db, FIREBASE_PROJECT_ID: 'p' }, { uid: UID }, fetcher);
  assert.equal(noToken.status, 401);

  const switchedOff = await legacyMigration(
    request(),
    { DB: db, FIREBASE_PROJECT_ID: 'p', LEGACY_MIGRATION_ENABLED: 'false' },
    { uid: UID, token: 't' },
    fetcher,
  );
  assert.equal(switchedOff.status, 403);
  assert.equal(await countAll(db), 0, 'a refused run writes nothing');
});

test('the Worker routes /v1/migrate/legacy behind the identity check and nowhere else', async () => {
  const { db } = createSqliteD1();
  const { fetcher } = legacyStore(ownData());
  const w = createWorker(async () => ({ uid: UID, email: 'mig@example.com' }));
  const env = { DB: db, FIREBASE_PROJECT_ID: 'friendbook-test', LEGACY_FETCH: fetcher };

  const answer = await w.fetch(request(), env);
  assert.equal(answer.status, 200);
  const body = await answer.json();
  assert.equal(body.uid, UID);
  assert.equal(body.ok, true, JSON.stringify(body.errors));
  assert.ok(await row(db, 'posts/p1'));

  // The same path without a token never reaches the migration.
  const anonymous = await w.fetch(new Request('https://example.com/v1/migrate/legacy', { method: 'POST' }), env);
  assert.equal(anonymous.status, 401);

  // A neighbouring path is not a migration endpoint.
  const unknown = await w.fetch(new Request('https://example.com/v1/migrate/other', { method: 'POST' }), env);
  assert.equal(unknown.status, 404);
});
