import { test } from 'node:test';
import assert from 'node:assert/strict';
import { fixture } from './fixtures/interaction-db.js';
import { readFileSync } from 'node:fs';
import { interactions, engagementSummary, batchEngagementReads } from '../interactions.js';
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
              if (sql.includes('friendbook_cf_trusted_friendships')) return friendship ? { friend_path: 'friends/verified' } : null;
              if (sql.includes('friendbook_cf_trusted_groups')) return docs[args[0]]?.adminId ? {owner_uid:docs[args[0]].adminId} : null;
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
test('engagement summary binds the comment actor to parameter one, not an unbound fourth parameter', async t => {
  const f=fixture(); t.after(f.close);
  const db=f.env.DB; const prepare=db.prepare; let summarySql='';
  db.prepare=function(sql){if(sql.includes('AS preview_json'))summarySql=sql;return prepare.call(this,sql);};
  assert.equal((await call(f.env,'reactions')).status,200);
  assert.match(summarySql,/r\.uid=\?1\)/);
  assert.doesNotMatch(summarySql,/\?(?![0-9])/,'D1 requires exactly three explicitly numbered bindings');
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
test('compact reaction writes acknowledge within the batch without reading comments', async t => {
  const f=fixture(); t.after(f.close);
  const db=f.env.DB, prepare=db.prepare;
  db.prepare=function(sql){assert.doesNotMatch(sql,/comment/i);return prepare.call(this,sql);};
  const options={query:'?response=reaction'};
  for(const [index,type] of ['like','like',null].entries()) {
    const before=f.sql.prepare('SELECT total_changes() n').get().n;
    const response=await call(f.env,'reactions','PUT',{type},options);
    assert.equal(response.status,200);
    assert.deepEqual(await response.json(),{reactionOnly:true,types:type?[{type,count:1,mine:1}]:[]});
    if(index===1) assert.equal(f.sql.prepare('SELECT total_changes() n').get().n,before);
  }
  assert.equal((await call(f.env,'reactions','PUT',{type:'like'},{...options,post:'p2'})).status,503);
  assert.equal((await call(f.env,'reactions','PUT',{type:'like'},{...options,authorize:async()=>false})).status,403);
});
test('five ordered quick-tap targets leave one Like and the correct persisted count', async t => {
  const f=fixture(); t.after(f.close);
  const options={query:'?response=reaction'};
  for(const type of ['like',null,'like',null,'like']) {
    const result=await call(f.env,'reactions','PUT',{type},options);
    assert.equal(result.status,200);
    assert.deepEqual((await result.json()).types,type?[{type,count:1,mine:1}]:[]);
  }
  assert.deepEqual((await (await call(f.env,'reactions','GET')).json()).types,[{type:'like',count:1,mine:1}]);
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
  add('wrapped',{fields:{postId:{stringValue:'p1'},authorId:{stringValue:'user2'},content:{stringValue:'wrapped comment'},createdAt:{integerValue:'25'}}});
  add('deleted',{postId:'p1',authorId:'user2',content:'deleted',removed:true});
  const authorize=async (_identity,_collection,_id,_unused,context)=>{context.blocked=['blocked'];return true;};
  const response=await call(f.env,'comments','GET',undefined,{authorize});
  assert.equal(response.status,200);
  const page=await response.json();
  assert.deepEqual(page.comments.map(c=>c.id),['same','wrapped','old']);
  const summary=await (await call(f.env,'reactions','GET',undefined,{authorize})).json();
  assert.equal(summary.comments,3,'feed count includes visible legacy comments but not blocked, deleted or shadowed records');
  assert.deepEqual(summary.preview.map(c=>c.id),['same','wrapped'],'preview merges canonical and visible historical comments in creation order');
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
test('explicit ten-comment pages preserve cursor and never send the lookahead row', async t=>{
  const f=fixture();t.after(f.close);
  const insert=f.sql.prepare('INSERT INTO friendbook_cf_comments VALUES (?,?,?,?,?,?)');
  for(let i=0;i<22;i++)insert.run('posts/p1',`ten_${i}`,'user1','text',null,100+i);
  const ids=[];let cursor=null;
  for(const length of [10,10,2]){
    const query='?limit=10'+(cursor?'&before='+encodeURIComponent(cursor):'');
    const page=await (await call(f.env,'comments','GET',undefined,{query})).json();
    assert.equal(page.comments.length,length);ids.push(...page.comments.map(c=>c.id));cursor=page.next;
  }
  assert.equal(cursor,null);assert.equal(new Set(ids).size,22);
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
  const before=f.sql.prepare('SELECT total_changes() n').get().n;
  assert.equal((await call(f.env,'comments','PUT',{op:'edit',id:'media1',text:'edited',mentions:[]})).status,200);
  assert.equal((await call(f.env,'comments','PUT',{op:'pin',id:'media1',pin:true},{authorize:owner})).status,200);
  assert.equal((await call(f.env,'comments','PUT',{op:'react',id:'media1',type:'love'})).status,200);
  assert.equal(f.sql.prepare('SELECT total_changes() n').get().n,before);
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
      'groups/g1': { adminId:'owner', privacy:'Private', members: member ? ['user1'] : [] } };
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
  assert.equal((await call(f.env, 'reactions', 'PUT', {type:'like'})).status, 200);
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

test('reaction and comment totals reflect writes from other isolates and recheck migration status', async t => {
  const f=fixture();t.after(f.close);
  const first=await (await call(f.env,'reactions')).json();
  assert.deepEqual(first.types,[]);assert.equal(first.comments,0);
  // A different isolate/device writes directly to the same D1 database.
  f.sql.prepare('INSERT INTO friendbook_cf_reactions VALUES (?,?,?,?)').run('posts/p1','other','like',100);
  f.sql.prepare('INSERT INTO friendbook_cf_comments VALUES (?,?,?,?,?,?)').run('posts/p1','external','other','hello',null,100);
  const updated=await (await call(f.env,'reactions')).json();
  assert.deepEqual(updated.types,[{type:'like',count:1,mine:0}]);assert.equal(updated.comments,1);
  f.sql.prepare("UPDATE friendbook_cf_interaction_resources SET state='staging' WHERE resource='posts/p1'").run();
  assert.equal((await call(f.env,'reactions')).status,503);
});

test('legacy comment count uses the lookup indexes rather than scanning all root comments', async t => {
  const f=fixture();t.after(f.close);
  f.sql.exec(readFileSync(new URL('../migrations/0032_legacy_comment_lookup.sql',import.meta.url),'utf8'));
  const {legacyCommentCount}=await import('../comment-api.js');
  let plan='';const db={prepare(query){const stmt=f.sql.prepare(query);return{bind(...args){plan=f.sql.prepare('EXPLAIN QUERY PLAN '+query).all(...args).map(row=>row.detail).join('\n');return{first:async()=>stmt.get(...args)||null};}};}};
  assert.equal(await legacyCommentCount(db,'posts/p1',[]),0);
  for(const name of ['idx_cf_comment_lookup_post','idx_cf_comment_lookup_post_legacy','idx_cf_comment_lookup_resource'])assert.match(plan,new RegExp(name));
  assert.match(plan,/SEARCH a USING INDEX sqlite_autoindex_friendbook_cf_documents_1 \(path=\?\)/);
});

test('grouped legacy counts match individual indexed counts, shadowing and blocked authors', async t => {
  const f=fixture();t.after(f.close);
  f.sql.exec(readFileSync(new URL('../migrations/0032_legacy_comment_lookup.sql',import.meta.url),'utf8'));
  const {legacyCommentCount,legacyCommentCountsStatement}=await import('../comment-api.js');
  const add=f.sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)');
  const row=(id,data)=>add.run(`comments/${id}`,'comments',id,'',JSON.stringify(data),1);
  row('one',{postId:'p0',authorId:'alice'});
  row('two',{post_id:'p0',uid:'bob'});
  row('blocked',{resource:'posts/p0',userId:'blocked'});
  row('deleted',{postId:'p0',authorId:'alice',deleted:true});
  row('shadow',{postId:'p1',authorId:'alice'});
  row('wrapped',{fields:{postId:{stringValue:'p1'},authorId:{stringValue:'alice'}}});
  row('same',{postId:'p2',post_id:'p2',resource:'posts/p2',authorId:'alice'});
  f.sql.prepare('INSERT INTO friendbook_cf_comments VALUES (?,?,?,?,?,?)').run('posts/p1','shadow','alice','text',null,1);
  const resources=Array.from({length:20},(_,i)=>`posts/p${i}`),blocked=['blocked','blocked'];
  const expected=await Promise.all(resources.map(resource=>legacyCommentCount(f.env.DB,resource,blocked)));
  const grouped=(await legacyCommentCountsStatement(f.env.DB,resources,blocked).all()).results.map(row=>row.n);
  assert.deepEqual(grouped,expected);assert.deepEqual(grouped.slice(0,3),[2,1,1]);
  // All three legacy selectors still use their expression indexes for each branch.
  const prepare=f.env.DB.prepare;let plans=[],bindCount=0,queryLength=0;
  const db={prepare(query){return{bind(...args){bindCount=args.length;queryLength=query.length;
    plans=f.sql.prepare('EXPLAIN QUERY PLAN '+query).all(...args).map(row=>row.detail);
    return prepare.call(f.env.DB,query).bind(...args);
  }};}};
  await legacyCommentCountsStatement(db,resources,blocked).all();
  assert.equal(bindCount,41);assert.ok(queryLength<100000);
  for(const name of ['idx_cf_comment_lookup_post','idx_cf_comment_lookup_post_legacy','idx_cf_comment_lookup_resource'])assert.match(plans.join('\n'),new RegExp(name));
  assert.equal((plans.join('\n').match(/SEARCH a USING INDEX sqlite_autoindex_friendbook_cf_documents_1/g)||[]).length,20);
});

test('legacy D1 comments remain readable and support authorized replies and reactions without overwriting sources', async t => {
  const f=fixture();t.after(f.close);
  const original={postId:'p1',authorId:'user2',content:'old comment',createdAt:12,likes:3,reactions:{user2:'love'},likedBy:['user2']};
  const raw=JSON.stringify(original);
  f.sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)').run('comments/old','comments','old','user2',raw,1);
  const single=await call(f.env,'comments','GET',undefined,{query:'?id=old'});
  assert.equal(single.status,200);assert.equal((await single.json()).comment.content,'old comment');
  const reactors=await (await call(f.env,'reactors','GET',undefined,{query:'?commentId=old'})).json();
  assert.deepEqual(reactors.reactors,[{uid:'user2',type:'love'}]);assert.equal(reactors.historicalUnattributed,2);
  assert.equal((await call(f.env,'comments','PUT',{op:'edit',id:'old',text:'spoof',mentions:[]})).status,403);
  assert.equal(f.sql.prepare('SELECT COUNT(*) n FROM friendbook_cf_comments').get().n,0,'denied edits do not promote the source');
  assert.equal((await call(f.env,'comments','POST',{id:'old',text:'shadow'})).status,409);
  const reply=await call(f.env,'comments','POST',{id:'reply',text:'hello',parentId:'old'});
  assert.equal(reply.status,200);assert.equal((await reply.json()).replyTo,'user2');
  assert.equal(f.sql.prepare("SELECT document_json FROM friendbook_cf_documents WHERE path='comments/old'").get().document_json,raw);
  assert.equal((await call(f.env,'comments','PUT',{op:'react',id:'old',type:'like'})).status,200);
  const after=await (await call(f.env,'comments','GET',undefined,{query:'?id=old'})).json();
  assert.equal(after.comment.likes,4);assert.equal(after.comment.reactions.user1,'like');
  assert.equal(f.sql.prepare("SELECT unattributed_count n FROM friendbook_cf_comment_legacy_aggregates WHERE id='old'").get().n,2);
  assert.equal((await (await call(f.env,'reactions')).json()).comments,2,'promotion does not double-count legacy and canonical records');
  const owner=async(_a,_b,_c,_d,ctx)=>{ctx.owner='user1';return true;};
  assert.equal((await call(f.env,'comments','PUT',{op:'remove',id:'old'},{authorize:owner})).status,200);
  assert.equal((await call(f.env,'comments','GET',undefined,{query:'?id=old'})).status,404);
  assert.equal((await (await call(f.env,'comments')).json()).comments.some(c=>c.id==='old'),false,'removed canonical tombstone suppresses old source');
});

test('legacy promotion fails safely for blocked or cyclic parents and changed source', async t => {
 const f=fixture();t.after(f.close);const add=(id,doc)=>f.sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)').run(`comments/${id}`,'comments',id,'user2',JSON.stringify(doc),1);
 add('blocked',{postId:'p1',authorId:'blocked',content:'x'});add('a',{postId:'p1',authorId:'user2',content:'a',parentId:'b'});add('b',{postId:'p1',authorId:'user2',content:'b',parentId:'a'});
 const auth=async(_a,_b,_c,_d,ctx)=>{ctx.blocked=['blocked'];return true;};
 assert.equal((await call(f.env,'comments','PUT',{op:'react',id:'blocked',type:'like'},{authorize:auth})).status,404);
 assert.equal((await call(f.env,'comments','POST',{id:'new',text:'reply',parentId:'a'},{authorize:auth})).status,400);
 assert.equal(f.sql.prepare('SELECT COUNT(*) n FROM friendbook_cf_comments').get().n,0);
 add('change',{postId:'p1',authorId:'user2',content:'old'});
 const originalBatch=f.env.DB.batch;let race=true;
 f.env.DB.batch=async entries=>{if(race){race=false;f.sql.prepare("UPDATE friendbook_cf_documents SET document_json=? WHERE path='comments/change'").run(JSON.stringify({postId:'p1',authorId:'user2',content:'new'}));}return originalBatch(entries);};
 assert.equal((await call(f.env,'comments','PUT',{op:'react',id:'change',type:'like'})).status,503);
 assert.equal(f.sql.prepare("SELECT COUNT(*) n FROM friendbook_cf_comments WHERE id='change'").get().n,0);
 assert.equal((await call(f.env,'comments','PUT',{op:'react',id:'change',type:'like'})).status,200);
 assert.equal((await (await call(f.env,'comments','GET',undefined,{query:'?id=change'})).json()).comment.content,'new');
});

test('legacy comment cursor does not drop records beyond first thousand storage updates', async t=>{
 const f=fixture();t.after(f.close);
 const insert=f.sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)');
 for(let i=0;i<1201;i++){
  const id='old_'+String(i).padStart(4,'0');
  // The last storage row is actually the newest comment; all other times tie.
  insert.run(`comments/${id}`,'comments',id,'user2',JSON.stringify({postId:'p1',authorId:'user2',content:id,createdAt:i===0?200:100}),i);
 }
 const first=await (await call(f.env,'comments')).json();assert.equal(first.comments.length,20);assert.equal(first.comments[0].id,'old_0000');
 assert.equal(first.comments[1].id,'old_1200');
 const second=await (await call(f.env,'comments','GET',undefined,{query:'?before='+encodeURIComponent(first.next)})).json();
 assert.equal(second.comments.length,20);assert.equal(new Set([...first.comments,...second.comments].map(c=>c.id)).size,40);
 assert.equal(second.comments[0].id,'old_1181');
});

