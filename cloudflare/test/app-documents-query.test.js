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

async function insert(db, collection, id, body) {
  await db.prepare(
    `INSERT INTO friendbook_cf_documents (path, collection, doc_id, owner_uid, document_json, updated_at)
     VALUES (?, ?, ?, ?, ?, ?)`,
  ).bind(`${collection}/${id}`, collection, id, body.authorId || body.uid || '', JSON.stringify(body),
    Number(body.createdAt || body.updatedAt || 0)).run();
}

async function seed(db) {
  const posts = [
    { id: 'p1', authorId: 'a1', content: 'one', privacy: 'Public', createdAt: 100, mediaType: 'none' },
    { id: 'p2', authorId: 'a1', content: 'two', privacy: 'Friends', createdAt: 200, mediaType: 'video' },
    { id: 'p3', authorId: 'a2', content: 'three', privacy: 'Public', createdAt: 300, mediaType: 'photo' },
    { id: 'p4', authorId: 'a2', content: 'gone', privacy: 'Public', createdAt: 400, archived: true },
    { id: 'p5', authorId: 'a3', content: 'tags', privacy: 'Public', createdAt: 500, taggedUids: ['a1', 'a9'] },
    { id: 'p6', authorId: 'a2', content: 'secret', privacy: 'Only me', createdAt: 600 },
  ];
  for (const p of posts) await insert(db, 'posts', p.id, p);
}

/**
 * Firestore stand-in: post-migration rows live in D1, but a profile that predates the migration is
 * only in the old source, and the visibility check must still be able to reach it. 404 = not found.
 */
function firestoreStub(docs = {}) {
  return async (url) => {
    const path = decodeURIComponent(String(url).split('/documents/')[1] || '');
    const body = docs[path];
    if (!body) return new Response('{}', { status: 404 });
    const fields = {};
    for (const [key, value] of Object.entries(body)) {
      fields[key] = Array.isArray(value)
        ? { arrayValue: { values: value.map((v) => ({ stringValue: String(v) })) } }
        : { stringValue: String(value) };
    }
    return Response.json({ fields });
  };
}

async function get(env, query, uid = 'a1') {
  const w = createWorker(async () => uid);
  const res = await w.fetch(new Request(`https://example.com/v1/docs?${query}`, {
    headers: { Authorization: 'Bearer test-token' },
  }), env);
  return { status: res.status, body: await res.json() };
}

/** A worker whose read path cannot reach the old profile source at all. */
async function getOffline(env, query, uid = 'a1') {
  const w = createWorker(async () => uid);
  const offline = { ...env, fetch: async () => { throw new Error('source offline'); } };
  const res = await w.fetch(new Request(`https://example.com/v1/docs?${query}`, {
    headers: { Authorization: 'Bearer test-token' },
  }), offline);
  return { status: res.status, body: await res.json() };
}

test('the read engine orders by a document field and pages by the same cursor the app uses', async () => {
  const { db } = createSqliteD1();
  await seed(db);
  const env = { DB: db, FIREBASE_PROJECT_ID: 'friendbook-test', fetch: firestoreStub() };

  // The reader (a1) sees his own posts, public posts, and never the archived or "Only me" ones.
  const first = await get(env, 'collection=posts&order=createdAt&dir=desc&limit=2');
  assert.equal(first.status, 200);
  assert.deepEqual(first.body.documents.map((d) => d.id), ['p5', 'p3']);
  assert.equal(first.body.cursor, 'p3');
  assert.equal(first.body.hasMore, true);

  const second = await get(env, `collection=posts&order=createdAt&dir=desc&limit=2&cursor=${first.body.cursor}`);
  assert.deepEqual(second.body.documents.map((d) => d.id), ['p2', 'p1']);
  assert.equal(second.body.cursor, 'p1');
  assert.equal(second.body.hasMore, false);

  // Ascending means the other end of the same ordering.
  const asc = await get(env, 'collection=posts&order=createdAt&dir=asc&limit=2');
  assert.deepEqual(asc.body.documents.map((d) => d.id), ['p1', 'p2']);

  // The cursor the engine returns is exactly the next request's cursor: no document is skipped.
  const seen = [];
  let cursor = '';
  for (let i = 0; i < 6; i++) {
    const page = await get(env, `collection=posts&order=createdAt&dir=desc&limit=2&cursor=${cursor}`);
    seen.push(...page.body.documents.map((d) => d.id));
    if (!page.body.hasMore) break;
    cursor = page.body.cursor;
  }
  assert.deepEqual(seen, ['p5', 'p3', 'p2', 'p1']);
});

