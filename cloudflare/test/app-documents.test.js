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

function createSqliteD1() {
  const sql = new DatabaseSync(':memory:');
  for (const m of [MIGRATION_0001, MIGRATION_0002, MIGRATION_0003, MIGRATION_0011]) {
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

test('/v1/docs filters, paginates and deletes through the real worker', async () => {
  const { db } = createSqliteD1();
  const w = createWorker(async () => 'uid_owner');
  const env = { DB: db, FIREBASE_PROJECT_ID: 'friendbook-test' };
  const put = (coll, id, body, token = 'uid_owner') => w.fetch(new Request(`https://example.com/v1/docs/${coll}/${id}`, {
    method: 'PUT',
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  }), env);

  for (let i = 0; i < 12; i++) {
    assert.equal((await put('posts', `p${String(i).padStart(2, '0')}`, {
      authorId: i % 2 === 0 ? 'uid_owner' : 'uid_other',
      createdAt: 1000 + i,
      content: `post ${i}`,
    })).status, 200);
  }

  // WHERE narrows the collection to one author's posts.
  const filtered = await (await w.fetch(new Request(
    'https://example.com/v1/docs?collection=posts&where=authorId:eq:uid_owner&orderBy=createdAt:asc&limit=50',
    { headers: { Authorization: 'Bearer uid_owner' } }), env)).json();
  assert.ok(filtered.documents.every(d => d.authorId === 'uid_owner'));
  assert.equal(filtered.documents.length, 6);

  // Pagination walks the whole filtered set without gaps or repeats.
  const seen = [];
  let after = null;
  for (let page = 0; page < 10; page++) {
    const qs = 'https://example.com/v1/docs?collection=posts&orderBy=createdAt:asc&limit=5'
      + (after ? `&after=${encodeURIComponent(after)}` : '');
    const body = await (await w.fetch(new Request(qs, { headers: { Authorization: 'Bearer uid_owner' } }), env)).json();
    seen.push(...body.documents.map(d => d.id));
    if (!body.hasMore) { assert.equal(body.nextCursor, null); break; }
    after = body.nextCursor;
  }
  assert.equal(seen.length, 12);
  assert.equal(new Set(seen).size, 12, 'no duplicates across pages');

  // Owner deletes; the document is gone and the delete is idempotent.
  assert.equal((await w.fetch(new Request('https://example.com/v1/docs/posts/p00', {
    method: 'DELETE', headers: { Authorization: 'Bearer uid_owner' } }), env)).status, 200);
  assert.equal((await w.fetch(new Request('https://example.com/v1/docs/posts/p00', {
    headers: { Authorization: 'Bearer uid_owner' } }), env)).status, 404);
  const repeat = await (await w.fetch(new Request('https://example.com/v1/docs/posts/p00', {
    method: 'DELETE', headers: { Authorization: 'Bearer uid_owner' } }), env)).json();
  assert.equal(repeat.deleted, false);

  // Someone else's post cannot be deleted.
  assert.equal((await w.fetch(new Request('https://example.com/v1/docs/posts/p01', {
    method: 'DELETE', headers: { Authorization: 'Bearer uid_owner' } }), env)).status, 403);
  assert.equal((await w.fetch(new Request('https://example.com/v1/docs/posts/p01', {
    headers: { Authorization: 'Bearer uid_owner' } }), env)).status, 200);

  // Bad query parameters are rejected, not silently ignored.
  assert.equal((await w.fetch(new Request('https://example.com/v1/docs?collection=posts&where=bad',
    { headers: { Authorization: 'Bearer uid_owner' } }), env)).status, 400);
  assert.equal((await w.fetch(new Request('https://example.com/v1/docs?collection=nope',
    { headers: { Authorization: 'Bearer uid_owner' } }), env)).status, 400);
});