test('Home returns authorized reaction state and comment previews with the bounded post page', async t => {
  const f=fixture();t.after(f.close);
  f.sql.exec(readFileSync(new URL('../migrations/0014_additive_firestore_documents.sql', import.meta.url),'utf8'));
  f.sql.exec(readFileSync(new URL('../migrations/0051_feed_exception_indexes.sql', import.meta.url),'utf8'));
  const doc=f.sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
  for(const uid of ['user1','author','other']) doc.run(`users/${uid}`,'users','users',uid,uid,JSON.stringify({uid,...(uid==='user1'?{blocked:['other']}:{})}),1,1);
  for(const [id,owner,privacy] of [['p1','author','public'],['p2','author','public'],['secret','author','only me']]) {
    doc.run(`posts/${id}`,'posts','posts',id,owner,JSON.stringify({authorId:owner,privacy,createdAt:1}),1,1);
  }
  const payload=JSON.stringify({authorId:'author',privacy:'public',createdAt:1,mediaUrl:'https://example.test/photo.webp',
    commentsMap:{old:{content:'historical long text '.repeat(500)}},reactionsMap:{legacy:{type:'like'}}});
  f.sql.prepare("UPDATE friendbook_cf_firestore_documents SET document_json=? WHERE path='posts/p1'").run(payload);
  f.sql.prepare("UPDATE friendbook_cf_firestore_documents SET document_json=? WHERE path='posts/p2'").run(payload);
  await call(f.env,'reactions','PUT',{type:'love'});
  const insert=f.sql.prepare('INSERT INTO friendbook_cf_comments(resource,id,uid,text,created_at) VALUES (?,?,?,?,?)');
  insert.run('posts/p1','first','author','first visible',1);
  insert.run('posts/p1','second','other','blocked content',2);
  insert.run('posts/p1','third','author','removed content',3);
  f.sql.exec("INSERT INTO friendbook_cf_comment_details(resource,id,removed) VALUES('posts/p1','third',1)");
  assert.equal(f.sql.prepare("SELECT total FROM friendbook_cf_visible_comment_totals WHERE resource='posts/p1'").get().total,2);
  const worker=createWorker(async token=>token);
  const get=(uid,limit)=>worker.fetch(new Request('https://example.test/v1/firestore/query',{method:'POST',headers:{authorization:`Bearer ${uid}`,'content-type':'application/json'},body:JSON.stringify({parent:'posts',feedEngagement:true,limit,orderBy:[{field:'createdAt',direction:'DESCENDING'}]})}),{...f.env,FIREBASE_PROJECT_ID:'test'});
  // Android Home requests twenty posts both for the head and older pages.
  const r=await get('user1',20);assert.equal(r.status,200,await r.clone().text());
  const {documents}=await r.json();
  assert.deepEqual(documents.map(d=>d.id),['p2','p1']);
  assert.equal(documents[0].engagement,undefined);
  assert.ok(documents[0].data.commentsMap.old.content);
  assert.equal(documents[1].data.commentsMap,undefined);
  assert.equal(documents[1].data.reactionsMap,undefined);
  assert.equal(documents[1].data.mediaUrl,'https://example.test/photo.webp');
  assert.ok(Buffer.byteLength(JSON.stringify(documents[1])) < Buffer.byteLength(payload)/5);
  assert.ok(JSON.parse(f.sql.prepare("SELECT document_json FROM friendbook_cf_firestore_documents WHERE path='posts/p1'").get().document_json).commentsMap);
  assert.deepEqual(documents[1].engagement.types,[{type:'love',count:1,mine:1}]);
  assert.equal(documents[1].engagement.comments,1);
  assert.deepEqual(documents[1].engagement.preview.map(c=>c.id),['first']);
  const another=await (await get('author',7)).json();
  assert.equal(another.documents.find(d=>d.id==='p1').engagement.types[0].mine,0);
  assert.ok((await (await get('user1',21)).json()).documents.every(d=>!d.engagement));
  f.sql.exec("UPDATE friendbook_cf_comment_details SET removed=0 WHERE resource='posts/p1' AND id='third'");
  assert.equal(f.sql.prepare("SELECT total FROM friendbook_cf_visible_comment_totals WHERE resource='posts/p1'").get().total,3);
});

