import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import { interactions } from '../interactions.js';
import { promotePage, promoteResource, promoteStatus } from '../interaction-verify.js';

// The engagement API refuses every resource that is not marked 'verified', and only the old,
// never-completed migration ever wrote that mark. On production that made every like sheet and
// comment list answer 503, which the app rendered as an empty "People who reacted" with
// "Retry reactions" - while the post header still said "1 reaction".
//
// These tests hold the repair to two rules: a resource is only servable when D1 holds its
// document, and a servable resource must return the reactors it really has, by name.

const SCHEMA = [
  '../migrations/0003_additive_interactions.sql',
  '../migrations/0004_additive_comment_details.sql',
  '../migrations/0005_additive_reaction_history.sql',
  '../migrations/0006_additive_comment_history.sql',
  '../migrations/0008_additive_legacy_comment_totals.sql',
  '../migrations/0011_additive_app_documents.sql',
  '../migrations/0014_additive_core_schema.sql',
].map((path) => readFileSync(new URL(path, import.meta.url), 'utf8')).join('\n');

function db() {
  const sql = new DatabaseSync(':memory:');
  sql.exec(SCHEMA);
  const wrap = (stmt) => ({
    bind: (...args) => ({
      run: async () => stmt.run(...args),
      all: async () => ({ results: stmt.all(...args) }),
      first: async () => stmt.get(...args) || null,
    }),
    run: async () => stmt.run(),
    all: async () => ({ results: stmt.all() }),
    first: async () => stmt.get() || null,
  });
  return {
    sql,
    prepare: (query) => wrap(sql.prepare(query)),
    async batch(statements) {
      return Promise.all(statements.map((s) => s.run()));
    },
    withSession() { return this; },
  };
}

function putDocument(d, collection, id, doc) {
  d.sql.prepare(
    `INSERT INTO friendbook_cf_documents(path, collection, doc_id, owner_uid, document_json, updated_at)
     VALUES (?, ?, ?, ?, ?, ?)`
  ).run(`${collection}/${id}`, collection, id, 'uid_a', JSON.stringify(doc), Date.now());
}

const engagement = (d, path) => {
  const request = new Request(`https://test.invalid${path}`);
  const match = /^\/v1\/engagement\/(posts|reels)\/([^/]+)\/(reactions|comments|reactors)$/
    .exec(new URL(request.url).pathname);
  return interactions(
    request,
    { DB: d, D1_INTERACTIONS_ENABLED: 'true' },
    { uid: 'uid_viewer' },
    match,
    async () => true
  );
};

// The real shape: the map is uid -> type, not type -> count. Reading it as type -> count was a
// silent zero, which is how a post with likers could be promoted with nothing to show.
test('uid-keyed reactionsMap becomes named reactors', async () => {
  const d = db();
  putDocument(d, 'posts', 'p1', {
    id: 'p1', authorId: 'uid_a', createdAt: 1,
    reactionsMap: {
      uid_x: { type: 'like', userName: 'X' },
      uid_y: { type: 'love' },
    },
  });
  putDocument(d, 'posts', 'p2', { id: 'p2', authorId: 'uid_a', createdAt: 1, reactionsMap: {} });

  const result = await promotePage(d, 'posts');
  assert.equal(result.promoted, 2);
  assert.equal(result.reactions, 2);

  const rows = d.sql.prepare(
    "SELECT uid, type FROM friendbook_cf_reactions WHERE resource = 'posts/p1' ORDER BY uid"
  ).all();
  assert.deepEqual(rows.map((r) => [r.uid, r.type]), [['uid_x', 'like'], ['uid_y', 'love']]);

  const totals = d.sql.prepare(
    "SELECT type, total FROM friendbook_cf_reaction_totals WHERE resource = 'posts/p1' ORDER BY type"
  ).all();
  assert.deepEqual(totals.map((r) => [r.type, r.total]), [['like', 1], ['love', 1]]);
});

test('likedBy fills gaps but does not overwrite an explicit reaction', async () => {
  const d = db();
  putDocument(d, 'posts', 'p3', {
    id: 'p3', authorId: 'uid_a', createdAt: 1,
    reactionsMap: { uid_x: { type: 'haha' } },
    likedBy: ['uid_x', 'uid_y'],
  });
  await promotePage(d, 'posts');

  const rows = d.sql.prepare(
    "SELECT uid, type FROM friendbook_cf_reactions WHERE resource = 'posts/p3' ORDER BY uid"
  ).all();
  assert.deepEqual(rows.map((r) => [r.uid, r.type]), [['uid_x', 'haha'], ['uid_y', 'like']]);
});

test('reels read the reactions field first and default to love', async () => {
  const d = db();
  putDocument(d, 'reels', 'r1', {
    id: 'r1', authorId: 'uid_a', createdAt: 1,
    reactions: { uid_x: 'wow' },
    likedBy: ['uid_y'],
  });
  await promotePage(d, 'reels');

  const rows = d.sql.prepare(
    "SELECT uid, type FROM friendbook_cf_reactions WHERE resource = 'reels/r1' ORDER BY uid"
  ).all();
  assert.deepEqual(rows.map((r) => [r.uid, r.type]), [['uid_x', 'wow'], ['uid_y', 'love']]);
});

