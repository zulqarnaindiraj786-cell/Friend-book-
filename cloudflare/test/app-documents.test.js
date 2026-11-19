import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import { createWorker } from '../../worker.js';
import { authorizePost } from '../firebase-post-access.js';

const MIGRATION_0001 = readFileSync(new URL('../migrations/0001_additive_profiles.sql', import.meta.url), 'utf8');
const MIGRATION_0002 = readFileSync(new URL('../migrations/0002_additive_copy_staging.sql', import.meta.url), 'utf8');
const MIGRATION_0003 = readFileSync(new URL('../migrations/0003_additive_interactions.sql', import.meta.url), 'utf8');
const MIGRATION_0011 = readFileSync(new URL('../migrations/0011_additive_app_documents.sql', import.meta.url), 'utf8');
const MIGRATION_0014 = readFileSync(new URL('../migrations/0014_additive_firestore_documents.sql', import.meta.url), 'utf8');
const MIGRATION_0018 = readFileSync(new URL('../migrations/0018_social_guards.sql', import.meta.url), 'utf8');

function createSqliteD1() {
  const sql = new DatabaseSync(':memory:');
  for (const m of [MIGRATION_0001, MIGRATION_0002, MIGRATION_0003, MIGRATION_0011, MIGRATION_0014, MIGRATION_0018]) {
    sql.exec(m);
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
    async batch(stmts) {
      sql.exec('BEGIN');
      try { for (const s of stmts) await s.run(); sql.exec('COMMIT'); }
      catch (error) { sql.exec('ROLLBACK'); throw error; }
    },
  };
  return { sql, db };
}

test('/v1/docs preserves historical staging records and stores new Cloudflare documents with D1-first ACL', async () => {
  const { db } = createSqliteD1();
  await db
    .prepare(
      `INSERT INTO friendbook_cf_documents (path, collection, doc_id, owner_uid, document_json, updated_at)
       VALUES ('posts/old_post_1', 'posts', 'old_post_1', 'uid_old', ?, 1700000000000)`,
    )
    .bind(
      JSON.stringify({
        fields: {
          authorId: { stringValue: 'uid_old' },
          authorName: { stringValue: 'Old User' },
          content: { stringValue: 'Preserved historical post' },
          privacy: { stringValue: 'Public' },
          likes: { integerValue: '5' },
        },
      }),
    )
    .run();

  await db.prepare(
    `INSERT INTO friendbook_cf_documents (path, collection, doc_id, owner_uid, document_json, updated_at)
     VALUES ('users/uid_old', 'users', 'uid_old', 'uid_old', ?, 1690000000000)`,
  ).bind(JSON.stringify({ fields: { displayName: { stringValue: 'Old User' }, banned: { booleanValue: false } } })).run();
  for (const [id, privacy] of [['private_post', 'Friends'], ['only_me_post', 'Only me']]) {
    await db.prepare(
      `INSERT INTO friendbook_cf_documents (path, collection, doc_id, owner_uid, document_json, updated_at)
       VALUES (?, 'posts', ?, 'uid_old', ?, 1800000000000)`,
    ).bind(`posts/${id}`, id, JSON.stringify({ fields: {
      authorId: { stringValue: 'uid_old' }, content: { stringValue: id }, privacy: { stringValue: privacy },
    } })).run();
  }

  const w = createWorker(async () => 'uid_new');
  const env = { DB: db, FIREBASE_PROJECT_ID: 'friendbook-test' };

  // 1. Write user profile + new post from an updated APK user to Cloudflare D1
  const userPutRes = await w.fetch(
    new Request('https://example.com/v1/docs/users/uid_new', {
      method: 'PUT',
      headers: {
        Authorization: 'Bearer test-token',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        displayName: 'Updated User',
        photoUrl: '',
        banned: false,
      }),
    }),
    env,
  );
  assert.equal(userPutRes.status, 200);
  const mirroredProfile = await w.fetch(
    new Request('https://example.com/v1/firestore/document?path=users/uid_new', {
      headers: { Authorization: 'Bearer test-token' },
    }), env,
  );
  assert.equal(mirroredProfile.status, 200);
  assert.equal((await mirroredProfile.json()).data.displayName, 'Updated User');

  const putRes = await w.fetch(
    new Request('https://example.com/v1/docs/posts/new_cf_post_1', {
      method: 'PUT',
      headers: {
        Authorization: 'Bearer test-token',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        authorId: 'uid_new',
        authorName: 'Updated User',
        content: 'Brand new Cloudflare D1 post',
        privacy: 'Public',
        likes: 0,
      }),
    }),
    env,
  );
  assert.equal(putRes.status, 200);

  // 2. List posts from /v1/docs?collection=posts — returns both new Cloudflare post + preserved historical post
  const listRes = await w.fetch(
    new Request('https://example.com/v1/docs?collection=posts&limit=20', {
      method: 'GET',
      headers: { Authorization: 'Bearer test-token' },
    }),
    env,
  );
  assert.equal(listRes.status, 200);
  const listData = await listRes.json();
  assert.equal(listData.collection, 'posts');
  assert.equal(listData.documents.length, 2);
  assert.equal(listData.documents[0].id, 'new_cf_post_1');
  assert.equal(listData.documents[0].content, 'Brand new Cloudflare D1 post');
  assert.equal(listData.documents[1].id, 'old_post_1');
  assert.equal(listData.documents[1].content, 'Preserved historical post');
  assert.equal(listData.documents[1].likes, 5);

  // 3. Verify D1-first authorizePost succeeds without calling Firestore REST fetcher
  const ctx = {};
  const allowed = await authorizePost(
    {
      project: 'friendbook-test',
      token: 'test-token',
      uid: 'uid_new',
      db,
    },
    'posts',
    'new_cf_post_1',
    async () => {
      throw new Error('Firestore REST should not be called when post and user are in Cloudflare D1');
    },
    ctx,
  );
  assert.equal(allowed, true);
  assert.equal(ctx.owner, 'uid_new');
});