test('archived documents stay out of a page and a requested field filter is applied', async () => {
  const { db } = createSqliteD1();
  await seed(db);
  const env = { DB: db, FIREBASE_PROJECT_ID: 'friendbook-test', fetch: firestoreStub() };

  const all = await get(env, 'collection=posts&order=createdAt&limit=50');
  assert.deepEqual(all.body.documents.map((d) => d.id), ['p5', 'p3', 'p2', 'p1']);
  assert.ok(!all.body.documents.some((d) => d.archived));
  assert.ok(!all.body.documents.some((d) => d.id === 'p6'), 'an "Only me" post is never readable by others');

  // `archived=1` alone only stops hiding archived rows: visibility still applies, so the owner's
  // archived post stays invisible to everyone else.
  const othersArchive = await get(env, 'collection=posts&order=createdAt&limit=50&archived=1');
  assert.ok(!othersArchive.body.documents.some((d) => d.id === 'p4'));

  // The owner's own archive is readable by the owner, inside his own scope.
  const ownerArchive = await get(env, 'collection=posts&order=createdAt&limit=50&owner=1&archived=1', 'a2');
  assert.deepEqual(ownerArchive.body.documents.map((d) => d.id), ['p6', 'p4', 'p3']);

  const videos = await get(env, 'collection=posts&order=createdAt&where=mediaType:eq:video&limit=50');
  assert.deepEqual(videos.body.documents.map((d) => d.id), ['p2']);
  assert.equal(videos.body.filtered, true);

  const publicOrFriends = await get(env, 'collection=posts&order=createdAt&where=privacy:in:Public|Friends&limit=50');
  assert.deepEqual(publicOrFriends.body.documents.map((d) => d.id), ['p5', 'p3', 'p2', 'p1']);

  const tagged = await get(env, 'collection=posts&order=createdAt&where=taggedUids:contains:a1&limit=50');
  assert.deepEqual(tagged.body.documents.map((d) => d.id), ['p5']);

  const newer = await get(env, 'collection=posts&order=createdAt&where=createdAt:gt:250&limit=50');
  assert.deepEqual(newer.body.documents.map((d) => d.id), ['p5', 'p3']);
});

test('visibility is decided from the stored document, not from what the request asks for', async () => {
  const { db } = createSqliteD1();
  await seed(db);
  // a2's profile predates the migration: it only exists in the old source.
  const env = {
    DB: db,
    FIREBASE_PROJECT_ID: 'friendbook-test',
    fetch: firestoreStub({ 'users/a2': { displayName: 'Two' } }),
  };

  // p6 is "Only me" and belongs to a2: no query parameter widens it.
  for (const extra of ['', '&private=1', '&archived=1', '&private=1&archived=1']) {
    const page = await get(env, `collection=posts&order=createdAt&limit=50${extra}`);
    assert.ok(!page.body.documents.some((d) => d.id === 'p6'), `private=1 must not expose a private post (${extra})`);
  }

  // "Friends" needs a friendship that exists in D1 - not a claim in the request.
  const friendless = await get(env, 'collection=posts&order=createdAt&limit=50', 'a9');
  assert.ok(!friendless.body.documents.some((d) => d.id === 'p2'));

  // A friendship that cannot be checked is not a pass either: no proof, no post.
  const offline = await getOffline(env, 'collection=posts&order=createdAt&limit=50', 'a9');
  assert.ok(!offline.body.documents.some((d) => d.id === 'p2'));

  await insert(db, 'friends', 'a1_a9', { authorId: 'a1', participants: ['a1', 'a9'] });
  const friended = await get(env, 'collection=posts&order=createdAt&limit=50', 'a9');
  assert.ok(friended.body.documents.some((d) => d.id === 'p2'), 'a D1 friendship unlocks a "Friends" post');

  // Blocking a reader removes the author's posts from that reader's page.
  await insert(db, 'users', 'a3', { uid: 'a3', displayName: 'Three', blocked: ['a9'] });
  const blocked = await get(env, 'collection=posts&order=createdAt&limit=50', 'a9');
  assert.ok(!blocked.body.documents.some((d) => d.id === 'p5'), 'a blocked author is not readable');
});