test('an unrecognised reaction type is skipped, never guessed', async () => {
  const d = db();
  putDocument(d, 'posts', 'p4', {
    id: 'p4', authorId: 'uid_a', createdAt: 1,
    reactionsMap: { uid_x: { type: 'clap' }, uid_y: { type: 'like' } },
  });
  await promotePage(d, 'posts');

  const rows = d.sql.prepare(
    "SELECT uid FROM friendbook_cf_reactions WHERE resource = 'posts/p4'"
  ).all();
  assert.deepEqual(rows.map((r) => r.uid), ['uid_y']);
});

test('a document that is not in D1 is never promoted', async () => {
  const d = db();
  assert.equal(await promoteResource(d, 'posts', 'missing'), false);
  assert.equal(
    d.sql.prepare('SELECT COUNT(*) AS n FROM friendbook_cf_interaction_resources').get().n, 0
  );
});

test('promotion is idempotent and does not double the counts', async () => {
  const d = db();
  putDocument(d, 'posts', 'p5', {
    id: 'p5', authorId: 'uid_a', createdAt: 1, reactionsMap: { uid_x: { type: 'like' } },
  });

  await promotePage(d, 'posts');
  const second = await promotePage(d, 'posts');

  assert.equal(second.promoted, 0);
  const total = d.sql.prepare(
    "SELECT total FROM friendbook_cf_reaction_totals WHERE resource = 'posts/p5'"
  ).get();
  assert.equal(total.total, 1);
});

// The end the user sees. Before the fix this was 503 for every post on production.
test('the reaction sheet is served, not refused, for a copied post', async () => {
  const d = db();
  putDocument(d, 'posts', 'p6', {
    id: 'p6', authorId: 'uid_a', createdAt: 1,
    reactionsMap: { uid_x: { type: 'like' } },
  });

  // No prior promotion: the request itself must repair the resource.
  const response = await engagement(d, '/v1/engagement/posts/p6/reactors');
  assert.equal(response.status, 200);

  const body = await response.json();
  assert.deepEqual(body.reactors.map((r) => [r.uid, r.type]), [['uid_x', 'like']]);
});

test('the counters endpoint reports the reaction it has', async () => {
  const d = db();
  putDocument(d, 'posts', 'p7', {
    id: 'p7', authorId: 'uid_a', createdAt: 1,
    reactionsMap: { uid_x: { type: 'like' }, uid_y: { type: 'like' } },
  });

  const response = await engagement(d, '/v1/engagement/posts/p7/reactions');
  assert.equal(response.status, 200);

  const body = await response.json();
  const like = body.types.find((t) => t.type === 'like');
  assert.equal(like.count, 2, 'two likers must read as two, not zero');
});

test('status reports what is still pending', async () => {
  const d = db();
  putDocument(d, 'posts', 'p8', { id: 'p8', authorId: 'uid_a', createdAt: 1 });
  putDocument(d, 'posts', 'p9', { id: 'p9', authorId: 'uid_a', createdAt: 1 });

  assert.deepEqual((await promoteStatus(d)).posts, { pending: 2, verified: 0 });
  await promotePage(d, 'posts');
  assert.deepEqual((await promoteStatus(d)).posts, { pending: 0, verified: 2 });
});

// A post created by a new account: the document exists in D1 but no operator will ever mark it
// verified, because nobody runs the migration for it. It has no reactions yet, so an empty list
// is the correct answer - but it must be answered, not refused.
test('a newly created post with no reactions is served empty, not refused', async () => {
  const d = db();
  putDocument(d, 'posts', 'fresh', {
    id: 'fresh', authorId: 'uid_new', content: 'my first post', createdAt: Date.now(),
  });

  const response = await engagement(d, '/v1/engagement/posts/fresh/reactors');
  assert.equal(response.status, 200, 'a post D1 holds must never 503');
  const body = await response.json();
  assert.deepEqual(body.reactors, []);
  assert.equal(body.next, null);
});

test('the first reaction on a fresh post appears immediately', async () => {
  const d = db();
  putDocument(d, 'posts', 'fresh2', { id: 'fresh2', authorId: 'uid_new', createdAt: Date.now() });

  // The new post is served once, which promotes it.
  await engagement(d, '/v1/engagement/posts/fresh2/reactors');

  // Someone reacts through the same endpoint.
  const request = new Request('https://test.invalid/v1/engagement/posts/fresh2/reactions', {
    method: 'PUT',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ type: 'like' }),
  });
  const match = /^\/v1\/engagement\/(posts|reels)\/([^/]+)\/(reactions|comments|reactors)$/
    .exec(new URL(request.url).pathname);
  const put = await interactions(request, { DB: d, D1_INTERACTIONS_ENABLED: 'true' },
    { uid: 'uid_visitor' }, match, async () => true);
  assert.equal(put.status, 200);

  const sheet = await engagement(d, '/v1/engagement/posts/fresh2/reactors');
  const body = await sheet.json();
  assert.deepEqual(body.reactors.map((r) => [r.uid, r.type]), [['uid_visitor', 'like']]);
});
