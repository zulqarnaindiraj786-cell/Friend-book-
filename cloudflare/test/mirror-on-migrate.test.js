import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import { createWorker } from '../../worker.js';

// The migration used to write only the normalised tables (users, posts, chats, ...) while the
// app reads everything back through /v1/docs, which is served from friendbook_cf_documents.
// Nothing connected the two, so a migration could report "1234 migrated, 0 errors" and leave
// the app completely empty. This test asserts the actual contract: after a migration, the app
// can read the migrated document back through the endpoint it really uses.

const MIGRATION_0011 = readFileSync(new URL('../migrations/0011_additive_app_documents.sql', import.meta.url), 'utf8');
const MIGRATION_0014 = readFileSync(new URL('../migrations/0014_additive_core_schema.sql', import.meta.url), 'utf8');

const TOKEN = 'x'.repeat(48);

function createSqliteD1() {
  const sql = new DatabaseSync(':memory:');
  sql.exec(MIGRATION_0014);
  sql.exec(MIGRATION_0011);
  const db = {
    prepare(query) {
      let stmt;
      try {
        stmt = sql.prepare(query);
      } catch (error) {
        // Surface the real SQL problem; node:sqlite's prepare is lazy about some errors.
        throw error;
      }
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
    async batch(statements) {
      return Promise.all(statements.map(s => s.run()));
    },
  };
  return { sql, db };
}

function env(db) {
  return {
    DB: db,
    MIGRATION_ADMIN_TOKEN: TOKEN,
    FIREBASE_PROJECT_ID: 'friendbook-test',
    DATA_API_ENABLED: 'true',
  };
}

async function migrate(worker, collection, documents) {
  return worker.fetch(new Request(`https://test.invalid/v1/admin/migrate/${collection}`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${TOKEN}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ documents }),
  }), env.current);
}

test('a migrated document is readable through /v1/docs, which is what the app uses', async () => {
  const { db } = createSqliteD1();
  const worker = createWorker(async () => 'uid_reader');
  env.current = env(db);

  const response = await migrate(worker, 'posts', [{
    __id: 'post_migrated_1',
    authorId: 'uid_reader',
    authorName: 'Ayesha',
    content: 'migrated from Firestore',
    createdAt: 1700000000000,
  }]);
  assert.equal(response.status, 200);
  const body = await response.json();
  assert.equal(body.errors, 0, 'the normalised insert must not fail: ' + JSON.stringify(body));
  assert.equal(body.inserted + (body.updated || 0) >= 1, true, 'the normalised row must still be written');
  assert.equal(body.mirrored, 1, 'the document must also be mirrored into the app document store');

  // Read it back exactly the way the app does.
  const read = await worker.fetch(new Request('https://test.invalid/v1/docs/posts/post_migrated_1', {
    headers: { Authorization: 'Bearer token' },
  }), env.current);
  assert.equal(read.status, 200);
  const payload = await read.json();
  assert.equal(payload.id, 'post_migrated_1');
  assert.equal(payload.document.content, 'migrated from Firestore');
});

test('the mirrored document appears in the collection listing the feed calls', async () => {
  const { db } = createSqliteD1();
  const worker = createWorker(async () => 'uid_reader');
  env.current = env(db);

  await migrate(worker, 'posts', [
    { __id: 'p1', authorId: 'uid_reader', content: 'one', createdAt: 1 },
    { __id: 'p2', authorId: 'uid_reader', content: 'two', createdAt: 2 },
  ]);

  const list = await worker.fetch(new Request('https://test.invalid/v1/docs?collection=posts&limit=20', {
    headers: { Authorization: 'Bearer token' },
  }), env.current);
  assert.equal(list.status, 200);
  const { documents } = await list.json();
  const ids = documents.map(d => d.id).sort();
  assert.deepEqual(ids, ['p1', 'p2']);
});

test('a mirror failure is reported without failing the batch', async () => {
  const { db } = createSqliteD1();
  const worker = createWorker(async () => 'uid_reader');
  const broken = {
    ...db,
    // The normalised insert succeeds; every batch write explodes.
    async batch() { throw new Error('documents table unavailable'); },
  };
  env.current = env(broken);

  const response = await worker.fetch(new Request('https://test.invalid/v1/admin/migrate/posts', {
    method: 'POST',
    headers: { Authorization: `Bearer ${TOKEN}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ documents: [{ __id: 'p9', authorId: 'uid_reader', content: 'x' }] }),
  }), env.current);

  assert.equal(response.status, 200, 'the primary write succeeded, so the batch is not a failure');
  const body = await response.json();
  assert.equal(body.mirrorErrors, 1, 'the mirror problem must be visible, not silent');
  assert.equal(body.mirrored, 0);
});

// By the time a migration runs, D1 is not an empty target — the app has been writing to it for
// weeks. The copy source is therefore older than the table, so an unconditional upsert would
// replace live user edits with week-old Firestore versions. This asserts the merge guard.
test('a migration never overwrites a newer document with an older copy', async () => {
  const { db } = createSqliteD1();
  const worker = createWorker(async () => 'uid_guard');
  env.current = env(db);

  const live = {
    __id: 'post_live',
    authorId: 'uid_guard',
    content: 'edited in the app five minutes ago',
    createdAt: 1700000000000,
    updatedAt: 1800000000000,
  };
  await migrate(worker, 'posts', [live]);

  // The same document, as Firestore still has it: an older updatedAt.
  const stale = {
    __id: 'post_live',
    authorId: 'uid_guard',
    content: 'the version Firestore kept from last week',
    createdAt: 1700000000000,
    updatedAt: 1700000100000,
  };
  await migrate(worker, 'posts', [stale]);

  const read = await worker.fetch(
    new Request('https://test.invalid/v1/docs/posts/post_live', {
      headers: { Authorization: 'Bearer anything' },
    }),
    env.current
  );
  assert.equal(read.status, 200);
  const body = await read.json();
  assert.equal(
    body.document.content,
    'edited in the app five minutes ago',
    'the older copy must not win'
  );
});

// The guard must not be so strict that it blocks a genuine update: a newer copy still lands.
test('a newer copy still updates the document', async () => {
  const { db } = createSqliteD1();
  const worker = createWorker(async () => 'uid_newer');
  env.current = env(db);

  await migrate(worker, 'posts', [{
    __id: 'post_moves_on', authorId: 'uid_newer', content: 'first', createdAt: 1700000000000,
    updatedAt: 1700000000000,
  }]);
  await migrate(worker, 'posts', [{
    __id: 'post_moves_on', authorId: 'uid_newer', content: 'second', createdAt: 1700000000000,
    updatedAt: 1900000000000,
  }]);

  const read = await worker.fetch(
    new Request('https://test.invalid/v1/docs/posts/post_moves_on', {
      headers: { Authorization: 'Bearer anything' },
    }),
    env.current
  );
  assert.equal((await read.json()).document.content, 'second');
});