test('the owner scope is the write history, and it never leaks another user', async () => {
  const { db } = createSqliteD1();
  await seed(db);
  const env = { DB: db, FIREBASE_PROJECT_ID: 'friendbook-test', fetch: firestoreStub() };

  // The owner scope is the signed-in user's own write history: everything he ever wrote, newest
  // first, including his archived and his "Only me" documents - and nobody else's.
  const mine = await get(env, 'collection=posts&order=createdAt&limit=50&owner=1&archived=1', 'a2');
  assert.deepEqual(mine.body.documents.map((d) => d.id), ['p6', 'p4', 'p3']);
  assert.equal(mine.body.scope, 'owner');

  const someoneElse = await get(env, 'collection=posts&order=createdAt&limit=50&owner=1&archived=1', 'a3');
  assert.deepEqual(someoneElse.body.documents.map((d) => d.id), ['p5']);

  // The feed scope is this user's own documents plus what he may read; `public=1` narrows it to
  // public posts, so a stranger's "Friends" post never appears.
  const feedScope = await get(env, 'collection=posts&order=createdAt&limit=50&mine=1&public=1', 'a9');
  assert.equal(feedScope.body.scope, 'feed');
  // p1 is a1's public post, so it is readable; p2 is his "Friends" post and is not.
  assert.deepEqual(feedScope.body.documents.map((d) => d.id), ['p5', 'p3', 'p1']);

  const ownAlsoVisible = await get(env, 'collection=posts&order=createdAt&limit=50&mine=1', 'a1');
  assert.ok(ownAlsoVisible.body.documents.some((d) => d.id === 'p2'), 'a reader always sees his own post');
});

test('a per-user page is never served from a shared cache', async () => {
  const { db } = createSqliteD1();
  await seed(db);
  const env = { DB: db, FIREBASE_PROJECT_ID: 'friendbook-test', fetch: firestoreStub() };

  // Visibility runs for every read, so every page is per-user: nothing may be cached.
  const plain = await get(env, 'collection=posts&order=createdAt&limit=2');
  assert.equal(plain.body.cached, undefined);
  const plainAgain = await get(env, 'collection=posts&order=createdAt&limit=2');
  assert.equal(plainAgain.body.cached, undefined);

  const mine = await get(env, 'collection=posts&order=createdAt&where=authorId:eq:a2&limit=50');
  assert.deepEqual(mine.body.documents.map((d) => d.id), ['p3']);

  const other = await get(env, 'collection=posts&order=createdAt&where=authorId:eq:a1&limit=50');
  assert.deepEqual(other.body.documents.map((d) => d.id), ['p2', 'p1']);
});

