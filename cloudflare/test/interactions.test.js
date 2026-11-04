import { test } from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import { interactions } from '../interactions.js';
import { authorizePost } from '../firebase-post-access.js';
import { createWorker } from '../../worker.js';
const identity = { uid: 'user1', project: 'friendbook-17551', token: 'test' };
function fixture() {
  const sql = new DatabaseSync(':memory:');
  sql.exec('PRAGMA foreign_keys=ON');
  sql.exec(readFileSync(new URL('../migrations/0003_additive_interactions.sql', import.meta.url), 'utf8'));
  sql.exec("INSERT INTO friendbook_cf_interaction_resources VALUES ('posts/p1','verified'),('posts/p2','staging')");
  const DB = { withSession: () => DB, prepare(query) {
    const stmt = sql.prepare(query);
    return { bind: (...args) => ({ first: async () => stmt.get(...args) || null,
      all: async () => ({ results: stmt.all(...args) }), run: async () => stmt.run(...args) }) };
  } };
  const env = { DB, D1_INTERACTIONS_ENABLED: 'true' };
  return { sql, env, close: () => sql.close() };
}
async function call(env, kind, method = 'GET', data, options = {}) {
  const url = `https://example.invalid/v1/engagement/posts/${options.post || 'p1'}/${kind}${options.query || ''}`;
  const request = new Request(url, { method,
    ...(data === undefined ? {} : { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data) }) });
  return interactions(request, env, options.identity || identity,
    [null, 'posts', options.post || 'p1', kind], options.authorize || (async () => true));
}
test('deployment flag, migration marker, auth and authorization fail closed', async t => {
  const f = fixture(); t.after(f.close);
  assert.equal((await call({}, 'comments')).status, 503);
  assert.equal((await call(f.env, 'comments', 'GET', undefined, { post: 'p2', authorize: () => assert.fail('unverified') })).status, 503);
  assert.equal((await call(f.env, 'comments', 'GET', undefined, { authorize: async () => false })).status, 403);
  assert.equal((await call(f.env, 'comments', 'POST', { id: 'c1', text: 'no' }, { authorize: async () => { throw Error(); } })).status, 503);
  assert.equal(f.sql.prepare('SELECT COUNT(*) AS n FROM friendbook_cf_comments').get().n, 0);
  const app = createWorker();
  const response = await app.fetch(new Request('https://example.invalid/v1/engagement/posts/p1/reactions'), { FIREBASE_PROJECT_ID: identity.project });
  assert.equal(response.status, 401);
  assert.equal((await app.fetch(new Request('https://example.invalid/v1/engagement/posts/p1/reactions',
    { headers: { Authorization: 'Bearer invalid' } }), { FIREBASE_PROJECT_ID: identity.project })).status, 401);
});
test('reaction retries set state without duplicate increments or trusting client actor', async t => {
  const f = fixture(); t.after(f.close);
  for (const type of ['like', 'like', 'love']) assert.equal((await call(f.env, 'reactions', 'PUT', { type })).status, 200);
  let data = await (await call(f.env, 'reactions')).json();
  assert.deepEqual(data.types, [{ type: 'love', count: 1, mine: 1 }]);
  assert.equal((await call(f.env, 'reactions', 'PUT', { type: 'like', uid: 'victim' })).status, 400);
  assert.equal((await call(f.env, 'reactions', 'PUT', { type: 'unknown' })).status, 400);
  await call(f.env, 'reactions', 'PUT', { type: null });
  data = await (await call(f.env, 'reactions')).json(); assert.deepEqual(data.types, []);
  assert.equal(f.sql.prepare('SELECT COUNT(*) AS n FROM friendbook_cf_reactions').get().n, 1);
  assert.equal(f.sql.prepare('SELECT SUM(total) AS n FROM friendbook_cf_reaction_totals').get().n, 0);
  await call(f.env, 'reactions', 'PUT', { type: 'like' });
  await call(f.env, 'reactions', 'PUT', { type: 'like' }, { identity: { ...identity, uid: 'other' } });
  assert.deepEqual((await (await call(f.env, 'reactions')).json()).types, [{ type: 'like', count: 2, mine: 1 }]);
});
test('comments are idempotent, reject conflicts, spoofed authors and cross-post parents', async t => {
  const f = fixture(); t.after(f.close);
  const comment = { id: 'c1', text: 'Keep this comment' };
  for (let i = 0; i < 2; i++) assert.equal((await call(f.env, 'comments', 'POST', comment)).status, 200);
  assert.equal((await call(f.env, 'comments', 'POST', { ...comment, text: 'changed' })).status, 409);
  assert.equal((await call(f.env, 'comments', 'POST', comment, { identity: { ...identity, uid: 'other' } })).status, 409);
  assert.equal((await call(f.env, 'comments', 'POST', { id: 'r1', text: 'reply', parentId: 'c1' })).status, 200);
  assert.equal((await call(f.env, 'comments', 'POST', { id: 'r2', text: 'reply', parentId: 'missing' })).status, 400);
  assert.equal((await call(f.env, 'comments', 'POST', { id: 'r3', text: 'reply', uid: 'victim' })).status, 400);
  assert.equal((await call(f.env, 'comments', 'POST', { id: 'r4', text: 'x'.repeat(33000) })).status, 400);
  assert.equal((await call(f.env, 'comments', 'DELETE')).status, 405);
  assert.equal((await call(f.env, 'comments', 'GET', undefined, { query: '?before=invalid' })).status, 400);
  const page = await (await call(f.env, 'comments')).json();
  assert.equal(page.comments.length, 2); assert.ok(page.comments.every(c => c.uid === identity.uid));
});
test('cursor pages preserve equal-timestamp order and bound each read to fifty', async t => {
  const f = fixture(); t.after(f.close);
  const insert = f.sql.prepare('INSERT INTO friendbook_cf_comments VALUES (?,?,?,?,?,?)');
  for (let i = 1; i <= 51; i++) insert.run('posts/p1', `c_${String(i).padStart(3,'0')}`, 'user1', 'text', null, 100);
  const first = await (await call(f.env, 'comments')).json();
  assert.equal(first.comments.length, 50);
  const next = await (await call(f.env, 'comments', 'GET', undefined, { query: '?before=' + encodeURIComponent(first.next) })).json();
  assert.equal(next.comments.length, 1); assert.equal(next.next, null);
  assert.equal(new Set([...first.comments, ...next.comments].map(c => c.id)).size, 51);
});
test('live Firebase authorization enforces privacy, bans, blocks, archives and exact friendship', async () => {
  const string = stringValue => ({ stringValue });
  const list = values => ({ arrayValue: { values: values.map(string) } });
  for (const [privacy, extra, expected] of [['Public', {}, true], ['Only me', {}, false], ['Friends', {}, true],
    ['Friends', { notFriends: true }, false], ['Public', { blocked: true }, false],
    ['Public', { ownerBlocks: true }, false], ['Public', { banned: true }, false],
    ['Public', { archived: true }, false], ['Public', { group: true }, false], ['unknown', {}, false]]) {
    const fetcher = async (url, init) => {
      assert.equal(init.headers.Authorization, 'Bearer test');
      if (url.endsWith(':runQuery')) {
        const q = JSON.parse(init.body).structuredQuery;
        assert.equal(q.limit, 1); assert.equal(q.where.fieldFilter.op, 'IN');
        assert.equal(q.where.fieldFilter.value.arrayValue.values.length, 2);
        return Response.json(extra.notFriends ? [{}] : [{ document: { fields: {} } }]);
      }
      if (url.endsWith('/posts/p1')) return Response.json({ fields: { authorId: string('owner'), privacy: string(privacy),
        ...(extra.archived ? { archived: { booleanValue: true } } : {}), ...(extra.group ? { groupId: string('g1') } : {}) } });
      if (url.endsWith('/users/user1')) return Response.json({ fields: {
        blocked: list(extra.blocked ? ['owner'] : []), banned: { booleanValue: !!extra.banned } } });
      return Response.json({ fields: { blocked: list(extra.ownerBlocks ? ['user1'] : []) } });
    };
    assert.equal(await authorizePost(identity, 'posts', 'p1', fetcher), expected, privacy + JSON.stringify(extra));
  }
  await assert.rejects(authorizePost(identity, 'posts', 'p1', async () => new Response('', { status: 403 })));
});
