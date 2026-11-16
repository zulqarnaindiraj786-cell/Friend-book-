import { test } from 'node:test';
import assert from 'node:assert/strict';
import { fixture } from './fixtures/interaction-db.js';
import { readFileSync } from 'node:fs';
import { interactions } from '../interactions.js';
import { authorizePost } from '../firebase-post-access.js';
import { createWorker } from '../../worker.js';
const identity = { uid: 'user1', project: 'friendbook-17551', token: 'test' };
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
  // A resource the migration has not verified must fail closed and must NOT be
  // self-marked verified by the read path - that is how engagement data was lost.
  assert.equal((await call(f.env, 'comments', 'GET', undefined, { post: 'new-post-1', authorize: async () => true })).status, 503);
  assert.equal(f.sql.prepare("SELECT state FROM friendbook_cf_interaction_resources WHERE resource = 'posts/new-post-1'").get()?.state, undefined);
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
test('media, edits, pins, comment reactions and soft removal stay authorized',async t=>{
 const f=fixture();t.after(f.close);
 const media={id:'media1',text:'',mediaUrl:'https://res.cloudinary.com/demo/image/upload/x.jpg',mentions:[]};
 assert.equal((await call(f.env,'comments','POST',media)).status,200);
 assert.equal((await call(f.env,'comments','POST',{...media,id:'bad',mediaUrl:'http://example.invalid/x'})).status,400);
 const owner=async(_a,_b,_c,_d,ctx)=>{ctx.owner='user1';return true;};
 assert.equal((await call(f.env,'comments','PUT',{op:'edit',id:'media1',text:'edited',mentions:[]})).status,200);
 assert.equal((await call(f.env,'comments','PUT',{op:'edit',id:'media1',text:'stolen'}, {identity:{...identity,uid:'other'}})).status,403);
 assert.equal((await call(f.env,'comments','PUT',{op:'pin',id:'media1',pin:true})).status,403);
 assert.equal((await call(f.env,'comments','PUT',{op:'pin',id:'media1',pin:true},{authorize:owner})).status,200);
 await call(f.env,'comments','PUT',{op:'react',id:'media1',type:'love'});
 const page=await(await call(f.env,'comments')).json();
 assert.equal(page.pinned,'media1');assert.equal(page.comments[0].likes,1);assert.equal(page.comments[0].edited,true);
 assert.deepEqual(page.comments[0].reactions,{user1:'love'});
 const otherPage=await(await call(f.env,'comments','GET',undefined,{identity:{...identity,uid:'other'}})).json();
 assert.deepEqual(otherPage.comments[0].reactions,{});
 assert.equal((await call(f.env,'comments','PUT',{op:'remove',id:'media1'},{identity:{...identity,uid:'other'}})).status,403);
 await call(f.env,'comments','PUT',{op:'remove',id:'media1'});
 const removed=await(await call(f.env,'comments')).json();assert.equal(removed.comments.length,0);assert.equal(removed.pinned,'');
 assert.equal(f.sql.prepare('SELECT text FROM friendbook_cf_comments').get().text,'edited');
 assert.equal((await call(f.env,'comments','POST',media)).status,409);
});
test('reactor pages are bounded to thirty and scoped to resource/comment',async t=>{
 const f=fixture();t.after(f.close);
 const insert=f.sql.prepare('INSERT INTO friendbook_cf_reactions VALUES (?,?,?,?)');
 for(let i=0;i<31;i++)insert.run('posts/p1','u'+String(i).padStart(3,'0'),'like',1);
 const page=await(await call(f.env,'reactors')).json();assert.equal(page.reactors.length,30);
 const next=await(await call(f.env,'reactors','GET',undefined,{query:'?after='+page.next})).json();assert.equal(next.reactors.length,1);
});
test('blockedUsers and private group membership are checked against live source fields',async()=>{
 const s=stringValue=>({stringValue}),a=ids=>({arrayValue:{values:ids.map(s)}});
 for(const [member,blocked,expected] of [[false,false,false],[true,false,true],[true,true,false]]){
  const fetcher=async url=>Response.json({fields:url.endsWith('/posts/p1')?{authorId:s('owner'),privacy:s('Public'),groupId:s('g1')}:
   url.endsWith('/users/user1')?{blockedUsers:a(blocked?['owner']:[])}:
   url.endsWith('/groups/g1')?{privacy:s('Private'),members:a(member?['user1']:[])}:{}});
  assert.equal(await authorizePost(identity,'posts','p1',fetcher,{}),expected);
 }
});


