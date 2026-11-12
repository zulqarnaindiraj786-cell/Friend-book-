import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import { createWorker } from '../../worker.js';

const MIGRATIONS = ['0001_additive_profiles', '0002_additive_copy_staging', '0003_additive_interactions',
  '0011_additive_app_documents'];

function createSqliteD1() {
  const sql = new DatabaseSync(':memory:');
  for (const name of MIGRATIONS) {
    sql.exec(readFileSync(new URL(`../migrations/${name}.sql`, import.meta.url), 'utf8'));
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
    async batch(statements) {
      for (const statement of statements) await statement.run();
      return statements.map(() => ({ success: true }));
    },
  };
  return { sql, db };
}

/** A worker whose verifier answers with one fixed identity, so each caller is a different user. */
const workerFor = (uid, email = '') => createWorker(async () => ({ uid, email }));
const envFor = (db) => ({ DB: db, FIREBASE_PROJECT_ID: 'friendbook-17551' });

async function call(worker, db, path, { method = 'GET', body } = {}) {
  return worker.fetch(new Request(`https://example.invalid${path}`, {
    method,
    headers: body === undefined ? { Authorization: 'Bearer t' } : { Authorization: 'Bearer t', 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  }), envFor(db));
}

const put = (worker, db, path, body) => call(worker, db, path, { method: 'PUT', body });
const patch = (worker, db, path, body) => call(worker, db, path, { method: 'PATCH', body });
const list = (worker, db, query) => call(worker, db, `/v1/docs?${query}`);

async function seed(db, rows) {
  for (const [path, collection, docId, owner, doc, updatedAt] of rows) {
    await db.prepare(
      `INSERT INTO friendbook_cf_documents(path, collection, doc_id, owner_uid, document_json, updated_at)
       VALUES (?, ?, ?, ?, ?, ?)`
    ).bind(path, collection, docId, owner, JSON.stringify(doc), updatedAt).run();
  }
}

test('a filtered list answers with only the matching documents, newest first, and pages by cursor', async () => {
  const { db } = createSqliteD1();
  await seed(db, [
    ['posts/p1', 'posts', 'p1', 'u1', { authorId: 'u1', content: 'first', createdAt: 1000, likes: 2 }, 1000],
    ['posts/p2', 'posts', 'p2', 'u1', { authorId: 'u1', content: 'second', createdAt: 2000, likes: 5 }, 2000],
    ['posts/p3', 'posts', 'p3', 'u2', { authorId: 'u2', content: 'other', createdAt: 3000, likes: 0 }, 3000],
  ]);
  const w = workerFor('u1');

  const mine = await (await list(w, db, 'collection=posts&eq%5BauthorId%5D=u1&order=createdAt&dir=desc&limit=10')).json();
  assert.deepEqual(mine.documents.map(d => d.id), ['p2', 'p1']);
  assert.equal(mine.next, null);

  const page1 = await (await list(w, db, 'collection=posts&eq%5BauthorId%5D=u1&order=createdAt&dir=desc&limit=1')).json();
  assert.deepEqual(page1.documents.map(d => d.id), ['p2']);
  assert.ok(page1.next, 'a full page must hand back a cursor');
  const page2 = await (await list(w, db, `collection=posts&eq%5BauthorId%5D=u1&order=createdAt&dir=desc&limit=1&after=${encodeURIComponent(page1.next)}`)).json();
  assert.deepEqual(page2.documents.map(d => d.id), ['p1'], 'the cursor continues exactly where the page ended');

  const counted = await (await list(w, db, 'collection=posts&eq%5BauthorId%5D=u1&count=1')).json();
  assert.equal(counted.count, 2);
});

test('array-contains filters and since-cursors work, and unknown filters are refused', async () => {
  const { db } = createSqliteD1();
  await seed(db, [
    ['friends/f1', 'friends', 'f1', 'u1', { participants: ['u1', 'u2'], createdAt: 10 }, 10],
    ['friends/f2', 'friends', 'f2', 'u3', { participants: ['u3', 'u4'], createdAt: 20 }, 20],
    ['messages/m1', 'messages', 'm1', 'u1', { chatId: 'c1', text: 'old', createdAt: 100 }, 100],
    ['chats/c1', 'chats', 'c1', 'u1', { participants: ['u1', 'u2'], createdAt: 50 }, 50],
    ['messages/m2', 'messages', 'm2', 'u2', { chatId: 'c1', text: 'new', createdAt: 200 }, 200],
  ]);
  const w = workerFor('u1');

  const friends = await (await list(w, db, 'collection=friends&has%5Bparticipants%5D=u1')).json();
  assert.deepEqual(friends.documents.map(d => d.id), ['f1']);

  const changed = await (await list(w, db, 'collection=messages&eq%5BchatId%5D=c1&since=150&order=createdAt&dir=asc')).json();
  assert.deepEqual(changed.documents.map(d => d.text), ['new'], 'since is how a client watches a thread');

  const bad = await list(w, db, 'collection=posts&eq%5BsecretField%5D=x');
  assert.equal(bad.status, 400);
  const badOrder = await list(w, db, 'collection=posts&order=secretField');
  assert.equal(badOrder.status, 400);
  const badCollection = await list(w, db, 'collection=password_hashes');
  assert.equal(badCollection.status, 400);
});

test('private collections are scoped by the Worker: nobody pages through somebody else\'s inbox', async () => {
  const { db } = createSqliteD1();
  await seed(db, [
    ['notifications/n1', 'notifications', 'n1', 'u2', { toUid: 'u2', text: 'private to u2', createdAt: 10 }, 10],
    ['notifications/n2', 'notifications', 'n2', 'u1', { toUid: 'u1', text: 'for me', createdAt: 20 }, 20],
    ['payments/pay1', 'payments', 'pay1', 'u2', { userId: 'u2', status: 'pending', createdAt: 30 }, 30],
    ['reports/r1', 'reports', 'r1', 'u2', { uid: 'u2', reason: 'spam', createdAt: 40 }, 40],
  ]);
  const stranger = workerFor('u1');

  const inbox = await (await list(stranger, db, 'collection=notifications&order=createdAt&dir=desc')).json();
  assert.deepEqual(inbox.documents.map(d => d.id), ['n2'], 'only my own notifications, without asking for a filter');

  const peek = await call(stranger, db, '/v1/docs/notifications/n1');
  assert.equal(peek.status, 403);

  const payments = await (await list(stranger, db, 'collection=payments')).json();
  assert.deepEqual(payments.documents, []);

  const reports = await (await list(stranger, db, 'collection=reports')).json();
  assert.deepEqual(reports.documents, [], 'a moderation inbox is admin-only');
  assert.equal((await call(stranger, db, '/v1/docs/reports/r1')).status, 403);

  // Filing a report is still open to any signed-in user.
  const filed = await put(stranger, db, '/v1/docs/reports/r2', { uid: 'u1', reason: 'abuse', postId: 'p9' });
  assert.equal(filed.status, 200);
});

test('an admin is recognised from the stored user document and from the owner email', async () => {
  const { db } = createSqliteD1();
  await seed(db, [
    ['users/admin1', 'users', 'admin1', 'admin1', { uid: 'admin1', isAdmin: true, displayName: 'Admin' }, 10],
    ['notifications/n1', 'notifications', 'n1', 'u2', { toUid: 'u2', text: 'private', createdAt: 20 }, 20],
    ['reports/r1', 'reports', 'r1', 'u2', { uid: 'u2', reason: 'spam' }, 30],
  ]);
  const admin = workerFor('admin1');
  const inbox = await (await list(admin, db, 'collection=notifications')).json();
  assert.deepEqual(inbox.documents.map(d => d.id), ['n1'], 'an admin sees every inbox');
  assert.equal((await call(admin, db, '/v1/docs/reports/r1')).status, 200);

  const owner = workerFor('someone', 'zulqarnaindiraj786@gmail.com');
  assert.equal((await call(owner, db, '/v1/docs/reports/r1')).status, 200);

  const normal = workerFor('u2');
  assert.equal((await list(normal, db, 'collection=reports')).status, 200);
  assert.deepEqual((await (await list(normal, db, 'collection=reports')).json()).documents, []);
});

test('chat messages are gated by the parent chat, for participants only', async () => {
  const { db } = createSqliteD1();
  await seed(db, [
    ['chats/c1', 'chats', 'c1', 'u1', { participants: ['u1', 'u2'], createdAt: 1 }, 1],
    ['messages/m1', 'messages', 'm1', 'u1', { chatId: 'c1', senderId: 'u1', text: 'hello', createdAt: 10 }, 10],
    ['messages/m2', 'messages', 'm2', 'u2', { chatId: 'c1', senderId: 'u2', text: 'hi', createdAt: 20 }, 20],
  ]);
  const peer = workerFor('u2');
  const thread = await (await list(peer, db, 'collection=messages&eq%5BchatId%5D=c1&order=createdAt&dir=asc')).json();
  assert.deepEqual(thread.documents.map(d => d.text), ['hello', 'hi']);
  assert.equal((await call(peer, db, '/v1/docs/messages/m1')).status, 200);

  const outsider = workerFor('u9');
  assert.equal((await list(outsider, db, 'collection=messages&eq%5BchatId%5D=c1')).status, 403);
  assert.equal((await list(outsider, db, 'collection=messages')).status, 400, 'a thread must be named');
  assert.equal((await call(outsider, db, '/v1/docs/messages/m1')).status, 403);
  assert.equal((await put(outsider, db, '/v1/docs/messages/m3', { chatId: 'c1', text: 'intruder' })).status, 403);

  // Writing inside a chat you are part of works, and the sender becomes the owner of the row.
  const sent = await put(peer, db, '/v1/docs/messages/m4', { chatId: 'c1', senderId: 'u2', text: 'again', createdAt: 30 });
  assert.equal(sent.status, 200);
  assert.equal((await list(peer, db, 'collection=messages&eq%5BchatId%5D=c1&order=createdAt&dir=desc')).status, 200);
});

test('atomic field operations keep counters and arrays correct under repeated writes', async () => {
  const { db } = createSqliteD1();
  await seed(db, [['posts/p1', 'posts', 'p1', 'u1', { authorId: 'u1', likes: 1, likedBy: ['u1'], createdAt: 5 }, 5]]);
  const liker = workerFor('u2');

  for (let i = 0; i < 3; i += 1) {
    const res = await patch(liker, db, '/v1/docs/posts/p1', { inc: { likes: 1 }, add: { likedBy: ['u2'] } });
    assert.equal(res.status, 200);
  }
  const doc = (await (await call(liker, db, '/v1/docs/posts/p1')).json()).document;
  assert.equal(doc.likes, 4, 'three increments on top of one like');
  assert.deepEqual(doc.likedBy, ['u1', 'u2'], 'a double tap stores the uid once');

  const unliked = await patch(liker, db, '/v1/docs/posts/p1', { inc: { likes: -1 }, remove: { likedBy: ['u2'] } });
  assert.equal(unliked.status, 200);
  const after = (await (await call(liker, db, '/v1/docs/posts/p1')).json()).document;
  assert.equal(after.likes, 3);
  assert.deepEqual(after.likedBy, ['u1']);

  const stamped = await patch(liker, db, '/v1/docs/posts/p1', { stamp: ['editedAt'], set: { content: 'edited' }, unset: ['draft'] });
  assert.equal(stamped.status, 200);
  const final = (await (await call(liker, db, '/v1/docs/posts/p1')).json()).document;
  assert.equal(final.content, 'edited');
  assert.ok(final.editedAt > 1_700_000_000_000, 'the server stamps its own clock');
  assert.equal('draft' in final, false);

  // A patch on a document that does not exist yet creates it, with the ops applied.
  const created = await patch(liker, db, '/v1/docs/posts/p9', { set: { authorId: 'u2', content: 'new' }, inc: { likes: 1 }, stamp: ['createdAt'] });
  assert.equal(created.status, 200);
  const fresh = (await (await call(liker, db, '/v1/docs/posts/p9')).json()).document;
  assert.equal(fresh.likes, 1);
  assert.equal(fresh.authorId, 'u2');
});

test('deleting is a tombstone: the content goes away, the history stays', async () => {
  const { sql, db } = createSqliteD1();
  await seed(db, [
    ['posts/p1', 'posts', 'p1', 'u1', { authorId: 'u1', content: 'mine', createdAt: 10 }, 10],
    ['posts/p2', 'posts', 'p2', 'u2', { authorId: 'u2', content: 'theirs', createdAt: 20 }, 20],
  ]);
  const author = workerFor('u1');

  assert.equal((await call(author, db, '/v1/docs/posts/p2', { method: 'DELETE' })).status, 403, 'not my post');
  const gone = await call(author, db, '/v1/docs/posts/p1', { method: 'DELETE' });
  assert.equal(gone.status, 200);
  assert.deepEqual(await gone.json(), { id: 'p1', path: 'posts/p1', deleted: true, kept: true });

  assert.equal((await call(author, db, '/v1/docs/posts/p1')).status, 404);
  const remaining = await (await list(author, db, 'collection=posts&order=createdAt&dir=desc')).json();
  assert.deepEqual(remaining.documents.map(d => d.id), ['p2']);

  const row = sql.prepare('SELECT document_json FROM friendbook_cf_documents WHERE path = ?').get('posts/p1');
  assert.ok(row, 'the row is never dropped');
  assert.equal(JSON.parse(row.document_json).deleted, true);
  assert.equal(JSON.parse(row.document_json).content, 'mine', 'the bytes survive the tombstone');
  assert.equal((await call(author, db, '/v1/docs/posts/p1', { method: 'DELETE' })).status, 404);
  assert.equal((await put(author, db, '/v1/docs/posts/p1', { content: 'resurrected' })).status, 404);
});

test('a batch applies every operation or refuses the whole request', async () => {
  const { db } = createSqliteD1();
  await seed(db, [
    ['posts/p1', 'posts', 'p1', 'u1', { authorId: 'u1', content: 'one', createdAt: 10 }, 10],
    ['comments/c1', 'comments', 'c1', 'u1', { postId: 'p1', authorId: 'u1', text: 'hi', createdAt: 20 }, 20],
    ['comments/c2', 'comments', 'c2', 'u2', { postId: 'p1', authorId: 'u2', text: 'other', createdAt: 30 }, 30],
  ]);
  const w = workerFor('u1');

  const ok = await call(w, db, '/v1/docs/batch', {
    method: 'POST',
    body: {
      ops: [
        { op: 'update', collection: 'posts', id: 'p1', fields: { likes: 3 } },
        { op: 'set', collection: 'posts', id: 'p2', fields: { authorId: 'u1', content: 'two', createdAt: 40 } },
        { op: 'delete', collection: 'comments', id: 'c1' },
      ],
    },
  });
  assert.equal(ok.status, 200);
  assert.equal((await ok.json()).applied, 3);
  assert.equal((await (await call(w, db, '/v1/docs/posts/p1')).json()).document.likes, 3);
  assert.equal((await (await call(w, db, '/v1/docs/posts/p2')).json()).document.content, 'two');
  assert.equal((await call(w, db, '/v1/docs/comments/c1')).status, 404);
  assert.equal((await call(w, db, '/v1/docs/comments/c2')).status, 200, "somebody else's comment is untouched");

  const refused = await call(w, db, '/v1/docs/batch', {
    method: 'POST',
    body: {
      ops: [
        { op: 'update', collection: 'posts', id: 'p1', fields: { likes: 99 } },
        { op: 'delete', collection: 'comments', id: 'c2' },
      ],
    },
  });
  assert.equal(refused.status, 403);
  assert.equal((await (await call(w, db, '/v1/docs/posts/p1')).json()).document.likes, 3, 'nothing was applied');

  const tooMany = await call(w, db, '/v1/docs/batch', {
    method: 'POST',
    body: { ops: Array.from({ length: 21 }, (_v, i) => ({ op: 'set', collection: 'posts', id: `x${i}`, fields: {} })) },
  });
  assert.equal(tooMany.status, 400);
});

test('user documents follow the rules the security rules used to enforce', async () => {
  const { db } = createSqliteD1();
  await seed(db, [['users/u1', 'users', 'u1', 'u1', { uid: 'u1', displayName: 'Me', followers: [], isAdmin: false }, 10]]);
  const me = workerFor('u1');
  const stranger = workerFor('u2');

  assert.equal((await put(me, db, '/v1/docs/users/u1', { displayName: 'Renamed' })).status, 200);
  assert.equal((await (await call(me, db, '/v1/docs/users/u1')).json()).document.displayName, 'Renamed');

  // Somebody else may only touch the collaborative counters, never the guarded flags.
  assert.equal((await put(stranger, db, '/v1/docs/users/u1', { followers: ['u2'] })).status, 200);
  assert.equal((await put(stranger, db, '/v1/docs/users/u1', { isAdmin: true })).status, 403);
  assert.equal((await put(stranger, db, '/v1/docs/users/u1', { banned: true })).status, 403);
  assert.equal((await put(stranger, db, '/v1/docs/users/u1', { displayName: 'Hijacked' })).status, 403);
  assert.equal((await (await call(me, db, '/v1/docs/users/u1')).json()).document.isAdmin, false);

  // And I can never grant myself admin either.
  assert.equal((await put(me, db, '/v1/docs/users/u1', { isAdmin: true })).status, 403);
});

test('money and moderation documents keep their owner and admin rules', async () => {
  const { db } = createSqliteD1();
  await seed(db, [['users/admin1', 'users', 'admin1', 'admin1', { uid: 'admin1', isAdmin: true }, 5]]);
  const payer = workerFor('u1');
  const stranger = workerFor('u3');
  const admin = workerFor('admin1');

  assert.equal((await put(payer, db, '/v1/docs/payments/pay1', { userId: 'u1', status: 'pending', plan: 'blue' })).status, 200);
  assert.equal((await put(stranger, db, '/v1/docs/payments/pay2', { userId: 'u1', status: 'pending' })).status, 403,
    'nobody files a payment for somebody else');
  assert.equal((await put(payer, db, '/v1/docs/payments/pay3', { userId: 'u1', status: 'approved' })).status, 403,
    'a user cannot approve their own payment');
  assert.equal((await call(stranger, db, '/v1/docs/payments/pay1')).status, 403);
  assert.equal((await call(payer, db, '/v1/docs/payments/pay1')).status, 200);
  assert.equal((await put(payer, db, '/v1/docs/payments/pay1', { status: 'approved' })).status, 403);
  assert.equal((await put(admin, db, '/v1/docs/payments/pay1', { status: 'approved' })).status, 200);

  // Pokes: the sender files it, the receiver clears it.
  assert.equal((await put(payer, db, '/v1/docs/pokes/pk1', { from: 'u1', to: 'u3' })).status, 200);
  assert.equal((await put(stranger, db, '/v1/docs/pokes/pk2', { from: 'u1', to: 'u3' })).status, 403);
  assert.equal((await call(stranger, db, '/v1/docs/pokes/pk1', { method: 'DELETE' })).status, 200);
  assert.equal((await call(payer, db, '/v1/docs/pokes/pk1', { method: 'DELETE' })).status, 404);
});

test('migrated legacy documents stay readable and queryable in their Firestore wrapper', async () => {
  const { db } = createSqliteD1();
  await db.prepare(
    `INSERT INTO friendbook_cf_documents(path, collection, doc_id, owner_uid, document_json, updated_at)
     VALUES ('posts/legacy1', 'posts', 'legacy1', 'u1', ?, 1700000000000)`
  ).bind(JSON.stringify({
    fields: {
      authorId: { stringValue: 'u1' }, content: { stringValue: 'Old post' },
      likes: { integerValue: '7' }, createdAt: { integerValue: '1699999999000' },
      likedBy: { arrayValue: { values: [{ stringValue: 'u2' }] } },
    },
  })).run();
  const w = workerFor('u1');

  const byAuthor = await (await list(w, db, 'collection=posts&eq%5BauthorId%5D=u1')).json();
  assert.deepEqual(byAuthor.documents.map(d => d.id), ['legacy1']);
  assert.equal(byAuthor.documents[0].content, 'Old post');
  assert.equal(byAuthor.documents[0].likes, 7);

  const byArray = await (await list(w, db, 'collection=posts&has%5BlikedBy%5D=u2')).json();
  assert.equal(byArray.documents.length, 1);

  // An increment on a legacy row upgrades it in place, without losing the other fields.
  const bumped = await patch(w, db, '/v1/docs/posts/legacy1', { inc: { likes: 1 }, add: { likedBy: ['u3'] } });
  assert.equal(bumped.status, 200);
  const doc = (await (await call(w, db, '/v1/docs/posts/legacy1')).json()).document;
  assert.equal(doc.likes, 8);
  assert.deepEqual(doc.likedBy, ['u2', 'u3']);
  assert.equal(doc.content, 'Old post');
});

test('the document store refuses oversized writes and unbounded requests', async () => {
  const { db } = createSqliteD1();
  const w = workerFor('u1');
  const huge = await put(w, db, '/v1/docs/posts/big', { authorId: 'u1', content: 'x'.repeat(96_000) });
  assert.equal(huge.status, 413);

  const limit = await (await list(w, db, 'collection=posts&limit=5000')).json();
  assert.ok(limit.documents.length <= 50);
  assert.equal((await list(w, db, 'collection=posts&limit=abc')).status, 200);

  const badCursor = await list(w, db, 'collection=posts&after=not-a-cursor');
  assert.equal(badCursor.status, 400);
  assert.equal((await call(w, db, '/v1/docs/posts/p1', { method: 'POST' })).status, 405);
});

test('the comparison and set filters answer the questions the app used to ask Firestore', async () => {
  const { db } = createSqliteD1();
  const w = workerFor('u1');
  const now = 1_700_000_000_000;
  await seed(db, [
    ['stories/s1', 'stories', 's1', 'u1', { authorId: 'u1', expiresAt: now + 60_000, createdAt: now }, now],
    ['stories/s2', 'stories', 's2', 'u1', { authorId: 'u1', expiresAt: now - 60_000, createdAt: now - 1 }, now - 1],
    ['stories/s3', 'stories', 's3', 'u2', { authorId: 'u2', expiresAt: now + 90_000, createdAt: now - 2 }, now - 2],
    ['posts/p1', 'posts', 'p1', 'u1', { authorId: 'u1', mediaType: 'video', createdAt: now }, now],
    ['posts/p2', 'posts', 'p2', 'u2', { authorId: 'u2', mediaType: 'photo', createdAt: now - 1 }, now - 1],
  ]);

  // "stories that have not expired yet" - the live ring must never show yesterday's story.
  const live = await (await list(w, db, `collection=stories&gt%5BexpiresAt%5D=${now}`)).json();
  assert.deepEqual(live.documents.map(d => d.id).sort(), ['s1', 's3']);

  const expiring = await (await list(w, db, `collection=stories&lte%5BexpiresAt%5D=${now}`)).json();
  assert.deepEqual(expiring.documents.map(d => d.id), ['s2']);

  // A feed filtered by one author and a media type at the same time.
  const videos = await (await list(w, db, 'collection=posts&eq%5BmediaType%5D=video')).json();
  assert.deepEqual(videos.documents.map(d => d.id), ['p1']);

  // whereIn: the friend-picker loads exactly the profiles it asked for, in one request.
  const picked = await (await list(w, db, 'collection=posts&in%5BauthorId%5D=u1,u2')).json();
  assert.equal(picked.documents.length, 2);
  const byIds = await (await list(w, db, 'collection=posts&inId=p2,p1,missing')).json();
  assert.deepEqual(byIds.documents.map(d => d.id).sort(), ['p1', 'p2']);

  // A comparison on a text field would silently misorder, so it is refused rather than guessed.
  assert.equal((await list(w, db, 'collection=posts&gt%5BmediaType%5D=photo')).status, 400);
  // An unbounded set is refused too: one request cannot smuggle a full table scan in.
  const tooMany = Array.from({ length: 40 }, (_, i) => `u${i}`).join(',');
  assert.equal((await list(w, db, `collection=posts&in%5BauthorId%5D=${tooMany}`)).status, 400);
  assert.equal((await list(w, db, 'collection=posts&inId=')).status, 400);
  // A field the collection does not expose cannot be compared.
  assert.equal((await list(w, db, 'collection=posts&gt%5Bsecret%5D=1')).status, 400);
});

test('a nested collection is addressed by its parent and gated by the parent, not by its own fields', async () => {
  const { db } = createSqliteD1();
  const now = 1_700_000_000_000;
  await seed(db, [
    ['chats/c1', 'chats', 'c1', 'u1', { participants: ['u1', 'u2'], lastMessageAt: now }, now],
    ['chats/c1/messages/m0', 'chats/c1/messages', 'm0', 'u1', { text: 'older', createdAt: now - 2 }, now - 2],
  ]);

  const inside = workerFor('u2');
  const outside = workerFor('u3');

  // A participant writes into the thread by its parent path, and the row is stored under that path.
  const wrote = await put(inside, db, '/v1/docs/chats/c1/messages/m1', { text: 'hello', createdAt: now });
  assert.equal(wrote.status, 200);
  const stored = await db.prepare('SELECT collection, doc_id FROM friendbook_cf_documents WHERE path = ?')
    .bind('chats/c1/messages/m1').first();
  assert.equal(stored.collection, 'chats/c1/messages');
  assert.equal(stored.doc_id, 'm1');

  // Both participants can list and read the thread; a stranger gets 403 on every one of those.
  const listed = await (await list(inside, db, 'collection=chats/c1/messages&order=createdAt&dir=asc')).json();
  assert.deepEqual(listed.documents.map(d => d.id), ['m0', 'm1']);
  assert.equal((await call(inside, db, '/v1/docs/chats/c1/messages/m1')).status, 200);
  assert.equal((await list(outside, db, 'collection=chats/c1/messages')).status, 403);
  assert.equal((await call(outside, db, '/v1/docs/chats/c1/messages/m1')).status, 403);
  assert.equal((await put(outside, db, '/v1/docs/chats/c1/messages/m2', { text: 'intruder' })).status, 403);
  assert.equal((await call(outside, db, '/v1/docs/chats/c1/messages/m1', { method: 'DELETE' })).status, 403);

  // Somebody's session list is theirs alone: another signed-in user cannot read or write it.
  const sessions = workerFor('u1');
  assert.equal((await put(sessions, db, '/v1/docs/users/u1/sessions/s1', { uid: 'u1', device: 'pixel' })).status, 200);
  assert.equal((await call(sessions, db, '/v1/docs/users/u1/sessions/s1')).status, 200);
  assert.equal((await call(outside, db, '/v1/docs/users/u1/sessions/s1')).status, 403);
  // Naming somebody else's uid does not open their session list either.
  assert.equal((await put(outside, db, '/v1/docs/users/u1/sessions/s2', { uid: 'u3', device: 'intruder' })).status, 403);
  assert.equal((await put(outside, db, '/v1/docs/users/u1/sessions/s3', { uid: 'u1', device: 'intruder' })).status, 403);

  // A nested list is never cached, and a parent that does not exist closes the child.
  const orphan = await list(inside, db, 'collection=chats/nope/messages');
  assert.equal(orphan.status, 403);

  // A batch may carry a nested op, addressed the same way, and one refusal cancels the whole batch.
  const batched = await call(inside, db, '/v1/docs/batch', {
    method: 'POST',
    body: { ops: [{ op: 'set', collection: 'messages', id: 'm2', parent: 'chats/c1', subcollection: 'messages', fields: { text: 'two' } }] },
  });
  assert.equal(batched.status, 200);
  assert.equal((await call(inside, db, '/v1/docs/chats/c1/messages/m2')).status, 200);

  const refused = await call(outside, db, '/v1/docs/batch', {
    method: 'POST',
    body: { ops: [
      { op: 'set', collection: 'posts', id: 'p9', fields: { authorId: 'u3', text: 'allowed' } },
      { op: 'set', collection: 'messages', id: 'm3', parent: 'chats/c1', subcollection: 'messages', fields: { text: 'not allowed' } },
    ] },
  });
  assert.equal(refused.status, 403);
  const leaked = await db.prepare("SELECT path FROM friendbook_cf_documents WHERE path = 'posts/p9'").first();
  assert.equal(leaked, null);
});

test('a change feed reports a tombstone so a listener can drop the row, while a normal read cannot see it', async () => {
  const { db } = createSqliteD1();
  const w = workerFor('u1');
  const now = 1_700_000_000_000;
  await put(w, db, '/v1/docs/posts/p1', { authorId: 'u1', text: 'here', createdAt: now });

  const before = (await (await list(w, db, 'collection=posts')).json()).since;
  assert.equal((await call(w, db, '/v1/docs/posts/p1', { method: 'DELETE' })).status, 200);

  // The feed carries the tombstone, marked, so a poller removes it from the screen it is showing.
  const feed = await (await list(w, db, `collection=posts&since=${before}`)).json();
  assert.equal(feed.documents.length, 1);
  assert.equal(feed.documents[0].id, 'p1');
  assert.equal(feed.documents[0].deleted, true);
  assert.ok(feed.since > before);

  // A plain read still never shows it, and the row is still on disk.
  const after = await (await list(w, db, 'collection=posts')).json();
  assert.equal(after.documents.length, 0);
  assert.equal((await call(w, db, '/v1/docs/posts/p1')).status, 404);
  const kept = await db.prepare("SELECT document_json FROM friendbook_cf_documents WHERE path = 'posts/p1'").first();
  assert.ok(kept && kept.document_json.includes('"deleted":true'));
});