test('a full 20-post Feed page batches engagement without individual summary requests', async t=>{
  const f=fixture();t.after(f.close);
  const resources=Array.from({length:20},(_,i)=>`posts/page-${i}`);
  let batchCalls=0;
  const db={...f.env.DB,async batch(statements){
    batchCalls++;
    assert.equal(statements.length,21);
    return f.env.DB.batch(statements);
  }};
  const result=await batchEngagementReads(db,resources,'user1',[]);
  assert.equal(batchCalls,1);
  assert.equal(result.size,20);
});

test('grouped Feed engagement equals individual summaries with blocked and legacy comments', async t=>{
  const f=fixture();t.after(f.close);
  f.sql.exec(readFileSync(new URL('../migrations/0032_legacy_comment_lookup.sql',import.meta.url),'utf8'));
  const add=f.sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)');
  for(const [id,post,author] of [['a','p1','allowed'],['b','p1','blocked'],['c','p2','allowed']]) {
    add.run(`comments/${id}`,'comments',id,author,JSON.stringify({postId:post,authorId:author,content:id,createdAt:10}),1);
  }
  f.sql.prepare('INSERT INTO friendbook_cf_comments VALUES (?,?,?,?,?,?)').run('posts/p1','new','allowed','current',null,20);
  const resources=['posts/p1','posts/p2'],blocked=['blocked'];
  const preloaded=await batchEngagementReads(f.env.DB,resources,'user1',blocked);
  assert.equal(preloaded.size,2);
  for(const resource of resources) {
    const grouped=await engagementSummary(f.env.DB,resource,'user1','owner',blocked,preloaded.get(resource));
    const individual=await engagementSummary(f.env.DB,resource,'user1','owner',blocked);
    assert.deepEqual(grouped,individual);
  }
});