test('unknown historical reaction time is null, not a fabricated epoch date; fresh action becomes known', async t => {
  const f = fixture(); t.after(f.close);
  f.sql.exec("INSERT INTO friendbook_cf_reactions VALUES ('posts/p1','user1','like',0)");
  f.sql.exec("INSERT INTO friendbook_cf_reaction_history VALUES ('posts/p1','user1',0)");
  let page = await (await call(f.env, 'reactors')).json();
  assert.equal(page.reactors[0].createdAt, null);
  assert.equal(page.reactors[0].timestampKnown, 0);
  assert.equal((await call(f.env, 'reactions', 'PUT', {type:'love'})).status, 200);
  page = await (await call(f.env, 'reactors')).json();
  assert.ok(page.reactors[0].createdAt > 0);
  assert.equal(page.reactors[0].timestampKnown, 1);
});

test('known epoch zero is distinct from unknown, and schema upgrade retains existing rows', async t => {
  const f = fixture(); t.after(f.close);
  f.sql.exec("INSERT INTO friendbook_cf_reactions VALUES ('posts/p1','user1','like',0)");
  const page = await (await call(f.env, 'reactors')).json();
  assert.equal(page.reactors[0].createdAt, 0);
  assert.equal(page.reactors[0].timestampKnown, 1);
  f.sql.exec(readFileSync(new URL('../migrations/0005_additive_reaction_history.sql', import.meta.url), 'utf8'));
  assert.equal(f.sql.prepare('SELECT COUNT(*) AS n FROM friendbook_cf_reactions').get().n, 1);
});


test('unknown comment date is not invented; reply pagination still uses stable stored keys', async t => {
  const f = fixture(); t.after(f.close);
  f.sql.exec("INSERT INTO friendbook_cf_comments VALUES ('posts/p1','legacy','user1','original',NULL,0)");
  f.sql.exec("INSERT INTO friendbook_cf_comment_history VALUES ('posts/p1','legacy',0)");
  const page = await (await call(f.env, 'comments')).json();
  assert.equal(page.comments[0].createdAt, null);
  assert.equal(page.comments[0].createdAtKnown, false);
  assert.equal(f.sql.prepare('SELECT created_at FROM friendbook_cf_comments').get().created_at, 0);
});


test('legacy unattributed comment counts survive without fabricated actors and new taps adjust known portion', async t => {
  const f = fixture(); t.after(f.close);
  f.sql.exec("INSERT INTO friendbook_cf_comments VALUES ('posts/p1','legacy','user1','original',NULL,123)");
  f.sql.exec("INSERT INTO friendbook_cf_comment_legacy_aggregates VALUES ('posts/p1','legacy',3,'synthetic/path','synthetic-hash','batch')");
  let page = await (await call(f.env, 'comments')).json();
  assert.equal(page.comments[0].likes,3);
  assert.equal(page.comments[0].historical_unattributed,3);
  let reactors=await (await call(f.env,'reactors','GET',undefined,{query:'?commentId=legacy'})).json();
  assert.deepEqual(reactors.reactors,[]);
  assert.equal(reactors.historicalUnattributed,3);
  let action=await (await call(f.env,'comments','PUT',{id:'legacy',op:'react',type:'like'})).json();
  assert.equal(action.comment.likes,4);
  action=await (await call(f.env,'comments','PUT',{id:'legacy',op:'react',type:null})).json();
  assert.equal(action.comment.likes,3);
  assert.equal(f.sql.prepare('SELECT COUNT(*) AS n FROM friendbook_cf_comment_reactions WHERE type IS NOT NULL').get().n,0);
});
