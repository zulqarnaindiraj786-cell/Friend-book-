import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import { createWorker } from '../../worker.js';

const MIGRATIONS = [
  '0001_additive_profiles.sql',
  '0002_additive_copy_staging.sql',
  '0003_additive_interactions.sql',
  '0004_additive_comment_details.sql',
  '0005_additive_reaction_history.sql',
  '0006_additive_comment_history.sql',
  '0008_additive_legacy_comment_totals.sql',
  '0011_additive_app_documents.sql',
  '0014_additive_core_schema.sql',
].map(f => readFileSync(new URL(`../migrations/${f}`, import.meta.url), 'utf8'));

function createSqliteD1() {
  const sql = new DatabaseSync(':memory:');
  for (const m of MIGRATIONS) {
    sql.exec(m);
  }
  const db = {
    withSession() { return db; },
    async batch(stmts) {
      const out = [];
      for (const s of stmts) out.push(await s.run());
      return out;
    },
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

test('cfdb full Cloudflare cutover: registration, legacy post author join, post counts, friends/followers sync, chat & RTDB', async () => {
  const { sql, db } = createSqliteD1();
  let currentUid = 'ali_uid';
  const worker = createWorker(async (token) => token.replace('tok_', ''));
  const env = {
    DB: db,
    FIREBASE_PROJECT_ID: 'friendbook-17551',
    D1_INTERACTIONS_ENABLED: 'true',
    CLOUDFLARE_ONLY: 'true',
  };

  const call = async (path, method, body, uid = currentUid) => {
    const req = new Request(`https://rough-firefly-dd7b.zulqarnaindiraj786.workers.dev${path}`, {
      method,
      headers: {
        Authorization: `Bearer tok_${uid}`,
        ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      },
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
    const res = await worker.fetch(req, env, { waitUntil() {} });
    return { status: res.status, json: await res.json() };
  };

  // 1. Seed a legacy 0014 user + legacy 0014 post (which has NO authorName / authorPhoto columns!)
  sql.prepare(
    `INSERT INTO users(uid, email, display_name, photo_url, is_verified, tick_level, created_at)
     VALUES (?, ?, ?, ?, ?, ?, ?)`
  ).run('zara_uid', 'zara@example.com', 'Zara Khan', 'https://cdn.example.com/zara.jpg', 1, 'gold', 1700000000000);

  sql.prepare(
    `INSERT INTO posts(id, author_id, content, feeling, privacy, created_at)
     VALUES (?, ?, ?, ?, ?, ?)`
  ).run('legacy_post_1', 'zara_uid', '', 'updated their profile picture', 'Public', 1700000005000);

  // 2. Register new user Ali purely on Cloudflare (/v1/cfdb/write)
  const regRes = await call('/v1/cfdb/write', 'POST', {
    writes: [{
      op: 'set',
      path: 'users/ali_uid',
      merge: true,
      data: {
        uid: 'ali_uid',
        displayName: 'Ali Raza',
        email: 'ali@example.com',
        photoUrl: 'https://cdn.example.com/ali.jpg',
        createdAt: { __fv: 'serverTimestamp' },
      },
    }],
  }, 'ali_uid');
  assert.equal(regRes.status, 200);

  // 3. Query feed posts — legacy post MUST join Zara's real authorName, authorPhotoUrl, authorVerified & tickLevel!
  const feedRes = await call('/v1/cfdb/query', 'POST', {
    collection: 'posts',
    orders: [{ field: 'createdAt', dir: 'DESC' }],
    limit: 20,
  }, 'ali_uid');
  assert.equal(feedRes.status, 200);
  assert.equal(feedRes.json.documents.length, 1);
  const legacyPost = feedRes.json.documents[0].data;
  assert.equal(legacyPost.authorName, 'Zara Khan');
  assert.equal(legacyPost.authorPhotoUrl, 'https://cdn.example.com/zara.jpg');
  assert.equal(legacyPost.authorVerified, true);
  assert.equal(legacyPost.authorTickLevel, 'gold');

  // 4. Create a new post from Ali (even with blank authorName/authorPhoto, backend joins Ali's profile!)
  const createPost = await call('/v1/cfdb/write', 'POST', {
    writes: [{
      op: 'set',
      path: 'posts/post_ali_1',
      data: {
        authorId: 'ali_uid',
        authorName: 'User',
        authorPhotoUrl: '',
        content: 'Hello Cloudflare D1!',
        privacy: 'Public',
        createdAt: { __fv: 'serverTimestamp' },
      },
    }],
  }, 'ali_uid');
  assert.equal(createPost.status, 200);
  assert.equal(createPost.json.results[0].data.authorName, 'Ali Raza');
  assert.equal(createPost.json.results[0].data.authorPhotoUrl, 'https://cdn.example.com/ali.jpg');

  // 5. Verify Zara's post count query == Zara's profile postsCount == Zara's post list length (never 13 vs 0!)
  const zaraProfile1 = await call('/v1/cfdb/get', 'POST', { path: 'users/zara_uid' }, 'ali_uid');
  const zaraPostCount = await call('/v1/cfdb/query', 'POST', {
    collection: 'posts',
    filters: [{ field: 'authorId', op: '==', value: 'zara_uid' }],
    countOnly: true,
  }, 'ali_uid');
  assert.equal(zaraProfile1.json.data.postsCount, 1);
  assert.equal(zaraPostCount.json.count, 1);

  // 6. Send & accept Friend Request (Ali <-> Zara) -> verify Profile followers, following, friendsCount AND FollowList queries ALL == 1!
  await call('/v1/cfdb/write', 'POST', {
    writes: [
      {
        op: 'set',
        path: 'friends/ali_uid_zara_uid',
        data: { participants: ['zara_uid', 'ali_uid'], createdAt: { __fv: 'serverTimestamp' } },
      },
    ],
  }, 'zara_uid');

  const aliProfile = await call('/v1/cfdb/get', 'POST', { path: 'users/ali_uid' }, 'ali_uid');
  assert.equal(aliProfile.json.data.friendsCount, 1);
  assert.deepEqual(aliProfile.json.data.followers, ['zara_uid']);
  assert.deepEqual(aliProfile.json.data.following, ['zara_uid']);

  // FollowListActivity query: users where `following` array-contains ali_uid -> returns Zara!
  const aliFollowersList = await call('/v1/cfdb/query', 'POST', {
    collection: 'users',
    filters: [{ field: 'following', op: 'array-contains', value: 'ali_uid' }],
  }, 'ali_uid');
  assert.equal(aliFollowersList.json.documents.length, 1);
  assert.equal(aliFollowersList.json.documents[0].id, 'zara_uid');

  // CfClient /v1/users/me/friends -> returns Zara with name & photo!
  const cfFriends = await call('/v1/users/me/friends', 'GET', undefined, 'ali_uid');
  assert.equal(cfFriends.status, 200);
  assert.equal(cfFriends.json.friends.length, 1);
  assert.equal(cfFriends.json.friends[0].displayName, 'Zara Khan');

  // 7. Engagement reactions on D1 post with CLOUDFLARE_ONLY=true (zero calls to firestore.googleapis.com!)
  const reactRes = await call('/v1/engagement/posts/post_ali_1/reactions', 'PUT', { type: 'love' }, 'zara_uid');
  assert.equal(reactRes.status, 200);
  assert.equal(reactRes.json.types[0].count, 1);

  // 8. Chat + subcollection messages + nested FieldValue.increment
  await call('/v1/cfdb/write', 'POST', {
    writes: [
      {
        op: 'set',
        path: 'chats/ali_uid_zara_uid',
        merge: true,
        data: {
          participants: ['ali_uid', 'zara_uid'],
          lastMessage: 'Salam!',
          unreadCount: { zara_uid: { __fv: 'increment', n: 1 } },
        },
      },
      {
        op: 'set',
        path: 'chats/ali_uid_zara_uid/messages/msg_1',
        data: {
          senderId: 'ali_uid',
          receiverId: 'zara_uid',
          text: 'Salam!',
          createdAt: { __fv: 'serverTimestamp' },
        },
      },
    ],
  }, 'ali_uid');

  const msgs = await call('/v1/cfdb/query', 'POST', {
    collection: 'chats/ali_uid_zara_uid/messages',
    orders: [{ field: 'createdAt', dir: 'ASC' }],
  }, 'zara_uid');
  assert.equal(msgs.json.documents.length, 1);
  assert.equal(msgs.json.documents[0].data.text, 'Salam!');

  // 9. RTDB compatibility (/v1/rtdb/*)
  await call('/v1/rtdb/calls/ali_uid', 'PUT', { value: { callerId: 'zara_uid', status: 'ringing' } }, 'zara_uid');
  await call('/v1/rtdb/calls/ali_uid', 'PATCH', { value: { status: 'connected' } }, 'ali_uid');
  const rtdbGet = await call('/v1/rtdb/calls/ali_uid', 'GET', undefined, 'ali_uid');
  assert.equal(rtdbGet.json.value.callerId, 'zara_uid');
  assert.equal(rtdbGet.json.value.status, 'connected');
});
