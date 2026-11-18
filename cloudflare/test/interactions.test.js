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
function authDb(docs, friendship = false) {
  return {
    prepare(sql) {
      return {
        bind(...args) {
          return {
            async first() {
              if (sql.includes('SELECT document_json')) {
                const value = docs[args[0]];
                return value ? { document_json: JSON.stringify(value) } : null;
              }
              if (sql.includes('SELECT path FROM friendbook_cf_documents')) return friendship ? { path: 'friends/user1_owner' } : null;
              return null;
            },
          };
        },
      };
    },
  };
}
test('deployment flag, migration marker, auth and authorization fail closed', async t => {
  const f = fixture(); t.after(f.close);
  assert.equal((await call({}, 'comments')).status, 503);
  assert.equal((await call(f.env, 'comments', 'GET', undefined, { post: 'p2', authorize: () => assert.fail('unverified') })).status, 503);
  assert.equal((await call(f.env, 'comments', 'GET', undefined, { authorize: async () => true })).status, 200);
  assert.equal(f.sql.prepare("SELECT state FROM friendbook_cf_interaction_resources WHERE resource = 'posts/p1'").get()?.state, 'verified');
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
test('legacy root app comments are merged into authorized D1 comment pages without reviving removed rows', async t => {
  const f=fixture(); t.after(f.close);
  await call(f.env,'comments','POST',{id:'same',text:'canonical version'});
  const insert=f.sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)');
  const add=(id,data)=>insert.run(`comments/${id}`,'comments',id,'user2',JSON.stringify(data),100);
  add('same',{id:'same',postId:'p1',authorId:'user2',authorName:'Legacy',content:'stale copy',createdAt:10});
  add('old',{id:'old',postId:'p1',authorId:'user2',authorName:'Legacy friend',content:'historical comment',createdAt:20});
  add('blocked',{id:'blocked',postId:'p1',authorId:'blocked',content:'private from blocked author',createdAt:30});
  const authorize=async (_identity,_collection,_id,_unused,context)=>{context.blocked=['blocked'];return true;};
  const response=await call(f.env,'comments','GET',undefined,{authorize});
  assert.equal(response.status,200);
  const page=await response.json();
  assert.deepEqual(page.comments.map(c=>c.id),['same','old']);
  assert.equal(page.comments.find(c=>c.id==='same').content,'canonical version');
  assert.equal(page.comments.find(c=>c.id==='old').authorName,'Legacy friend');
  assert.equal(f.sql.prepare("SELECT COUNT(*) AS n FROM friendbook_cf_comments WHERE id='old'").get().n,0,
    'read bridge does not bulk-copy or mutate historical records');
});

test('cursor pages preserve equal-timestamp order and bound each read to twenty', async t => {
  const f = fixture(); t.after(f.close);
  const insert = f.sql.prepare('INSERT INTO friendbook_cf_comments VALUES (?,?,?,?,?,?)');
  for (let i = 1; i <= 51; i++) insert.run('posts/p1', `c_${String(i).padStart(3,'0')}`, 'user1', 'text', null, 100);
  const first = await (await call(f.env, 'comments')).json();
  assert.equal(first.comments.length, 20);
  const second = await (await call(f.env, 'comments', 'GET', undefined, { query: '?before=' + encodeURIComponent(first.next) })).json();
  const third = await (await call(f.env, 'comments', 'GET', undefined, { query: '?before=' + encodeURIComponent(second.next) })).json();
  assert.equal(second.comments.length, 20); assert.equal(third.comments.length, 11); assert.equal(third.next, null);
  assert.equal(new Set([...first.comments, ...second.comments, ...third.comments].map(c => c.id)).size, 51);
});
test('D1-only authorization enforces privacy, bans, blocks, archives and friendship without source fetches', async () => {
  for (const [privacy, extra, expected] of [['Public', {}, true], ['Only me', {}, false], ['Friends', {}, true],
    ['Friends', { notFriends: true }, false], ['Public', { blocked: true }, false], ['Public', { ownerBlocks: true }, false],
    ['Public', { banned: true }, false], ['Public', { archived: true }, false], ['Public', { group: true }, false], ['unknown', {}, false]]) {
    const docs = {
      'posts/p1': { authorId: 'owner', privacy, ...(extra.archived ? { archived: true } : {}), ...(extra.group ? { groupId: 'g1' } : {}) },
      'users/user1': { blocked: extra.blocked ? ['owner'] : [], blockedUsers: extra.blocked ? ['owner'] : [], banned: !!extra.banned },
      'users/owner': { blocked: extra.ownerBlocks ? ['user1'] : [] },
      ...(extra.group ? { 'groups/g1': { privacy: 'private', members: [] } } : {}),
    };
    const friendship = privacy === 'Friends' && !extra.notFriends;
    const db = authDb(docs, friendship);
    const noNetwork = async () => { throw new Error('D1 authorization must never call Firebase/Firestore'); };
    assert.equal(await authorizePost({ ...identity, db }, 'posts', 'p1', noNetwork), expected, privacy + JSON.stringify(extra));
  }
});
test('media, edits, pins, comment reactions and soft removal stay authorized', async t => {
  const f = fixture(); t.after(f.close);
  const media = { id:'media1', text:'', mediaUrl:'https://res.cloudinary.com/demo/image/upload/x.jpg', mentions:[] };
  assert.equal((await call(f.env,'comments','POST',media)).status,200);
  assert.equal((await call(f.env,'comments','POST',{...media,id:'bad',mediaUrl:'http://example.invalid/x'})).status,400);
  const owner = async(_a,_b,_c,_d,ctx) => { ctx.owner='user1'; return true; };
  assert.equal((await call(f.env,'comments','PUT',{op:'edit',id:'media1',text:'edited',mentions:[]})).status,200);
  assert.equal((await call(f.env,'comments','PUT',{op:'edit',id:'media1',text:'stolen'}, {identity:{...identity,uid:'other'}})).status,403);
  assert.equal((await call(f.env,'comments','PUT',{op:'pin',id:'media1',pin:true})).status,403);
  assert.equal((await call(f.env,'comments','PUT',{op:'pin',id:'media1',pin:true},{authorize:owner})).status,200);
  await call(f.env,'comments','PUT',{op:'react',id:'media1',type:'love'});
  const page = await (await call(f.env,'comments')).json();
  assert.equal(page.pinned,'media1'); assert.equal(page.comments[0].likes,1); assert.equal(page.comments[0].edited,true);
  assert.deepEqual(page.comments[0].reactions,{user1:'love'});
  const otherPage = await (await call(f.env,'comments','GET',undefined,{identity:{...identity,uid:'other'}})).json();
  assert.deepEqual(otherPage.comments[0].reactions,{});
  assert.equal((await call(f.env,'comments','PUT',{op:'remove',id:'media1'},{identity:{...identity,uid:'other'}})).status,403);
  await call(f.env,'comments','PUT',{op:'remove',id:'media1'});
  const removed = await (await call(f.env,'comments')).json(); assert.equal(removed.comments.length,0); assert.equal(removed.pinned,'');
  assert.equal(f.sql.prepare('SELECT text FROM friendbook_cf_comments').get().text,'edited');
  assert.equal((await call(f.env,'comments','POST',media)).status,409);
});
test('reactor pages are bounded to thirty and scoped to resource/comment', async t => {
  const f = fixture(); t.after(f.close);
  const insert = f.sql.prepare('INSERT INTO friendbook_cf_reactions VALUES (?,?,?,?)');
  for (let i=0;i<31;i++) insert.run('posts/p1','u'+String(i).padStart(3,'0'),'like',1);
  const page = await (await call(f.env,'reactors')).json(); assert.equal(page.reactors.length,30);
  const next = await (await call(f.env,'reactors','GET',undefined,{query:'?after='+page.next})).json(); assert.equal(next.reactors.length,1);
});
test('blocked users and private group membership are checked from D1 only', async () => {
  for (const [member, blocked, expected] of [[false,false,false],[true,false,true],[true,true,false]]) {
    const docs = { 'posts/p1': { authorId:'owner', privacy:'Public', groupId:'g1' },
      'users/user1': { blockedUsers: blocked ? ['owner'] : [] }, 'users/owner': { displayName: 'Owner' },
      'groups/g1': { privacy:'Private', members: member ? ['user1'] : [] } };
    const db = authDb(docs);
    const noNetwork = async () => { throw new Error('unexpected source fetch'); };
    assert.equal(await authorizePost({...identity,db},'posts','p1',noNetwork,{}),expected);
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