test('an unsupported or malformed query is refused instead of returning the wrong page', async () => {
  const { db } = createSqliteD1();
  await seed(db);
  const env = { DB: db, FIREBASE_PROJECT_ID: 'friendbook-test', fetch: firestoreStub() };

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

test('a write belongs to its author: nobody can overwrite, forge or read another user\'s document', async () => {
  const { db } = createSqliteD1();
  await seed(db);
  const env = { DB: db, FIREBASE_PROJECT_ID: 'friendbook-test', fetch: firestoreStub() };

  async function put(uid, path, body) {
    const w = createWorker(async () => uid);
    const res = await w.fetch(new Request(`https://example.com/v1/docs/${path}`, {
      method: 'PUT',
      headers: { Authorization: 'Bearer test-token', 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }), env);
    return { status: res.status, body: await res.json() };
  }
  async function getOne(uid, path) {
    const w = createWorker(async () => uid);
    const res = await w.fetch(new Request(`https://example.com/v1/docs/${path}`, {
      headers: { Authorization: 'Bearer test-token' },
    }), env);
    return { status: res.status, body: await res.json() };
  }

  // A new post written by an updated APK goes to D1 with its own author as the owner.
  assert.equal((await put('a1', 'posts/new1', { authorId: 'a1', content: 'mine', privacy: 'Public' })).status, 200);

  // Forging another author is refused: the Wire can never write as somebody else.
  assert.equal((await put('a9', 'posts/new2', { authorId: 'a1', content: 'forged', privacy: 'Public' })).status, 403);

  // Overwriting somebody else's content is refused outright - not silently ignored.
  assert.equal((await put('a9', 'posts/new1', { content: 'hijacked' })).status, 403);
  assert.equal((await put('a9', 'posts/new1', { authorId: 'a9', content: 'hijacked' })).status, 403);

  // The shared counters stay writable (that is how likes/comments/shares already work).
  assert.equal((await put('a9', 'posts/new1', { likes: 7 })).status, 200);
  const afterLike = await getOne('a1', 'posts/new1');
  assert.equal(afterLike.body.document.likes, 7);
  assert.equal(afterLike.body.document.content, 'mine');
  assert.equal(afterLike.body.document.authorId, 'a1');

  // A single document is not more public than a page: "Only me" stays the author's own.
  assert.equal((await getOne('a2', 'posts/p6')).status, 200);
  assert.equal((await getOne('a9', 'posts/p6')).status, 404);
  assert.equal((await getOne('a9', 'posts/p5')).status, 200);
});

test('a comment counter is a delta the database applies, never a client-computed total', async () => {
  const { db } = createSqliteD1();
  await seed(db);
  const env = { DB: db, FIREBASE_PROJECT_ID: 'friendbook-test', fetch: firestoreStub() };
  async function put(uid, path, body) {
    const w = createWorker(async () => uid);
    const res = await w.fetch(new Request(`https://example.com/v1/docs/${path}`, {
      method: 'PUT',
      headers: { Authorization: 'Bearer test-token', 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }), env);
    return { status: res.status, body: await res.json() };
  }
  async function getOne(uid, path) {
    const w = createWorker(async () => uid);
    const res = await w.fetch(new Request(`https://example.com/v1/docs/${path}`, {
      headers: { Authorization: 'Bearer test-token' },
    }), env);
    return { status: res.status, body: await res.json() };
  }

  // The author's post starts with no counter at all.
  assert.equal((await put('a1', 'posts/c1', { authorId: 'a1', content: 'count me', privacy: 'Public' })).status, 200);
  // Three comments from three different readers, each sending a delta rather than a total.
  assert.equal((await put('a2', 'comments/k1', { authorId: 'a2', postId: 'c1', commentsDelta: 1 })).status, 200);
  assert.equal((await put('a9', 'comments/k2', { authorId: 'a9', postId: 'c1', commentsDelta: 1 })).status, 200);
  assert.equal((await put('a3', 'comments/k3', { authorId: 'a3', postId: 'c1', commentsDelta: 1 })).status, 200);
  // The delta never becomes a stored field of its own.
  assert.equal((await getOne('a3', 'comments/k3')).body.document.commentsDelta, undefined);

  // A counter the client does own is still writable, and it is additive on the stored value.
  assert.equal((await put('a2', 'posts/c1', { comments: 9 })).status, 200);
  assert.equal((await put('a9', 'posts/c1', { commentsDelta: 1 })).status, 200);
  const post = await getOne('a1', 'posts/c1');
  assert.equal(post.body.document.comments, 10, 'the stored total is incremented, not replaced');
  assert.equal(post.body.document.content, 'count me', 'a delta must not touch the content');
});

test('any counter delta is applied to the stored number, so parallel devices cannot lose a write', async () => {
  const { db } = createSqliteD1();
  await seed(db);
  const env = { DB: db, FIREBASE_PROJECT_ID: 'friendbook-test', fetch: firestoreStub() };
  async function put(uid, path, body) {
    const w = createWorker(async () => uid);
    return (await w.fetch(new Request(`https://example.com/v1/docs/${path}`, {
      method: 'PUT',
      headers: { Authorization: 'Bearer test-token', 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }), env)).status;
  }
  async function getOne(uid, path) {
    const w = createWorker(async () => uid);
    return (await (await w.fetch(new Request(`https://example.com/v1/docs/${path}`, {
      headers: { Authorization: 'Bearer test-token' },
    }), env)).json()).document;
  }

  assert.equal(await put('a1', 'posts/d1', { authorId: 'a1', content: 'x', privacy: 'Public', likes: 4 }), 200);
  // Friend counters live on the user row and can go up and down.
  assert.equal(await put('a2', 'users/a2', { displayName: 'Two' }), 200);
  assert.equal(await put('a2', 'users/a2', { friendsCount: 1 }), 200);
  assert.equal(await put('a9', 'users/a2', { friendsCountDelta: 1 }), 200);
  assert.equal(await put('a3', 'users/a2', { friendsCountDelta: -1 }), 200);
  const user = await getOne('a2', 'users/a2');
  assert.equal(user.friendsCount, 1, 'up then down lands on the same number');
  assert.equal(user.friendsCountDelta, undefined, 'a delta is never stored as a field');

  // A delta can never be negative below zero and never exceeds a sane bound.
  assert.equal(await put('a9', 'posts/d1', { likesDelta: -50 }), 200);
  assert.equal((await getOne('a1', 'posts/d1')).likes, 0, 'a counter never goes below zero');
  assert.equal(await put('a9', 'posts/d1', { likesDelta: 99999 }), 200);
  assert.equal((await getOne('a1', 'posts/d1')).likes, 1000, 'a delta is bounded per request');
});

test('a chat message is read by its room, and only by somebody in that room', async () => {
  const { db } = createSqliteD1();
  await seed(db);
  const env = { DB: db, FIREBASE_PROJECT_ID: 'friendbook-test', fetch: firestoreStub() };
  async function put(uid, path, body) {
    const w = createWorker(async () => uid);
    return (await w.fetch(new Request(`https://example.com/v1/docs/${path}`, {
      method: 'PUT',
      headers: { Authorization: 'Bearer test-token', 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }), env)).status;
  }
  async function get(uid, query) {
    const w = createWorker(async () => uid);
    const res = await w.fetch(new Request(`https://example.com/v1/docs?${query}`, {
      headers: { Authorization: 'Bearer test-token' },
    }), env);
    return { status: res.status, body: await res.json() };
  }

  // A room between a1 and a2, written the way the app now writes it: the room row first, then each
  // message as a flat `messages/<chatId>_<messageId>` document that names its room.
  assert.equal(await put('a1', 'chats/a1_a2', {
    participants: ['a1', 'a2'], lastMessage: 'hello', lastMessageAt: 1000,
  }), 200);
  assert.equal(await put('a1', 'messages/a1_a2_m1', {
    chatId: 'a1_a2', senderId: 'a1', receiverId: 'a2', text: 'hello', createdAtMs: 1000,
  }), 200);
  assert.equal(await put('a2', 'messages/a1_a2_m2', {
    chatId: 'a1_a2', senderId: 'a2', receiverId: 'a1', text: 'hi there', createdAtMs: 2000,
  }), 200);

  // Both sides read the conversation through the room id, newest last.
  for (const who of ['a1', 'a2']) {
    const page = await get(who, 'collection=messages&where=chatId:eq:a1_a2&order=createdAtMs&dir=asc&limit=20');
    assert.equal(page.status, 200);
    assert.deepEqual(page.body.documents.map((d) => d.text), ['hello', 'hi there']);
  }

  // Somebody outside the room reads nothing at all.
  const outsider = await get('a3', 'collection=messages&where=chatId:eq:a1_a2&limit=20');
  assert.equal(outsider.status, 200);
  assert.deepEqual(outsider.body.documents, [], 'a room is private to its participants');

  // A message document is never readable by id from outside either.
  const direct = await get('a3', 'collection=messages&limit=20');
  assert.deepEqual(direct.body.documents, []);
});

test('a map field merges by key, so two people can react without erasing each other', async () => {
  const { db } = createSqliteD1();
  await seed(db);
  const env = { DB: db, FIREBASE_PROJECT_ID: 'friendbook-test', fetch: firestoreStub() };
  async function put(uid, path, body) {
    const w = createWorker(async () => uid);
    return (await w.fetch(new Request(`https://example.com/v1/docs/${path}`, {
      method: 'PUT',
      headers: { Authorization: 'Bearer test-token', 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }), env)).status;
  }
  async function getOne(uid, path) {
    const w = createWorker(async () => uid);
    return (await (await w.fetch(new Request(`https://example.com/v1/docs/${path}`, {
      headers: { Authorization: 'Bearer test-token' },
    }), env)).json()).document;
  }

  assert.equal(await put('a1', 'posts/r1', { authorId: 'a1', content: 'react to me', privacy: 'Public' }), 200);
  // Two readers react with different emojis: each one only sends its own member.
  assert.equal(await put('a2', 'posts/r1', { reactionsMap: { a2: '❤️' }, likesDelta: 1 }), 200);
  assert.equal(await put('a3', 'posts/r1', { reactionsMap: { a3: '👍' }, likesDelta: 1 }), 200);
  let post = await getOne('a1', 'posts/r1');
  assert.deepEqual(post.reactionsMap, { a2: '❤️', a3: '👍' }, 'both reactions survive');
  assert.equal(post.likes, 2);

  // Taking one back deletes only that member, and the counter follows.
  assert.equal(await put('a2', 'posts/r1', { reactionsMap: { a2: null }, likesDelta: -1 }), 200);
  post = await getOne('a1', 'posts/r1');
  assert.deepEqual(post.reactionsMap, { a3: '👍' }, 'a null member removes one key, not the map');
  assert.equal(post.likes, 1);
});