test('post detail returns only newer comments, stable continuation and reaction totals', async t=>{
  const f=fixture();t.after(f.close);
  const insert=f.sql.prepare('INSERT INTO friendbook_cf_comments VALUES (?,?,?,?,?,?)');
  for(let i=0;i<15;i++)insert.run('posts/p1',`c${String(i).padStart(2,'0')}`,'user1','text',null,100);
  assert.equal((await call(f.env,'reactions','PUT',{type:'like'})).status,200);
  const after=btoa(JSON.stringify([100,'c01']));
  const response=await call(f.env,'comments','GET',undefined,{query:`?limit=10&includeReactions=1&after=${encodeURIComponent(after)}`});
  assert.equal(response.status,200);
  const first=await response.json();
  assert.equal(first.delta,true);
  assert.deepEqual(first.comments.map(c=>c.id),Array.from({length:10},(_,i)=>`c${String(i+2).padStart(2,'0')}`));
  assert.deepEqual(first.reactions,[{type:'like',count:1,mine:1}]);
  const last=await (await call(f.env,'comments','GET',undefined,{query:`?limit=10&after=${encodeURIComponent(first.next)}`})).json();
  assert.deepEqual(last.comments.map(c=>c.id),['c12','c13','c14']);
  assert.equal(last.next,null);
  assert.equal((await call(f.env,'comments','GET',undefined,{query:`?before=${after}&after=${after}`})).status,400);
});


test('engagement subtracts blocked comments using author seeks without double counting', async t=>{
  const f=fixture();t.after(f.close);
  f.sql.exec(`WITH RECURSIVE seq(i) AS (VALUES(1) UNION ALL SELECT i+1 FROM seq WHERE i<2000)
    INSERT INTO friendbook_cf_comments(resource,id,uid,text,created_at)
    SELECT 'posts/p1', 'c'||i, CASE WHEN i<=2 THEN 'blocked' ELSE 'allowed' END, 'text',i FROM seq;
    INSERT INTO friendbook_cf_comment_details(resource,id,removed) VALUES('posts/p1','c1',1);`);
  const plans=[];
  const db={prepare(query){
    if(!plans.length && query.includes('CROSS JOIN friendbook_cf_comments')) {
      const literal=query.replaceAll('?1',"'viewer'").replaceAll('?2',"'posts/p1'").replaceAll('?3',"'[\"blocked\",\"blocked\"]'");
      plans.push(...f.sql.prepare('EXPLAIN QUERY PLAN '+literal).all().map(row=>row.detail));
    }
    return f.env.DB.prepare(query);
  }};
  const summarize=()=>engagementSummary(db,'posts/p1','viewer','owner',['blocked','blocked']);
  assert.equal((await summarize()).comments,1998);
  assert.ok(plans.some(plan=>/SEARCH c USING INDEX idx_cf_comment_resource_author \(resource=\? AND uid=\?\)/.test(plan)),plans.join('\n'));
  f.sql.exec('DROP INDEX idx_cf_comment_resource_author');
  assert.equal((await summarize()).comments,1998,'old schemas retain the compatible path');
});
