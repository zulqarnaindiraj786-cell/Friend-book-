import { resumeOfficialBroadcasts } from '../firestore-documents.js';
import { drainBroadcastPush } from '../fcm-push.js';
import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import { createWorker } from '../../worker.js';
import { hasD1Friendship } from '../app-documents.js';

const resourcesSchema = readFileSync(new URL('../migrations/0003_additive_interactions.sql', import.meta.url), 'utf8');
const schema = readFileSync(new URL('../migrations/0014_additive_firestore_documents.sql', import.meta.url), 'utf8');
const socialGuardSchema = readFileSync(new URL('../migrations/0018_social_guards.sql', import.meta.url), 'utf8');
const appSchema = readFileSync(new URL('../migrations/0011_additive_app_documents.sql', import.meta.url), 'utf8');
const mirrorMigration = readFileSync(new URL('../migrations/0017_sync_firestore_app_documents.sql', import.meta.url), 'utf8');

test('Firestore-compatible D1 endpoint accepts Firebase UIDs containing x', async () => {
  const sql = new DatabaseSync(':memory:');
  sql.exec(resourcesSchema);
  sql.exec(appSchema);
  sql.exec(schema);
  sql.exec(mirrorMigration);
  sql.exec(socialGuardSchema);
  const db = {
    prepare(query) {
      const stmt = sql.prepare(query);
      return {
        bind(...args) {
          return {
            run: async () => stmt.run(...args),
            all: async () => ({results: stmt.all(...args)}),
            first: async () => stmt.get(...args) || null,
          };
        },
      };
    },
    async batch(statements) {
      sql.exec('BEGIN');
      try { for (const stmt of statements) await stmt.run(); sql.exec('COMMIT'); }
      catch (error) { sql.exec('ROLLBACK'); throw error; }
    },
  };
  const uid = 'FF3hGwZJlTMp9r4q2eTAXbgsQMx2';
  const app = createWorker(async () => uid);
  const response = await app.fetch(new Request(`https://example.test/v1/firestore/document?path=users/${uid}`, {
    method: 'PUT',
    headers: {authorization: 'Bearer proof-token', 'content-type': 'application/json'},
    body: JSON.stringify({data: {uid, displayName: 'D1 proof', proofMarker: 'd1-only'}}),
  }), {DB: db, FIREBASE_PROJECT_ID: 'friendbook-test'});
  assert.equal(response.status, 200, await response.text());
  const row = sql.prepare('SELECT path, owner_uid, document_json FROM friendbook_cf_firestore_documents WHERE path = ?').get(`users/${uid}`);
  assert.equal(row.path, `users/${uid}`);
  assert.equal(row.owner_uid, uid);
  assert.match(row.document_json, /d1-only/);
  const read = await app.fetch(new Request(`https://example.test/v1/firestore/document?path=users/${uid}`, {
    headers: {authorization: 'Bearer proof-token'},
  }), {DB: db, FIREBASE_PROJECT_ID: 'friendbook-test'});
  assert.equal(read.status, 200);
  const document = await read.json();
  assert.equal(document.exists, true);
  assert.equal(document.data.displayName, 'D1 proof');
  const patch = await app.fetch(new Request(`https://example.test/v1/firestore/document?path=users/${uid}`, {
    method:'PATCH', headers:{authorization:'Bearer proof-token','content-type':'application/json'},
    body:JSON.stringify({fields:{displayName:'Patched name'}}),
  }), {DB:db,FIREBASE_PROJECT_ID:'friendbook-test'});
  assert.equal(patch.status,200,await patch.text());
  assert.equal(JSON.parse(sql.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?').get(`users/${uid}`).document_json).displayName,'Patched name');
  const legacyPatch = await app.fetch(new Request(`https://example.test/v1/firestore/document?path=users/${uid}`, {
    method:'PATCH', headers:{authorization:'Bearer proof-token','content-type':'application/json'},
    body:JSON.stringify({updates:{displayName:'Patched legacy payload'}}),
  }), {DB:db,FIREBASE_PROJECT_ID:'friendbook-test'});
  assert.equal(legacyPatch.status,200);
  assert.equal(JSON.parse(sql.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?').get(`users/${uid}`).document_json).displayName,'Patched legacy payload');
  const appRow = sql.prepare('SELECT path, owner_uid, document_json FROM friendbook_cf_documents WHERE path = ?').get(`users/${uid}`);
  assert.equal(appRow.owner_uid, uid);
  assert.match(appRow.document_json, /d1-only/);
  assert.match(appRow.document_json, /Patched legacy payload/);
  const list = await app.fetch(new Request('https://example.test/v1/docs?collection=users&limit=10', {
    headers: {authorization: 'Bearer proof-token'},
  }), {DB: db, FIREBASE_PROJECT_ID: 'friendbook-test'});
  assert.equal(list.status, 200);
  assert.ok((await list.json()).documents.some(doc => doc.id === uid && doc.proofMarker === 'd1-only'));
  const createOnly = await app.fetch(new Request(`https://example.test/v1/firestore/document?path=users/${uid}`, {
    method:'PUT', headers:{authorization:'Bearer proof-token','content-type':'application/json'},
    body:JSON.stringify({data:{uid,displayName:'forged replacement'},createOnly:true}),
  }), {DB:db,FIREBASE_PROJECT_ID:'friendbook-test'});
  assert.equal(createOnly.status,409);
  assert.match(sql.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?').get(`users/${uid}`).document_json,/Patched legacy payload/);
});

test('Firestore document reads fall back to existing D1 app documents for root profiles and posts', async () => {
  const sql = new DatabaseSync(':memory:');
  sql.exec(resourcesSchema);
  sql.exec(appSchema);
  sql.exec(schema);
  sql.prepare(`INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)`)
    .run('users/new_uid','users','new_uid','new_uid',JSON.stringify({uid:'new_uid',displayName:'New account'}),1234);
  const db = { prepare(query) { const stmt = sql.prepare(query); return { bind(...args) {
    return { run: async () => stmt.run(...args), all: async () => ({results: stmt.all(...args)}), first: async () => stmt.get(...args) || null };
  } }; } };
  const app = createWorker(async () => 'new_uid');
  const profile = await app.fetch(new Request('https://example.test/v1/firestore/document?path=users/new_uid', {
    headers: {authorization:'Bearer test-token'},
  }), {DB:db,FIREBASE_PROJECT_ID:'friendbook-test'});
  assert.equal(profile.status,200);
  const profileDoc = await profile.json();
  assert.equal(profileDoc.exists, true);
  assert.equal(profileDoc.data.displayName,'New account');
  sql.close();
});

test('Firestore collection queries and counts include historical root app documents without duplicates', async () => {
  const sql = new DatabaseSync(':memory:');
  sql.exec(resourcesSchema);
  sql.exec(appSchema);
  sql.exec(schema);
  const addApp = sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)');
  addApp.run('users/u1','users','u1','u1',JSON.stringify({uid:'u1',displayName:'User'}),1);
  addApp.run('posts/old1','posts','old1','u1',JSON.stringify({authorId:'u1',content:'legacy post',privacy:'public',createdAt:10}),10);
  addApp.run('posts/shared','posts','shared','u1',JSON.stringify({authorId:'u1',content:'stale copy',privacy:'public',createdAt:20}),20);
  sql.prepare(`INSERT INTO friendbook_cf_firestore_documents
    (path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time)
    VALUES (?,?,?,?,?,?,?,?)`).run('posts/shared','posts','posts','shared','u1',JSON.stringify({authorId:'u1',content:'canonical copy',privacy:'public',createdAt:21}),21,21);
  const db = { prepare(query) { const stmt = sql.prepare(query); return { bind(...args) {
    return { run: async () => stmt.run(...args), all: async () => ({results: stmt.all(...args)}), first: async () => stmt.get(...args) || null };
  } }; } };
  const app = createWorker(async () => 'u1');
  const query = await app.fetch(new Request('https://example.test/v1/firestore/query', {
    method:'POST', headers:{authorization:'Bearer token','content-type':'application/json'},
    body:JSON.stringify({parent:'posts',filters:[{field:'privacy',op:'==',value:'public'}],orderBy:[{field:'createdAt',direction:'ASCENDING'}],limit:10}),
  }), {DB:db,FIREBASE_PROJECT_ID:'friendbook-test',D1_INTERACTIONS_ENABLED:'false'});
  assert.equal(query.status,200);
  const documents=(await query.json()).documents;
  assert.deepEqual(documents.map(d=>d.id),['old1','shared']);
  assert.equal(documents.find(d=>d.id==='shared').data.content,'canonical copy');
  const count = await app.fetch(new Request('https://example.test/v1/firestore/count?parent=posts&filters='+encodeURIComponent(JSON.stringify([{field:'privacy',op:'==',value:'public'}])), {
    headers:{authorization:'Bearer token'},
  }), {DB:db,FIREBASE_PROJECT_ID:'friendbook-test'});
  assert.equal(count.status,200);
  assert.equal((await count.json()).count,2);
  sql.close();
});

test('Firestore-style query cursors preserve equal-timestamp ordering across pages', async () => {
  const sql=new DatabaseSync(':memory:');
  sql.exec(resourcesSchema); sql.exec(appSchema); sql.exec(schema);
  const add=sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)');
  add.run('users/u1','users','u1','u1',JSON.stringify({uid:'u1',displayName:'User'}),1);
  for (const id of ['a','b','c']) add.run(`posts/${id}`,'posts',id,'u1',JSON.stringify({authorId:'u1',createdAt:100}),100);
  add.run('posts/d','posts','d','u1',JSON.stringify({authorId:'u1',createdAt:90}),90);
  const db={prepare(query){const stmt=sql.prepare(query);return{bind(...args){return{run:async()=>stmt.run(...args),all:async()=>({results:stmt.all(...args)}),first:async()=>stmt.get(...args)||null};}};}};
  const app=createWorker(async()=> 'u1');
  const query=(cursor)=>new Request('https://example.test/v1/firestore/query',{method:'POST',headers:{authorization:'Bearer token','content-type':'application/json'},body:JSON.stringify({parent:'posts',orderBy:[{field:'createdAt',direction:'DESCENDING'}],limit:2,...(cursor?{startAfter:cursor}:{})})});
  add.run('posts/other','posts','other','u1',JSON.stringify({authorId:'u2',createdAt:200}),200);
  add.run('users/u2','users','u2','u2',JSON.stringify({displayName:'Other'}),1);
  sql.exec(readFileSync(new URL('../migrations/0044_profile_post_page_indexes.sql',import.meta.url),'utf8'));
  const authorPage=await app.fetch(new Request('https://example.test/v1/firestore/query',{method:'POST',headers:{authorization:'Bearer token','content-type':'application/json'},body:JSON.stringify({parent:'posts',filters:[{field:'authorId',op:'==',value:'u1'}],orderBy:[{field:'createdAt',direction:'DESCENDING'}],limit:2})}),{DB:db,FIREBASE_PROJECT_ID:'friendbook-test'});
  assert.equal(authorPage.status,200);
  assert.deepEqual((await authorPage.json()).documents.map(d=>d.id),['c','b']);
  sql.exec("DELETE FROM friendbook_cf_documents WHERE path='posts/other'");
  const first=await app.fetch(query(),{DB:db,FIREBASE_PROJECT_ID:'friendbook-test'});
  const firstRows=(await first.json()).documents;
  assert.deepEqual(firstRows.map(row=>row.id),['c','b']);
  const next=await app.fetch(query([100,'b']),{DB:db,FIREBASE_PROJECT_ID:'friendbook-test'});
  assert.deepEqual((await next.json()).documents.map(row=>row.id),['a','d']);
  const lastPage=await app.fetch(new Request('https://example.test/v1/firestore/query',{method:'POST',headers:{authorization:'Bearer token','content-type':'application/json'},body:JSON.stringify({parent:'posts',orderBy:[{field:'createdAt',direction:'ASCENDING'}],limit:2,limitToLast:true})}),{DB:db,FIREBASE_PROJECT_ID:'friendbook-test'});
  assert.deepEqual((await lastPage.json()).documents.map(row=>row.id),['b','c']);
  sql.close();
});

test('nested chat queries bridge legacy D1 messages and require chat membership', async () => {
  const sql = new DatabaseSync(':memory:');
  sql.exec(resourcesSchema);
  sql.exec(appSchema);
  sql.exec(schema);
  const appDoc = sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)');
  appDoc.run('chats/c1','chats','c1','u1',JSON.stringify({id:'c1',participants:['u1','u2']}),1);
  appDoc.run('messages/c1_m1','messages','c1_m1','u1',JSON.stringify({senderId:'u1',receiverId:'u2',text:'old chat message',createdAtMs:10,createdAt:10}),10);
  appDoc.run('messages/old-id','messages','old-id','u1',JSON.stringify({chatId:'c1',senderId:'u2',receiverId:'u1',text:'chatId-keyed legacy message',createdAtMs:9,createdAt:9}),9);
  appDoc.run('messages/c2_m2','messages','c2_m2','u1',JSON.stringify({chatId:'c2',senderId:'u1',receiverId:'u3',text:'other chat secret',createdAtMs:11,createdAt:11}),11);
  const db = { prepare(query) { const stmt = sql.prepare(query); return { bind(...args) {
    return { run: async () => stmt.run(...args), all: async () => ({results: stmt.all(...args)}), first: async () => stmt.get(...args) || null };
  } }; } };
  const req = () => new Request('https://example.test/v1/firestore/query', {
    method:'POST', headers:{authorization:'Bearer token','content-type':'application/json'},
    body:JSON.stringify({parent:'chats/c1/messages',orderBy:[{field:'createdAt',direction:'ASCENDING'}]}),
  });
  const memberWorker=createWorker(async()=> 'u1');
  const memberResult=await memberWorker.fetch(req(),{DB:db,FIREBASE_PROJECT_ID:'friendbook-test'});
  assert.equal(memberResult.status,200);
  const rows=(await memberResult.json()).documents;
  assert.equal(rows.length,2);
  assert.equal(rows[0].id,'old-id');
  assert.equal(rows[0].data.text,'chatId-keyed legacy message');
  assert.equal(rows[1].id,'m1');
  assert.equal(rows[1].data.text,'old chat message');
  const outsiderWorker=createWorker(async()=> 'u9');
  const denied=await outsiderWorker.fetch(req(),{DB:db,FIREBASE_PROJECT_ID:'friendbook-test'});
  assert.equal(denied.status,403);
  sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)')
    .run('chats/c1/messages/private','chats/c1/messages','messages','private','u1',JSON.stringify({text:'secret'}),1,1);
  const direct = () => new Request('https://example.test/v1/firestore/document?path=chats/c1/messages/private',{headers:{authorization:'Bearer token'}});
  const count = () => new Request('https://example.test/v1/firestore/count?parent=chats/c1/messages',{headers:{authorization:'Bearer token'}});
  assert.equal((await memberWorker.fetch(direct(),{DB:db,FIREBASE_PROJECT_ID:'friendbook-test'})).status,200);
  assert.equal((await memberWorker.fetch(count(),{DB:db,FIREBASE_PROJECT_ID:'friendbook-test'})).status,200);
  assert.equal((await outsiderWorker.fetch(direct(),{DB:db,FIREBASE_PROJECT_ID:'friendbook-test'})).status,403);
  assert.equal((await outsiderWorker.fetch(count(),{DB:db,FIREBASE_PROJECT_ID:'friendbook-test'})).status,403);
  sql.close();
});

test('additive mirror migration copies existing root app documents and preserves newer feed rows', () => {
  const sql = new DatabaseSync(':memory:');
  sql.exec(resourcesSchema);
  sql.exec(appSchema);
  sql.exec(schema);
  sql.prepare(`INSERT INTO friendbook_cf_firestore_documents
    (path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time)
    VALUES (?,?,?,?,?,?,?,?)`).run('users/u1','users','users','u1','u1',JSON.stringify({uid:'u1',displayName:'Migrated'}),1,100);
  sql.prepare(`INSERT INTO friendbook_cf_firestore_documents
    (path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time)
    VALUES (?,?,?,?,?,?,?,?)`).run('posts/p1','posts','posts','p1','u1',JSON.stringify({authorId:'u1',content:'Post'}),1,200);
  sql.prepare(`INSERT INTO friendbook_cf_firestore_documents
    (path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time)
    VALUES (?,?,?,?,?,?,?,?)`).run('posts/p1/comments/c1','posts/p1/comments','comments','c1','u1',JSON.stringify({content:'Nested'}),1,300);
  sql.prepare(`INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)`).run('users/u1','users','u1','u1',JSON.stringify({uid:'u1',displayName:'Newer'}),150);
  sql.exec(mirrorMigration);
  assert.equal(JSON.parse(sql.prepare('SELECT document_json FROM friendbook_cf_documents WHERE path=?').get('users/u1').document_json).displayName,'Newer');
  assert.equal(JSON.parse(sql.prepare('SELECT document_json FROM friendbook_cf_documents WHERE path=?').get('posts/p1').document_json).content,'Post');
  assert.equal(sql.prepare('SELECT COUNT(*) AS n FROM friendbook_cf_documents WHERE path LIKE ?').get('posts/p1/comments/%').n,0);
  assert.equal(sql.prepare('SELECT state FROM friendbook_cf_interaction_resources WHERE resource=?').get('posts/p1').state,'verified');
  sql.close();
});

test('cleanup migration removes only synthetic D1 proof profiles', () => {
  const sql = new DatabaseSync(':memory:');
  sql.exec(resourcesSchema);
  sql.exec(appSchema);
  sql.exec(schema);
  sql.exec('CREATE TABLE friendbook_cf_profiles (uid TEXT PRIMARY KEY, display_name TEXT, cloudinary_avatar_url TEXT)');
  const cleanup = readFileSync(new URL('../migrations/0018_remove_d1_proof_profiles.sql', import.meta.url), 'utf8');
  const proof = JSON.stringify({displayName:'Cloudflare D1 migration proof',proofMarker:'d1-only'});
  const real = JSON.stringify({displayName:'Real user',proofMarker:'normal'});
  sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)').run('users/proof','users','proof','proof',proof,1);
  sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)').run('users/real','users','real','real',real,1);
  sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)')
    .run('users/proof','users','users','proof','proof',proof,1,1);
  sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)')
    .run('users/real','users','users','real','real',real,1,1);
  sql.prepare('INSERT INTO friendbook_cf_profiles VALUES (?,?,?)').run('proof','Cloudflare D1 migration proof','');
  sql.prepare('INSERT INTO friendbook_cf_profiles VALUES (?,?,?)').run('real','Real user','');
  sql.exec(cleanup);
  assert.equal(sql.prepare('SELECT COUNT(*) AS n FROM friendbook_cf_documents WHERE doc_id=?').get('proof').n,0);
  assert.equal(sql.prepare('SELECT COUNT(*) AS n FROM friendbook_cf_firestore_documents WHERE doc_id=?').get('proof').n,0);
  assert.equal(sql.prepare('SELECT COUNT(*) AS n FROM friendbook_cf_profiles WHERE uid=?').get('proof').n,0);
  assert.equal(sql.prepare('SELECT COUNT(*) AS n FROM friendbook_cf_documents WHERE doc_id=?').get('real').n,1);
  assert.equal(sql.prepare('SELECT COUNT(*) AS n FROM friendbook_cf_firestore_documents WHERE doc_id=?').get('real').n,1);
  assert.equal(sql.prepare('SELECT COUNT(*) AS n FROM friendbook_cf_profiles WHERE uid=?').get('real').n,1);
  sql.close();
});

test('D1 document misses never fall back to reading the Firebase Firestore API', async () => {
  const sql = new DatabaseSync(':memory:');
  sql.exec(schema);
  sql.exec(appSchema);
  const db = { prepare(query) { const stmt = sql.prepare(query); return { bind(...args) {
    return { run: async () => stmt.run(...args), all: async () => ({results: stmt.all(...args)}), first: async () => stmt.get(...args) || null };
  } }; } };
  const app = createWorker(async () => 'uid1');
  const originalFetch = globalThis.fetch;
  let externalCalls = 0;
  globalThis.fetch = async () => { externalCalls++; throw new Error('Unexpected external data read'); };
  try {
    const miss = await app.fetch(new Request('https://example.test/v1/firestore/document?path=users/missing',{headers:{Authorization:'Bearer proof-token'}}),
      {DB: db, FIREBASE_PROJECT_ID: 'friendbook-test'});
    assert.equal(miss.status, 200);
    assert.equal((await miss.json()).exists, false);
    const query = await app.fetch(new Request('https://example.test/v1/firestore/query', {method:'POST',headers:{'content-type':'application/json',Authorization:'Bearer proof-token'},body:JSON.stringify({parent:'users',limit:10})}),
      {DB: db, FIREBASE_PROJECT_ID: 'friendbook-test'});
    assert.equal(query.status, 200);
    assert.deepEqual((await query.json()).documents, []);
    assert.equal(externalCalls, 0);
  } finally { globalThis.fetch = originalFetch; sql.close(); }
});

test('database read failures are not reported as missing profiles or empty lists', async () => {
  const app = createWorker(async () => 'uid1');
  const db = { prepare() { throw new Error('database offline'); } };
  const env = {DB:db, FIREBASE_PROJECT_ID:'friendbook-test'};
  const headers = {authorization:'Bearer token', 'content-type':'application/json'};
  const doc = await app.fetch(new Request('https://example.test/v1/firestore/document?path=users/uid1',{headers}), env);
  assert.equal(doc.status, 503);
  const query = await app.fetch(new Request('https://example.test/v1/firestore/query',{method:'POST',headers,body:JSON.stringify({parent:'notifications'})}),env);
  assert.equal(query.status, 503);
  const count = await app.fetch(new Request('https://example.test/v1/firestore/count?parent=posts',{headers}),env);
  assert.equal(count.status, 503);
});

test('Android batch operations persist chat messages and apply update and delete operations', async () => {
  const sql = new DatabaseSync(':memory:');
  sql.exec(resourcesSchema); sql.exec(appSchema); sql.exec(schema);sql.exec(socialGuardSchema);
  const db = { prepare(query) { const stmt = sql.prepare(query); return { bind(...args) {
    return { run: async () => stmt.run(...args), all: async () => ({results:stmt.all(...args)}), first: async () => stmt.get(...args) || null };
  } }; }, async batch(statements) { for (const s of statements) await s.run(); } };
  const app=createWorker(async()=> 'u1');
  const env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test'};
  const headers={authorization:'Bearer token','content-type':'application/json'};
  const post = async operations => app.fetch(new Request('https://example.test/v1/firestore/batch', {method:'POST',headers,body:JSON.stringify({operations})}),env);
  let res=await post([
    {op:'set',path:'chats/room/messages/m1',data:{senderId:'u1',text:'hello',createdAt:{__fieldValue:'serverTimestamp'}}},
    {op:'set',path:'chats/room',merge:true,data:{participants:['u1','u2']}}
  ]);
  assert.equal(res.status,200);
  res=await app.fetch(new Request('https://example.test/v1/firestore/query',{method:'POST',headers,body:JSON.stringify({parent:'chats/room/messages'})}),env);
  assert.equal(res.status,200); assert.equal((await res.json()).documents[0].data.text,'hello');
  res=await post([{op:'update',path:'chats/room/messages/m1',updates:{isSeen:true}}]);
  assert.equal(res.status,200);
  let doc=JSON.parse(sql.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?').get('chats/room/messages/m1').document_json);
  assert.equal(doc.isSeen,true); assert.equal(doc.text,'hello');
  res=await post([{op:'delete',path:'chats/room/messages/m1'}]);
  assert.equal(res.status,200);
  assert.equal(sql.prepare('SELECT path FROM friendbook_cf_firestore_documents WHERE path=?').get('chats/room/messages/m1'),undefined);
  sql.close();
});

test('notification inbox is scoped across document APIs and batch cannot reassign a recipient', async () => {
  const sql = new DatabaseSync(':memory:');
  sql.exec(resourcesSchema); sql.exec(appSchema); sql.exec(schema);
  const db = { prepare(query) { const stmt = sql.prepare(query); return { bind(...args) {
    return { run: async () => stmt.run(...args), all: async () => ({results:stmt.all(...args)}), first: async () => stmt.get(...args) || null };
  } }; }, async batch(statements) { for (const s of statements) await s.run(); } };
  const app=createWorker(async token => token);
  const env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test'};
  const call=(uid,path,method='GET',body) => app.fetch(new Request('https://example.test'+path,{
    method,headers:{authorization:'Bearer '+uid,'content-type':'application/json'},...(body?{body:JSON.stringify(body)}:{})
  }),env);
  const write=await call('u1','/v1/firestore/document?path=notifications/n1','PUT', {data:{fromUserId:'u1',toUserId:'u2',isRead:false,text:'private'}});
  assert.equal(write.status,200);
  assert.equal((await call('u1','/v1/firestore/document?path=notifications/n1')).status,403);
  assert.equal((await call('u1','/v1/docs/notifications/n1')).status,403);
  assert.equal((await call('u2','/v1/firestore/document?path=notifications/n1')).status,200);
  let list=await call('u1','/v1/firestore/query','POST',{parent:'notifications'});
  assert.deepEqual((await list.json()).documents,[]);
  list=await call('u1','/v1/docs?collection=notifications');
  assert.deepEqual((await list.json()).documents,[]);
  sql.exec(readFileSync(new URL('../migrations/0036_inbox_unread_indexes.sql',import.meta.url),'utf8'));
  sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)').run('notifications/old','notifications','old','u2',JSON.stringify({fields:{toUserId:{stringValue:'u2'},isRead:{booleanValue:false}}}),1);
  const unread=[{field:'isRead',op:'==',value:false}];
  const unreadCount=uid=>call(uid,`/v1/firestore/count?parent=notifications&filters=${encodeURIComponent(JSON.stringify(unread))}`);
  assert.equal((await (await unreadCount('u2')).json()).count,2);
  assert.equal((await (await unreadCount('u1')).json()).count,0);
  const plan=sql.prepare("EXPLAIN QUERY PLAN SELECT doc_id FROM friendbook_cf_firestore_documents WHERE parent_path='notifications' AND json_type(document_json,'$.isRead')='false' AND json_extract(document_json,'$.toUserId')=?").all('u2');
  assert.ok(plan.some(row=>row.detail.includes('idx_cf_inbox_unread')));
  const count=await call('u1','/v1/firestore/count?parent=notifications');
  assert.equal((await count.json()).count,0);
  assert.equal((await call('u1','/v1/firestore/document?path=notifications/n1','PATCH',{fields:{toUserId:'u1'}})).status,403);
  assert.equal((await call('u1','/v1/docs/notifications/n1','PUT',{toUserId:'u1'})).status,403);
  assert.equal((await call('u1','/v1/firestore/batch','POST',{operations:[{op:'update',path:'notifications/n1',updates:{toUserId:'u1'}}]})).status,403);
  assert.equal((await call('u1','/v1/firestore/document?path=notifications/n1','DELETE')).status,403);
  assert.equal((await call('u2','/v1/firestore/batch','POST',{operations:[{op:'update',path:'notifications/n1',updates:{isRead:true}}]})).status,200);
  assert.equal((await (await unreadCount('u2')).json()).count,1);
  assert.equal((await call('u2','/v1/firestore/document?path=notifications/n1','PATCH',{fields:{text:'tampered'}})).status,403);
  assert.equal((await call('u3','/v1/firestore/document?parent=notifications','POST',{data:{fromUserId:'u1',toUserId:'u2',isRead:false}})).status,403);
  sql.close();
});

test('profiles and chats: private fields, membership and write bypasses across APIs', async () => {
  const sql=new DatabaseSync(':memory:'); sql.exec(resourcesSchema);sql.exec(appSchema);sql.exec(schema);sql.exec(socialGuardSchema);
  const db={prepare(query){const stmt=sql.prepare(query);return {bind(...args){return {run:async()=>stmt.run(...args),all:async()=>({results:stmt.all(...args)}),first:async()=>stmt.get(...args)||null};}};},async batch(stmts){for(const s of stmts)await s.run();}};
  const app=createWorker(async token=>token),env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test'};
  const call=(uid,path,method='GET',body)=>app.fetch(new Request('https://example.test'+path,{method,headers:{authorization:'Bearer '+uid,'content-type':'application/json'},...(body?{body:JSON.stringify(body)}:{})}),env);
  const put=(uid,path,data)=>call(uid,'/v1/firestore/document?path='+path,'PUT',{data});
  assert.equal((await put('alice','users/alice',{uid:'alice',displayName:'Alice',email:'private@example.test',twoFactorPin:'1234',isAdmin:false})).status,200);
  const profile=await (await call('bob','/v1/firestore/document?path=users/alice')).json();
  assert.equal(profile.data.displayName,'Alice');assert.equal(profile.data.email,undefined);assert.equal(profile.data.twoFactorPin,undefined);
  const other=await (await call('bob','/v1/docs/users/alice')).json();assert.equal(other.document.email,undefined);
  const list=await (await call('bob','/v1/firestore/query','POST',{parent:'users',filters:[{field:'email',op:'==',value:'private@example.test'}]})).json();assert.deepEqual(list.documents,[]);
  assert.equal((await put('bob','users/alice',{displayName:'forged'})).status,403);
  assert.equal((await call('alice','/v1/firestore/document?path=users/alice','PATCH',{fields:{isAdmin:true}})).status,403);
  assert.equal((await call('bob','/v1/docs/users/alice','PUT',{displayName:'forged'})).status,403);
  const batch=ops=>({operations:ops});
  assert.equal((await call('bob','/v1/firestore/batch','POST',batch([{op:'update',path:'users/alice',updates:{email:'stolen'}}]))).status,403);
  assert.equal((await call('bob','/v1/firestore/document?path=users/alice','DELETE')).status,403);
  assert.equal((await call('alice','/v1/firestore/batch','POST',batch([
    {op:'set',path:'chats/c1/messages/m1',data:{senderId:'alice',text:'secret'}},
    {op:'set',path:'chats/c1',data:{participants:['alice','bob']}}
  ]))).status,200);
  assert.equal((await call('eve','/v1/firestore/document?path=chats/c1')).status,403);
  assert.equal((await call('eve','/v1/docs/chats/c1')).status,403);
  assert.equal((await call('eve','/v1/firestore/document?path=chats/c1/messages/m1')).status,403);
  const chats=await (await call('eve','/v1/firestore/query','POST',{parent:'chats'})).json();assert.deepEqual(chats.documents,[]);
  assert.equal((await call('eve','/v1/firestore/document?path=chats/c1','PATCH',{fields:{participants:['alice','eve']}})).status,403);
  assert.equal((await call('bob','/v1/firestore/document?path=chats/c1','PATCH',{fields:{participants:['alice','bob','eve']}})).status,403);
  assert.equal((await call('alice','/v1/firestore/document?path=chats/bypass','PUT',{data:{participants:['alice','bob','eve']}})).status,403);
  assert.equal((await call('alice','/v1/firestore/document?path=chats/solo','PUT',{data:{participants:['alice']}})).status,403);
  assert.equal((await call('eve','/v1/firestore/batch','POST',batch([{op:'set',path:'chats/c1/messages/m2',data:{senderId:'eve',text:'spoof'}}]))).status,403);
  assert.equal((await call('bob','/v1/firestore/batch','POST',batch([{op:'update',path:'chats/c1/messages/m1',updates:{text:'tamper'}}]))).status,403);
  assert.equal((await call('bob','/v1/firestore/batch','POST',batch([{op:'update',path:'chats/c1/messages/m1',updates:{isSeen:true}}]))).status,200);
  assert.equal((await call('bob','/v1/docs/chats/c1','PUT',{participants:['eve']})).status,403);
  // A stale legacy mirror cannot grant membership or overwrite the canonical room.
  sql.prepare('UPDATE friendbook_cf_documents SET document_json=? WHERE path=?')
    .run(JSON.stringify({participants:['alice','bob','eve']}),'chats/c1');
  assert.equal((await call('eve','/v1/docs/chats/c1')).status,403);
  assert.equal((await call('eve','/v1/docs/chats/c1','PUT',{participants:['alice','bob','eve']})).status,403);
  const leaked = await (await call('eve','/v1/docs?collection=chats')).json();
  assert.deepEqual(leaked.documents,[]);
  assert.equal((await call('eve','/v1/docs?collection=messages')).status,200);
  sql.close();
});

test('post privacy and unreviewed collections fail closed on all document routes', async () => {
  const sql=new DatabaseSync(':memory:');sql.exec(resourcesSchema);sql.exec(appSchema);sql.exec(schema);sql.exec(socialGuardSchema);
  const db={prepare(query){const stmt=sql.prepare(query);return{bind(...args){return{run:async()=>stmt.run(...args),all:async()=>({results:stmt.all(...args)}),first:async()=>stmt.get(...args)||null};}};},async batch(stmts){sql.exec('BEGIN');try{for(const s of stmts)await s.run();sql.exec('COMMIT');}catch(e){sql.exec('ROLLBACK');throw e;}}};
  const add=sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)');
  add.run('users/alice','users','alice','alice',JSON.stringify({uid:'alice'}),1);
  add.run('users/bob','users','bob','bob',JSON.stringify({uid:'bob'}),1);
  const app=createWorker(async token=>token),env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test'};
  const call=(uid,path,method='GET',body)=>app.fetch(new Request('https://example.test'+path,{method,headers:{authorization:'Bearer '+uid,'content-type':'application/json'},...(body?{body:JSON.stringify(body)}:{})}),env);
  assert.equal((await call('alice','/v1/firestore/document?path=posts/p1','PUT',{data:{authorId:'alice',privacy:'Only me',content:'secret'}})).status,200);
  assert.equal((await call('bob','/v1/firestore/document?path=posts/p1')).status,403);
  assert.equal((await call('bob','/v1/docs/posts/p1')).status,403);
  // A stale public mirror cannot authorize the newer private canonical record.
  sql.prepare('UPDATE friendbook_cf_documents SET document_json=? WHERE path=?').run(JSON.stringify({authorId:'alice',privacy:'Public',content:'stale'}),'posts/p1');
  assert.equal((await call('bob','/v1/firestore/document?path=posts/p1')).status,403);
  assert.equal((await call('bob','/v1/docs/posts/p1')).status,403);
  assert.equal((await call('bob','/v1/firestore/document?path=posts/p1','PATCH',{fields:{privacy:'Public'}})).status,403);
  assert.equal((await call('bob','/v1/docs/posts/p1','PUT',{privacy:'Public'})).status,403);
  assert.equal((await call('bob','/v1/firestore/batch','POST',{operations:[{op:'delete',path:'posts/p1'}]})).status,403);
  assert.deepEqual((await (await call('bob','/v1/firestore/query','POST',{parent:'posts'})).json()).documents,[]);
  assert.deepEqual((await (await call('bob','/v1/firestore/query','POST',{parent:'payments'})).json()).documents,[]);
  assert.equal((await call('bob','/v1/firestore/document?path=sessions/alice','PUT',{data:{uid:'bob'}})).status,403);
  for (const collection of ['sessions']) {
    assert.equal((await call('bob',`/v1/firestore/document?path=${collection}/item`)).status,403);
    assert.equal((await call('bob',`/v1/firestore/document?parent=${collection}`,'POST',{data:{uid:'bob'}})).status,403);
    assert.equal((await call('bob','/v1/firestore/query','POST',{parent:collection})).status,403);
    assert.equal((await call('bob',`/v1/firestore/count?parent=${collection}`)).status,403);
    assert.equal((await call('bob','/v1/firestore/batch','POST',{operations:[{op:'set',path:`${collection}/item`,data:{uid:'bob'}}]})).status,403);
  }
  assert.equal((await call('bob','/v1/firestore/batch','POST',{operations:[{op:'set',path:'friends/alice_bob',data:{participants:['alice','bob']}}]})).status,403);
  assert.equal(sql.prepare('SELECT count(*) AS n FROM friendbook_cf_firestore_documents WHERE path=?').get('users/bob').n,0);
  sql.close();
});

test('friend requests restrict reads and pending creation, and reject forged acceptance on every route', async () => {
  const sql=new DatabaseSync(':memory:');sql.exec(resourcesSchema);sql.exec(appSchema);sql.exec(schema);
  sql.exec(readFileSync(new URL('../migrations/0018_social_guards.sql',import.meta.url),'utf8'));sql.exec(readFileSync(new URL('../migrations/0019_trusted_friendships.sql',import.meta.url),'utf8'));
  const db={prepare(query){const stmt=sql.prepare(query);return{bind(...args){return{run:async()=>stmt.run(...args),all:async()=>({results:stmt.all(...args)}),first:async()=>stmt.get(...args)||null};}};},async batch(stmts){sql.exec('BEGIN');try{for(const stmt of stmts)await stmt.run();sql.exec('COMMIT');}catch(e){sql.exec('ROLLBACK');throw e;}}};
  const app=createWorker(async token=>token), env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test'};
  const call=(uid,path,method='GET',body)=>app.fetch(new Request('https://example.test'+path,{method,headers:{authorization:'Bearer '+uid,'content-type':'application/json'},...(body?{body:JSON.stringify(body)}:{})}),env);
  const path='/v1/firestore/document?parent=friend_requests';
  assert.equal((await call('mallory',path,'POST',{data:{fromUid:'alice',toUid:'bob',status:'pending'}})).status,403);
  assert.equal((await call('alice',path,'POST',{data:{fromUid:'alice',toUid:'bob',status:'accepted'}})).status,403);
  const create=await call('alice',path,'POST',{data:{fromUid:'alice',toUid:'bob',status:'pending',fromUserName:'Alice'}});
  assert.equal(create.status,201,await create.clone().text());
  const {id}=await create.json();
  const insertLegacy=sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)');
  insertLegacy.run('friend_requests/legacy-only','friend_requests','legacy-only','carol',JSON.stringify({fields:{fromUid:{stringValue:'carol'},toUid:{stringValue:'bob'},status:{stringValue:'pending'}}}),1);
  const insertCanonical=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
  for(let i=0;i<100;i++) insertCanonical.run(`friend_requests/history${i}`,'friend_requests','friend_requests',`history${i}`,'alice',JSON.stringify({fromUid:'alice',toUid:'bob',status:'accepted'}),1,1);
  insertLegacy.run('friend_requests/history0','friend_requests','history0','alice',JSON.stringify({fromUid:'alice',toUid:'bob',status:'pending'}),1);
  const filters=[{field:'toUid',op:'==',value:'bob'},{field:'status',op:'==',value:'pending'}];
  const pending=await call('bob','/v1/firestore/query','POST',{parent:'friend_requests',filters,limit:20});
  assert.deepEqual((await pending.json()).documents.map(d=>d.id).sort(),[id,'legacy-only'].sort());
  const matching=await call('bob','/v1/firestore/query','POST',{parent:'friend_requests',filters:[...filters,{field:'fromUid',op:'in',value:['carol']}]});
  assert.deepEqual((await matching.json()).documents.map(d=>d.id),['legacy-only']);
  const outsider=await call('mallory','/v1/firestore/query','POST',{parent:'friend_requests',filters});
  assert.deepEqual((await outsider.json()).documents,[]);
  sql.prepare("DELETE FROM friendbook_cf_documents WHERE path IN ('friend_requests/legacy-only','friend_requests/history0')").run();
  sql.prepare("DELETE FROM friendbook_cf_firestore_documents WHERE path LIKE 'friend_requests/history%'").run();
  const uri=`/v1/firestore/document?path=friend_requests/${id}`;
  assert.equal((await call('alice',uri)).status,200);
  assert.equal((await call('bob',uri)).status,200);
  assert.equal((await call('mallory',uri)).status,403);
  assert.equal((await call('mallory',`/v1/docs/friend_requests/${id}`)).status,403);
  assert.equal((await call('bob',`/v1/docs/friend_requests/${id}`)).status,200);
  assert.equal((await (await call('mallory','/v1/firestore/query','POST',{parent:'friend_requests'})).json()).documents.length,0);
  assert.equal((await (await call('bob','/v1/firestore/count?parent=friend_requests')).json()).count,1);
  assert.equal((await call('bob',uri,'PATCH',{fields:{status:'accepted'}})).status,403);
  assert.equal((await call('bob',`/v1/docs/friend_requests/${id}`,'PUT',{status:'accepted'})).status,403);
  assert.equal((await call('bob','/v1/firestore/batch','POST',{operations:[{op:'update',path:`friend_requests/${id}`,fields:{status:'accepted'}}]})).status,403);
  assert.equal((await call('mallory',uri,'DELETE')).status,403);
  const realBatch=db.batch;
  db.batch=async stmts=>{
    sql.prepare('UPDATE friendbook_cf_firestore_documents SET document_json=? WHERE path=?').run(JSON.stringify({fromUid:'alice',toUid:'carol',status:'pending'}),`friend_requests/${id}`);
    return realBatch(stmts);
  };
  assert.equal((await call('bob',uri,'DELETE')).status,409);
  assert.equal(JSON.parse(sql.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?').get(`friend_requests/${id}`).document_json).toUid,'carol');
  db.batch=realBatch;
  assert.equal((await call('carol',uri,'DELETE')).status,200);
  assert.equal(sql.prepare('SELECT count(*) AS n FROM friendbook_cf_documents WHERE path=?').get(`friend_requests/${id}`).n,0);
  assert.equal(sql.prepare('SELECT count(*) AS n FROM friendbook_cf_firestore_documents WHERE path=?').get(`friend_requests/${id}`).n,0);
  sql.close();
});

test('existing friendships are read-only and only visible to their participants on both APIs', async () => {
  const sql=new DatabaseSync(':memory:');sql.exec(resourcesSchema);sql.exec(appSchema);sql.exec(schema);
  const db={prepare(query){const stmt=sql.prepare(query);return{bind(...args){return{run:async()=>stmt.run(...args),all:async()=>({results:stmt.all(...args)}),first:async()=>stmt.get(...args)||null};}};},async batch(stmts){for(const stmt of stmts)await stmt.run();}};
  const add=sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)');
  add.run('friends/f1','friends','f1','alice',JSON.stringify({participants:['alice','bob'],createdAt:42,privateExtra:'secret'}),1);
  const app=createWorker(async token=>token),env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test'};
  const call=(uid,path,method='GET',body)=>app.fetch(new Request('https://example.test'+path,{method,headers:{authorization:'Bearer '+uid,'content-type':'application/json'},...(body?{body:JSON.stringify(body)}:{})}),env);
  const uri='/v1/firestore/document?path=friends/f1';
  const own=await call('alice',uri); assert.equal(own.status,200);
  assert.deepEqual((await own.json()).data,{participants:['alice','bob'],createdAt:42});
  assert.equal((await call('mallory',uri)).status,403);
  assert.equal((await call('mallory','/v1/docs/friends/f1')).status,403);
  const legacy=await (await call('bob','/v1/docs/friends/f1')).json();
  assert.equal(legacy.document.privateExtra,undefined);
  assert.equal((await (await call('mallory','/v1/firestore/query','POST',{parent:'friends'})).json()).documents.length,0);
  assert.equal((await (await call('bob','/v1/firestore/count?parent=friends')).json()).count,1);
  assert.equal((await call('bob',uri,'PATCH',{fields:{participants:['bob','mallory']}})).status,403);
  assert.equal((await call('bob',uri,'DELETE')).status,403);
  assert.equal((await call('bob','/v1/firestore/document?parent=friends','POST',{data:{participants:['bob','mallory']}})).status,403);
  assert.equal((await call('bob','/v1/docs/friends/f1','PUT',{participants:['bob','mallory']})).status,403);
  assert.equal((await call('bob','/v1/firestore/batch','POST',{operations:[{op:'delete',path:'friends/f1'}]})).status,403);
  sql.close();
});

test('friend acceptance commits only a recipient-authorized pending request once and atomically', async () => {
  const sql=new DatabaseSync(':memory:');sql.exec(resourcesSchema);sql.exec(appSchema);sql.exec(schema);sql.exec(readFileSync(new URL('../migrations/0018_social_guards.sql',import.meta.url),'utf8'));sql.exec(readFileSync(new URL('../migrations/0019_trusted_friendships.sql',import.meta.url),'utf8'));
  const db={prepare(query){const stmt=sql.prepare(query);return{bind(...args){return{run:async()=>stmt.run(...args),all:async()=>({results:stmt.all(...args)}),first:async()=>stmt.get(...args)||null};}};},async batch(stmts){sql.exec('BEGIN');try{for(const s of stmts)await s.run();sql.exec('COMMIT');}catch(e){sql.exec('ROLLBACK');throw e;}}};
  const app=createWorker(async t=>t),env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test'};
  const call=(uid,path,method='GET',body)=>app.fetch(new Request('https://example.test'+path,{method,headers:{authorization:'Bearer '+uid,'content-type':'application/json'},...(body?{body:JSON.stringify(body)}:{})}),env);
  for(const uid of ['alice','bob','mallory']) assert.equal((await call(uid,`/v1/firestore/document?path=users/${uid}`,'PUT',{data:{uid,friendsCount:0}})).status,200);
  assert.equal(await hasD1Friendship(db,'alice','bob'),null);
  const created=await call('alice','/v1/firestore/document?parent=friend_requests','POST',{data:{fromUid:'alice',toUid:'bob',status:'pending'}});
  const {id}=await created.json();
  const legacy=sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)');
  legacy.run('friend_requests/olddup','friend_requests','olddup','alice',JSON.stringify({fields:{fromUid:{stringValue:'alice'},toUid:{stringValue:'bob'},status:{stringValue:'pending'},fromUserName:{stringValue:'A'}}}),1);
  legacy.run('friend_requests/unrelated','friend_requests','unrelated','mallory',JSON.stringify({fromUid:'mallory',toUid:'bob',status:'pending'}),1);
  legacy.run('friend_requests/shadow','friend_requests','shadow','alice',JSON.stringify({fromUid:'alice',toUid:'bob',status:'pending'}),1);
  sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)').run('friend_requests/shadow','friend_requests','friend_requests','shadow','mallory',JSON.stringify({fromUid:'mallory',toUid:'bob',status:'pending'}),1,1);
  const ops=[{op:'update',path:`friend_requests/${id}`,updates:{status:'accepted'}},
    {op:'set',path:'friends/random1',data:{participants:['bob','alice'],createdAt:{__fieldValue:'serverTimestamp'}}},
    ...['bob','alice'].map(uid=>({op:'update',path:`users/${uid}`,updates:{friendsCount:{__fieldValue:'increment',value:1}}}))];
  assert.equal((await call('mallory','/v1/firestore/batch','POST',{operations:ops})).status,403);
  assert.equal((await call('alice','/v1/firestore/batch','POST',{operations:ops})).status,403);
  const modified=structuredClone(ops); modified[2].updates.friendsCount.value=200;
  assert.equal((await call('bob','/v1/firestore/batch','POST',{operations:modified})).status,403);
  assert.equal((await call('bob','/v1/firestore/document?path=users/bob','PATCH',{fields:{friendsCount:99}})).status,403);
  assert.equal((await call('bob','/v1/firestore/batch','POST',{operations:ops})).status,200);
  assert.equal(JSON.parse(sql.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?').get('users/alice').document_json).friendsCount,1);
  assert.equal(JSON.parse(sql.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?').get('users/bob').document_json).friendsCount,1);
  assert.equal(JSON.parse(sql.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?').get(`friend_requests/${id}`).document_json).status,'accepted');
  const duplicate=JSON.parse(sql.prepare('SELECT document_json FROM friendbook_cf_documents WHERE path=?').get('friend_requests/olddup').document_json);
  assert.equal(duplicate.fields.status.stringValue,'accepted'); assert.equal(duplicate.fields.fromUserName.stringValue,'A');
  assert.equal(JSON.parse(sql.prepare('SELECT document_json FROM friendbook_cf_documents WHERE path=?').get('friend_requests/unrelated').document_json).status,'pending');
  assert.equal(JSON.parse(sql.prepare('SELECT document_json FROM friendbook_cf_documents WHERE path=?').get('friend_requests/shadow').document_json).status,'pending','a conflicting canonical document must not authorize updating a shadowed row');
  assert.equal((await call('bob','/v1/firestore/batch','POST',{operations:ops})).status,403);
  const second=await call('alice','/v1/firestore/document?parent=friend_requests','POST',{data:{fromUid:'alice',toUid:'bob',status:'pending'}});
  const secondId=(await second.json()).id;
  const dup=structuredClone(ops);dup[0].path=`friend_requests/${secondId}`;dup[1].path='friends/random2';
  assert.equal((await call('bob','/v1/firestore/batch','POST',{operations:dup})).status,409);
  assert.equal(JSON.parse(sql.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?').get(`friend_requests/${secondId}`).document_json).status,'pending');
  assert.equal(JSON.parse(sql.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?').get('users/bob').document_json).friendsCount,1);
  assert.equal(await hasD1Friendship(db,'bob','alice'),true);
  assert.deepEqual({...sql.prepare('SELECT uid_low,uid_high,friend_path FROM friendbook_cf_trusted_friendships').get()},{uid_low:'alice',uid_high:'bob',friend_path:'friends/random1'});
  const third=await call('alice','/v1/firestore/document?parent=friend_requests','POST',{data:{fromUid:'alice',toUid:'mallory',status:'pending'}});
  const thirdId=(await third.json()).id;
  const racing=structuredClone(ops);racing[0].path=`friend_requests/${thirdId}`;racing[1].path='friends/random3';racing[1].data.participants=['mallory','alice'];racing[2].path='users/mallory';
  const actualBatch=db.batch;
  db.batch=async statements=>{
    sql.prepare('UPDATE friendbook_cf_firestore_documents SET document_json=? WHERE path=?').run(JSON.stringify({fromUid:'alice',toUid:'mallory',status:'declined'}),`friend_requests/${thirdId}`);
    return actualBatch(statements);
  };
  assert.equal((await call('mallory','/v1/firestore/batch','POST',{operations:racing})).status,409);
  assert.equal(sql.prepare('SELECT count(*) AS n FROM friendbook_cf_firestore_documents WHERE path=?').get('friends/random3').n,0);
  assert.equal(sql.prepare('SELECT count(*) AS n FROM friendbook_cf_trusted_friendships').get().n,1);
  assert.equal(JSON.parse(sql.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?').get('users/alice').document_json).friendsCount,1);
  db.batch=actualBatch;
  const remove=[{op:'delete',path:'friends/random1'},...['alice','bob'].map(uid=>({op:'update',path:`users/${uid}`,updates:{friendsCount:{__fieldValue:'increment',value:-1}}}))];
  assert.equal((await call('mallory','/v1/firestore/batch','POST',{operations:remove})).status,403);
  const badRemove=structuredClone(remove);badRemove[2].updates.friendsCount.value=-50;
  assert.equal((await call('bob','/v1/firestore/batch','POST',{operations:badRemove})).status,403);
  sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)').run('friends/duplicate','friends','duplicate','alice',JSON.stringify({participants:['alice','bob']}),1);
  assert.equal((await call('bob','/v1/firestore/batch','POST',{operations:remove})).status,409);
  assert.equal(sql.prepare('SELECT count(*) AS n FROM friendbook_cf_trusted_friendships').get().n,1);
  sql.prepare('DELETE FROM friendbook_cf_documents WHERE path=?').run('friends/duplicate');
  db.batch=async statements=>{
    sql.prepare('UPDATE friendbook_cf_firestore_documents SET document_json=json_set(document_json,\'$.bio\',\'changed\') WHERE path=?').run('users/alice');
    return actualBatch(statements);
  };
  assert.equal((await call('bob','/v1/firestore/batch','POST',{operations:remove})).status,409);
  assert.equal(sql.prepare('SELECT count(*) AS n FROM friendbook_cf_trusted_friendships').get().n,1);
  db.batch=actualBatch;
  assert.equal((await call('bob','/v1/firestore/batch','POST',{operations:remove})).status,200);
  assert.equal(sql.prepare('SELECT count(*) AS n FROM friendbook_cf_trusted_friendships').get().n,0);
  assert.equal(await hasD1Friendship(db,'bob','alice'),null);
  assert.equal(sql.prepare('SELECT count(*) AS n FROM friendbook_cf_firestore_documents WHERE path=?').get('friends/random1').n,0);
  assert.equal(JSON.parse(sql.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?').get('users/alice').document_json).friendsCount,0);
  assert.equal(JSON.parse(sql.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?').get('users/bob').document_json).friendsCount,0);
  assert.equal((await call('bob','/v1/firestore/batch','POST',{operations:remove})).status,403);
  sql.close();
});

test('legacy friendship duplicates require one atomic removal with the exact counter delta', async () => {
  const sql=new DatabaseSync(':memory:');sql.exec(resourcesSchema);sql.exec(appSchema);sql.exec(schema);
  for (const name of ['0018_social_guards.sql','0019_trusted_friendships.sql']) sql.exec(readFileSync(new URL(`../migrations/${name}`,import.meta.url),'utf8'));
  const db={prepare(query){const stmt=sql.prepare(query);return{bind(...args){return{run:async()=>stmt.run(...args),all:async()=>({results:stmt.all(...args)}),first:async()=>stmt.get(...args)||null};}};},async batch(stmts){sql.exec('BEGIN');try{for(const s of stmts)await s.run();sql.exec('COMMIT');}catch(e){sql.exec('ROLLBACK');throw e;}}};
  const add=sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)');
  for(const uid of ['alice','bob']) add.run(`users/${uid}`,'users',uid,uid,JSON.stringify({uid,friendsCount:2}),1);
  add.run('friends/old1','friends','old1','alice',JSON.stringify({id:'old1',participants:['alice','bob']}),1);
  add.run('friends/old2','friends','old2','bob',JSON.stringify({fields:{participants:{arrayValue:{values:[{stringValue:'bob'},{stringValue:'alice'}]}}}}),1);
  const app=createWorker(async t=>t),env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test'};
  const call=(uid,operations)=>app.fetch(new Request('https://example.test/v1/firestore/batch',{method:'POST',headers:{authorization:'Bearer '+uid,'content-type':'application/json'},body:JSON.stringify({operations})}),env);
  const counts=n=>['alice','bob'].map(uid=>({op:'update',path:`users/${uid}`,updates:{friendsCount:{__fieldValue:'increment',value:-n}}}));
  const one=[{op:'delete',path:'friends/old1'},...counts(1)];
  assert.equal((await call('alice',one)).status,409);
  const both=[{op:'delete',path:'friends/old1'},{op:'delete',path:'friends/old2'},...counts(2)];
  assert.equal((await call('mallory',both)).status,403);
  assert.equal((await call('alice',both)).status,200);
  assert.equal(sql.prepare("SELECT count(*) n FROM friendbook_cf_documents WHERE collection='friends'").get().n,0);
  assert.equal(JSON.parse(sql.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?').get('users/bob').document_json).friendsCount,0);
  sql.close();
});

test('free verification requests are owner-private and cannot be self-approved', async () => {
  const sql=new DatabaseSync(':memory:');sql.exec(resourcesSchema);sql.exec(appSchema);sql.exec(schema);
  const db={prepare(query){const stmt=sql.prepare(query);return{bind(...args){return{run:async()=>stmt.run(...args),all:async()=>({results:stmt.all(...args)}),first:async()=>stmt.get(...args)||null};}};},async batch(stmts){sql.exec('BEGIN');try{for(const s of stmts)await s.run();sql.exec('COMMIT');}catch(e){sql.exec('ROLLBACK');throw e;}}};
  const app=createWorker(async t=>t),env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test',PAYMENT_ADMIN_UIDS:'admin'};
  const call=(uid,path,method='GET',body)=>app.fetch(new Request('https://example.test'+path,{method,headers:{authorization:'Bearer '+uid,'content-type':'application/json'},...(body?{body:JSON.stringify(body)}:{})}),env);
  const req={userId:'alice',userEmail:'alice@example.test',type:'tick_blue',amount:0,currency:'FREE',status:'pending',date:{__fieldValue:'serverTimestamp'}};
  const endpoint='/v1/firestore/document?parent=payments';
  assert.equal((await call('bob',endpoint,'POST',{data:req})).status,403);
  assert.equal((await call('alice',endpoint,'POST',{data:{...req,status:'approved'}})).status,403);
  assert.equal((await call('alice',endpoint,'POST',{data:{...req,amount:10}})).status,403);
  const created=await call('alice',endpoint,'POST',{data:req});assert.equal(created.status,201);
  const id=(await created.json()).id,uri=`/v1/firestore/document?path=payments/${id}`;
  assert.equal((await call('alice',uri)).status,200);
  assert.equal((await call('bob',uri)).status,403);
  assert.equal((await call('admin',uri)).status,200);
  assert.equal((await call('bob',`/v1/docs/payments/${id}`)).status,403);
  assert.equal((await call('admin',`/v1/docs/payments/${id}`)).status,200);
  assert.equal((await (await call('bob','/v1/firestore/query','POST',{parent:'payments'})).json()).documents.length,0);
  assert.equal((await (await call('alice','/v1/firestore/count?parent=payments')).json()).count,1);
  assert.equal((await (await call('admin','/v1/firestore/count?parent=payments')).json()).count,1);
  assert.equal((await call('alice',uri,'PATCH',{fields:{status:'approved'}})).status,403);
  assert.equal((await call('alice',uri,'DELETE')).status,403);
  assert.equal((await call('alice',`/v1/docs/payments/${id}`,'PUT',{status:'approved'})).status,403);
  assert.equal((await call('admin','/v1/firestore/batch','POST',{operations:[{op:'update',path:`payments/${id}`,updates:{status:'approved'}}]})).status,403);
  sql.close();
});

test('only configured administrators can atomically approve or reject a free review', async () => {
  const sql=new DatabaseSync(':memory:');sql.exec(resourcesSchema);sql.exec(appSchema);sql.exec(schema);sql.exec(readFileSync(new URL('../migrations/0018_social_guards.sql',import.meta.url),'utf8'));
  const db={prepare(query){const stmt=sql.prepare(query);return{bind(...args){return{run:async()=>stmt.run(...args),all:async()=>({results:stmt.all(...args)}),first:async()=>stmt.get(...args)||null};}};},async batch(stmts){sql.exec('BEGIN');try{for(const s of stmts)await s.run();sql.exec('COMMIT');}catch(e){sql.exec('ROLLBACK');throw e;}}};
  const app=createWorker(async t=>t),env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test',PAYMENT_ADMIN_UIDS:'admin'};
  const call=(uid,path,method='GET',body)=>app.fetch(new Request('https://example.test'+path,{method,headers:{authorization:'Bearer '+uid,'content-type':'application/json'},...(body?{body:JSON.stringify(body)}:{})}),env);
  assert.equal((await call('alice','/v1/firestore/document?path=users/alice','PUT',{data:{uid:'alice'}})).status,200);
  const make=async()=>{const res=await call('alice','/v1/firestore/document?parent=payments','POST',{data:{userId:'alice',type:'tick_blue',amount:0,currency:'FREE',status:'pending'}});return (await res.json()).id;};
  const insert=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
  insert.run('payments/paid','payments','payments','paid','alice',JSON.stringify({type:'subscription',userId:'alice',subscriptionId:'fan',status:'pending',amount:100,currency:'PKR'}),1,1);
  insert.run('subscriptions/fan','subscriptions','subscriptions','fan','alice',JSON.stringify({subscriberId:'alice',creatorId:'creator',status:'pending'}),1,1);
  const paid=[{op:'update',path:'payments/paid',updates:{status:'approved',approvedBy:'admin',approvedAt:{__fieldValue:'serverTimestamp'}}},
    {op:'update',path:'subscriptions/fan',updates:{status:'active'}}];
  const review=(uid,operations)=>call(uid,'/v1/firestore/batch','POST',{operations});
  assert.equal((await review('alice',paid)).status,403);
  assert.equal((await review('admin',[paid[0],{...paid[1],path:'subscriptions/other'}])).status,403);
  assert.equal((await review('admin',[paid[0],{...paid[1],updates:{status:'active',expiry:1}}])).status,403);
  assert.equal((await review('admin',paid)).status,200);
  const sub=await call('alice','/v1/firestore/document?path=subscriptions/fan');
  assert.equal(sub.status,200);
  assert.equal((await sub.json()).data.status,'active');
  assert.equal((await call('stranger','/v1/firestore/document?path=subscriptions/fan')).status,403);
  assert.equal((await review('admin',paid)).status,403);
  const id=await make(), path=`payments/${id}`;
  const ops=[{op:'update',path,updates:{status:'approved',approvedBy:'admin',approvedAt:{__fieldValue:'serverTimestamp'}}},
    {op:'set',path:'users/alice',merge:true,data:{tickLevel:'blue',isVerified:true}}];
  assert.equal((await call('alice','/v1/firestore/batch','POST',{operations:ops})).status,403);
  const wrong=structuredClone(ops);wrong[1].path='users/bob';
  assert.equal((await call('admin','/v1/firestore/batch','POST',{operations:wrong})).status,403);
  const actualBatch=db.batch;
  db.batch=async statements=>{
    sql.prepare('UPDATE friendbook_cf_firestore_documents SET document_json=json_set(document_json,\'$.bio\',\'changed\') WHERE path=?').run('users/alice');
    return actualBatch(statements);
  };
  assert.equal((await call('admin','/v1/firestore/batch','POST',{operations:ops})).status,409);
  assert.equal(JSON.parse(sql.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?').get(path).document_json).status,'pending');
  db.batch=actualBatch;
  assert.equal((await call('admin','/v1/firestore/batch','POST',{operations:ops})).status,200);
  assert.equal(JSON.parse(sql.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?').get('users/alice').document_json).tickLevel,'blue');
  assert.equal(JSON.parse(sql.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?').get(path).document_json).status,'approved');
  assert.equal((await call('admin','/v1/firestore/batch','POST',{operations:ops})).status,403);
  const id2=await make(),uri=`/v1/firestore/document?path=payments/${id2}`;
  const reject={updates:{status:'rejected',rejectedBy:'admin',rejectedAt:{__fieldValue:'serverTimestamp'}}};
  assert.equal((await call('alice',uri,'PATCH',reject)).status,403);
  assert.equal((await call('admin',uri,'PATCH',reject)).status,200);
  assert.equal(JSON.parse(sql.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?').get(`payments/${id2}`).document_json).status,'rejected');
  sql.close();
});

test('trusted groups enforce role and membership changes on both document APIs', async () => {
  const sql=new DatabaseSync(':memory:');sql.exec(resourcesSchema);sql.exec(appSchema);sql.exec(schema);
  for(const n of ['0018_social_guards.sql','0020_trusted_groups.sql'])sql.exec(readFileSync(new URL(`../migrations/${n}`,import.meta.url),'utf8'));
  const db={prepare(query){const stmt=sql.prepare(query);return{bind(...args){return{run:async()=>stmt.run(...args),all:async()=>({results:stmt.all(...args)}),first:async()=>stmt.get(...args)||null};}};},async batch(stmts){sql.exec('BEGIN');try{for(const s of stmts)await s.run();sql.exec('COMMIT');}catch(e){sql.exec('ROLLBACK');throw e;}}};
  const app=createWorker(async t=>t),env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test'};
  const call=(uid,path,method='GET',body)=>app.fetch(new Request('https://example.test'+path,{method,headers:{authorization:'Bearer '+uid,'content-type':'application/json'},...(body?{body:JSON.stringify(body)}:{})}),env);
  const group={name:'Private group',privacy:'Private',adminId:'alice',adminIds:['alice'],moderatorIds:[],members:['alice'],pendingMemberIds:[],invitedUids:[],bannedMemberIds:[],requireJoinApproval:true};
  const endpoint='/v1/firestore/document?parent=groups';
  assert.equal((await call('mallory',endpoint,'POST',{data:group})).status,403);
  const created=await call('alice',endpoint,'POST',{data:group}); assert.equal(created.status,201,await created.clone().text());
  const id=(await created.json()).id, uri=`/v1/firestore/document?path=groups/${id}`;
  assert.equal((await call('mallory',uri,'PATCH',{fields:{adminIds:['alice','mallory'],members:['alice','mallory']}})).status,403);
  assert.equal((await call('mallory',uri,'PATCH',{fields:{members:{__fieldValue:'arrayUnion',values:['mallory']}}})).status,403);
  assert.equal((await call('bob',uri,'PATCH',{fields:{pendingMemberIds:{__fieldValue:'arrayUnion',values:['bob']}}})).status,200);
  const outsider=await (await call('mallory',uri)).json();assert.deepEqual(outsider.data.pendingMemberIds,[]);
  const manager=await (await call('alice',uri)).json();assert.deepEqual(manager.data.pendingMemberIds,['bob']);
  assert.equal((await call('mallory',`/v1/docs/groups/${id}`,'PUT',{adminIds:['alice','mallory']})).status,403);
  const actualBatch=db.batch;
  db.batch=async stmts=>{
    sql.prepare('UPDATE friendbook_cf_firestore_documents SET document_json=json_set(document_json,\'$.about\',\'changed\') WHERE path=?').run(`groups/${id}`);
    return actualBatch(stmts);
  };
  assert.notEqual((await call('alice',uri,'PATCH',{fields:{members:{__fieldValue:'arrayUnion',values:['bob']},pendingMemberIds:{__fieldValue:'arrayRemove',values:['bob']}}})).status,200);
  db.batch=actualBatch;
  assert.equal((await call('alice',uri,'PATCH',{fields:{members:{__fieldValue:'arrayUnion',values:['bob']},pendingMemberIds:{__fieldValue:'arrayRemove',values:['bob']}}})).status,200);
  const joined=await (await call('bob',uri)).json();assert.ok(joined.data.members.includes('bob'));
  assert.equal((await call('bob',uri,'PATCH',{fields:{adminIds:{__fieldValue:'arrayUnion',values:['bob']}}})).status,403);
  assert.equal((await call('alice',uri,'PATCH',{fields:{requirePostApproval:true,members:{__fieldValue:'arrayUnion',values:['charlie']}}})).status,200);
  for(const name of ['alice','bob','charlie','mallory']) assert.equal((await call(name,`/v1/firestore/document?path=users/${name}`,'PUT',{data:{uid:name}})).status,200);
  const post='/v1/firestore/document?path=posts/gpost';
  assert.equal((await call('mallory',post,'PUT',{data:{authorId:'mallory',groupId:id,privacy:'Public'}})).status,403);
  const postCreated=await call('bob',post,'PUT',{data:{authorId:'bob',groupId:id,privacy:'Public',groupApprovalStatus:'approved',content:'hello'}});
  assert.equal(postCreated.status,201);assert.equal((await postCreated.json()).data.groupApprovalStatus,'pending');
  assert.equal((await call('charlie',post)).status,403);
  assert.equal((await call('bob',post)).status,200);
  assert.equal((await call('alice',post)).status,200);
  assert.equal((await call('bob',post,'PATCH',{fields:{groupApprovalStatus:'approved'}})).status,403);
  assert.equal((await call('alice',post,'PATCH',{fields:{groupApprovalStatus:'approved',content:'tampered'}})).status,403);
  assert.equal((await call('alice',post,'PATCH',{fields:{groupApprovalStatus:'approved'}})).status,200);
  assert.equal((await call('charlie',post)).status,200);
  assert.equal((await call('charlie',post,'DELETE')).status,403);
  assert.equal((await call('alice',post,'DELETE')).status,200);
  assert.equal((await call('bob','/v1/firestore/batch','POST',{operations:[{op:'delete',path:`groups/${id}`}]})).status,403);
  assert.equal((await call('bob',uri,'DELETE')).status,403);
  // A historical record with a self-asserted admin cannot grant write access.
  sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)').run('groups/old','groups','old','mallory',JSON.stringify({...group,adminId:'mallory',adminIds:['mallory'],members:['mallory']}),1);
  const oldUri='/v1/firestore/document?path=groups/old';
  assert.equal((await call('mallory',oldUri,'PATCH',{fields:{name:'forged'}})).status,403);
  assert.equal((await call('mallory',oldUri,'PATCH',{fields:{adminIds:['mallory','bob']}})).status,403);
  assert.equal((await call('bob',oldUri,'PATCH',{fields:{pendingMemberIds:{__fieldValue:'arrayUnion',values:['bob']}}})).status,200);
  assert.equal((await call('bob',oldUri,'PATCH',{fields:{members:{__fieldValue:'arrayUnion',values:['bob']}}})).status,403);
  assert.equal((await call('mallory',oldUri,'PATCH',{fields:{members:[],adminIds:[]}})).status,200);
  assert.equal((await call('alice',uri,'DELETE')).status,200);
  sql.close();
});

test('follow and unfollow change only the actor and target atomically', async () => {
  const sql=new DatabaseSync(':memory:');sql.exec(resourcesSchema);sql.exec(appSchema);sql.exec(schema);sql.exec(readFileSync(new URL('../migrations/0018_social_guards.sql',import.meta.url),'utf8'));
  const db={prepare(query){const stmt=sql.prepare(query);return{bind(...args){return{run:async()=>stmt.run(...args),all:async()=>({results:stmt.all(...args)}),first:async()=>stmt.get(...args)||null};}};},async batch(stmts){sql.exec('BEGIN');try{for(const s of stmts)await s.run();sql.exec('COMMIT');}catch(e){sql.exec('ROLLBACK');throw e;}}};
  const app=createWorker(async t=>t),env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test'};
  const call=(uid,path,method='GET',body)=>app.fetch(new Request('https://example.test'+path,{method,headers:{authorization:'Bearer '+uid,'content-type':'application/json'},...(body?{body:JSON.stringify(body)}:{})}),env);
  for (const uid of ['alice','bob','mallory']) assert.equal((await call(uid,`/v1/firestore/document?path=users/${uid}`,'PUT',{data:{uid,followers:[],following:[]}})).status,200);
  const ops=(kind)=>[{op:'set',path:'users/bob',merge:true,data:{followers:{__fieldValue:kind,values:['alice']}}},{op:'set',path:'users/alice',merge:true,data:{following:{__fieldValue:kind,values:['bob']}}}];
  assert.equal((await call('mallory','/v1/firestore/batch','POST',{operations:ops('arrayUnion')})).status,403);
  assert.equal((await call('alice','/v1/firestore/document?path=users/bob','PATCH',{fields:{followers:['alice']}})).status,403);
  assert.equal((await call('alice','/v1/firestore/document?path=users/alice','PATCH',{fields:{following:['bob']}})).status,403);
  assert.equal((await call('alice','/v1/docs/users/bob','PUT',{followers:['alice']})).status,403);
  const forged=ops('arrayUnion');forged[0].data.followers.values=['mallory'];
  assert.equal((await call('alice','/v1/firestore/batch','POST',{operations:forged})).status,403);
  assert.equal((await call('alice','/v1/firestore/batch','POST',{operations:ops('arrayUnion')})).status,200);
  const data=path=>JSON.parse(sql.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?').get(path).document_json);
  assert.deepEqual(data('users/alice').following,['bob']);assert.deepEqual(data('users/bob').followers,['alice']);
  assert.deepEqual((await (await call('mallory','/v1/firestore/document?path=users/bob')).json()).data.followers,['alice']);
  assert.equal((await call('alice','/v1/firestore/batch','POST',{operations:ops('arrayUnion')})).status,200);
  assert.deepEqual(data('users/bob').followers,['alice']);
  const actualBatch=db.batch;
  db.batch=async statements=>{sql.prepare("UPDATE friendbook_cf_firestore_documents SET document_json=json_set(document_json,'$.bio','changed') WHERE path=?").run('users/bob');return actualBatch(statements);};
  assert.equal((await call('alice','/v1/firestore/batch','POST',{operations:ops('arrayRemove')})).status,409);
  assert.deepEqual(data('users/alice').following,['bob']);
  db.batch=actualBatch;
  assert.equal((await call('alice','/v1/firestore/batch','POST',{operations:ops('arrayRemove')})).status,200);
  assert.deepEqual(data('users/alice').following,[]);assert.deepEqual(data('users/bob').followers,[]);
  assert.equal((await call('bob','/v1/firestore/document?path=users/bob','PATCH',{fields:{blocked:['alice']}})).status,200);
  assert.equal((await call('alice','/v1/firestore/batch','POST',{operations:ops('arrayUnion')})).status,403);
  sql.close();
});

test('chat atomic guards reject concurrent room and message changes and orphan adoption', async () => {
  const sql=new DatabaseSync(':memory:'); sql.exec(resourcesSchema);sql.exec(appSchema);sql.exec(schema);sql.exec(socialGuardSchema);
  let race=null;
  const db={prepare(query){const stmt=sql.prepare(query);return {bind(...args){return {run:async()=>stmt.run(...args),all:async()=>({results:stmt.all(...args)}),first:async()=>stmt.get(...args)||null};}};},async batch(stmts){
    if(race){const fn=race;race=null;fn();}
    sql.exec('BEGIN');try{for(const s of stmts)await s.run();sql.exec('COMMIT');}catch(e){sql.exec('ROLLBACK');throw e;}
  }};
  const app=createWorker(async token=>token),env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test'};
  const call=(uid,path,method,body)=>app.fetch(new Request('https://example.test'+path,{method,headers:{authorization:'Bearer '+uid,'content-type':'application/json'},body:JSON.stringify(body)}),env);
  const put=(uid,path,data)=>call(uid,'/v1/firestore/document?path='+path,'PUT',{data});
  const insert=(path,parent,coll,id,doc)=>sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)').run(path,parent,coll,id,'alice',JSON.stringify(doc),1,1);
  insert('chats/orphan/messages/old','chats/orphan/messages','messages','old',{senderId:'alice',text:'private'});
  assert.notEqual((await put('eve','chats/orphan',{participants:['eve']})).status,200);
  assert.equal((await put('alice','chats/room',{participants:['alice','bob']})).status,200);
  assert.equal((await call('alice','/v1/docs/chats/room','PUT',{participants:['alice']})).status,403);
  assert.equal((await call('alice','/v1/docs/messages/room_m','PUT',{chatId:'room',senderId:'alice'})).status,403);
  assert.equal((await call('bob','/v1/firestore/document?path=chats/room','PATCH',{fields:{adminIds:['bob']}})).status,403);
  race=()=>sql.prepare('UPDATE friendbook_cf_firestore_documents SET document_json=? WHERE path=?').run(JSON.stringify({participants:['eve']}),'chats/room');
  assert.notEqual((await put('alice','chats/room/messages/m1',{senderId:'alice',text:'must not commit'})).status,200);
  assert.equal(sql.prepare('SELECT path FROM friendbook_cf_firestore_documents WHERE path=?').get('chats/room/messages/m1'),undefined);
  sql.prepare('UPDATE friendbook_cf_firestore_documents SET document_json=? WHERE path=?').run(JSON.stringify({participants:['alice','bob']}),'chats/room');
  assert.equal((await put('alice','chats/room/messages/m2',{senderId:'alice',text:'original'})).status,200);
  race=()=>sql.prepare('UPDATE friendbook_cf_firestore_documents SET document_json=? WHERE path=?').run(JSON.stringify({senderId:'bob',text:'new owner'}),'chats/room/messages/m2');
  assert.notEqual((await call('alice','/v1/firestore/document?path=chats/room/messages/m2','PATCH',{fields:{text:'overwrite'}})).status,200);
  assert.equal(JSON.parse(sql.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?').get('chats/room/messages/m2').document_json).text,'new owner');
  race=()=>insert('chats/new','chats','chats','new',{participants:['eve']});
  const res=await call('alice','/v1/firestore/batch','POST',{operations:[{op:'set',path:'chats/new/messages/m',data:{senderId:'alice',text:'private'}},{op:'set',path:'chats/new',data:{participants:['alice','bob']}}]});
  assert.notEqual(res.status,200);
  assert.equal(sql.prepare('SELECT path FROM friendbook_cf_firestore_documents WHERE path=?').get('chats/new/messages/m'),undefined);
  sql.close();
});

test('trusted group chats support admin management, member leave, transfer and admin-only sending', async () => {
  const sql=new DatabaseSync(':memory:');sql.exec(resourcesSchema);sql.exec(appSchema);sql.exec(schema);sql.exec(socialGuardSchema);
  sql.exec(readFileSync(new URL('../migrations/0021_trusted_group_chats.sql',import.meta.url),'utf8'));
  const db={prepare(q){const stmt=sql.prepare(q);return{bind(...args){return{run:async()=>stmt.run(...args),all:async()=>({results:stmt.all(...args)}),first:async()=>stmt.get(...args)||null};}};},async batch(stmts){sql.exec('BEGIN');try{for(const s of stmts)await s.run();sql.exec('COMMIT');}catch(e){sql.exec('ROLLBACK');throw e;}}};
  const app=createWorker(async token=>token),env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test'};
  const call=(uid,path,method,body)=>app.fetch(new Request('https://example.test/v1/firestore/document?path='+path,{method,headers:{authorization:'Bearer '+uid,'content-type':'application/json'},body:JSON.stringify(body)}),env);
  const room={isGroup:true,createdBy:'alice',adminIds:['alice'],participants:['alice','bob','carol'],onlyAdminsCanSend:false,groupName:'Group'};
  assert.equal((await call('alice','chats/g','PUT',{data:{...room,adminIds:['alice','bob']}})).status,403);
  assert.equal((await call('alice','chats/g','PUT',{data:room})).status,200);
  assert.equal(sql.prepare('SELECT owner_uid FROM friendbook_cf_trusted_group_chats WHERE chat_path=?').get('chats/g').owner_uid,'alice');
  assert.equal((await call('bob','chats/g','PATCH',{fields:{adminIds:['alice','bob']}})).status,403);
  assert.equal((await call('bob','chats/g','PATCH',{fields:{participants:['alice','bob','carol','eve']}})).status,403);
  assert.equal((await call('alice','chats/g','PATCH',{fields:{adminIds:['alice','bob'],onlyAdminsCanSend:true}})).status,200);
  assert.equal((await call('carol','chats/g/messages/m','PUT',{data:{senderId:'carol',text:'blocked'}})).status,403);
  assert.equal((await call('bob','chats/g/messages/m','PUT',{data:{senderId:'bob',text:'allowed'}})).status,200);
  assert.equal((await call('carol','chats/g','PATCH',{fields:{participantNames:{carol:'Carol'}}})).status,200);
  assert.equal((await call('carol','chats/g','PATCH',{fields:{participantNames:{carol:'Carol',alice:'Impostor'}}})).status,403);
  assert.equal((await call('carol','chats/g','PATCH',{fields:{participants:['alice','bob']}})).status,200);
  assert.equal((await call('alice','chats/g','PATCH',{fields:{participants:['bob'],adminIds:['bob'],createdBy:'bob'}})).status,200);
  assert.equal(sql.prepare('SELECT owner_uid FROM friendbook_cf_trusted_group_chats WHERE chat_path=?').get('chats/g').owner_uid,'bob');
  assert.equal((await call('alice','chats/g','PATCH',{fields:{adminIds:['alice']}})).status,403);
  assert.equal((await call('bob','chats/g','PATCH',{fields:{onlyAdminsCanSend:false}})).status,200);
  assert.equal((await call('alice','chats/g','DELETE',{})).status,403);
  assert.equal((await call('bob','chats/g','DELETE',{})).status,200);
  assert.equal(sql.prepare('SELECT owner_uid FROM friendbook_cf_trusted_group_chats WHERE chat_path=?').get('chats/g'),undefined);
  // Messages remain inaccessible rather than being adopted by a new room owner.
  assert.notEqual((await call('eve','chats/g','PUT',{data:{participants:['eve']}})).status,200);
  sql.close();
});

test('both people can reconfirm a legacy friendship without duplicating it or incrementing counters', async () => {
  const sql=new DatabaseSync(':memory:');sql.exec(resourcesSchema);sql.exec(appSchema);sql.exec(schema);
  for(const n of ['0018_social_guards.sql','0019_trusted_friendships.sql'])sql.exec(readFileSync(new URL(`../migrations/${n}`,import.meta.url),'utf8'));
  let race=null;
  const db={prepare(q){const stmt=sql.prepare(q);return{bind(...args){return{run:async()=>stmt.run(...args),all:async()=>({results:stmt.all(...args)}),first:async()=>stmt.get(...args)||null};}};},async batch(stmts){if(race){const f=race;race=null;f();}sql.exec('BEGIN');try{for(const s of stmts)await s.run();sql.exec('COMMIT');}catch(e){sql.exec('ROLLBACK');throw e;}}};
  const app=createWorker(async t=>t),env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test'};
  const call=(uid,path,method,body)=>app.fetch(new Request('https://example.test'+path,{method,headers:{authorization:'Bearer '+uid,'content-type':'application/json'},body:JSON.stringify(body)}),env);
  for(const uid of ['alice','bob']) await call(uid,`/v1/firestore/document?path=users/${uid}`,'PUT',{data:{uid,friendsCount:0}});
  for(const uid of ['alice','bob']) sql.prepare("UPDATE friendbook_cf_firestore_documents SET document_json=json_set(document_json,'$.friendsCount',1) WHERE path=?").run(`users/${uid}`);
  sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)').run('friends/old','friends','old','alice',JSON.stringify({participants:['alice','bob']}),1);
  assert.equal(await hasD1Friendship(db,'alice','bob'),null);
  const req=await call('alice','/v1/firestore/document?parent=friend_requests','POST',{data:{fromUid:'alice',toUid:'bob',status:'pending'}});
  const id=(await req.json()).id;
  const ops=[{op:'update',path:`friend_requests/${id}`,updates:{status:'accepted'}},{op:'set',path:'friends/new',data:{participants:['bob','alice'],createdAt:{__fieldValue:'serverTimestamp'}}},...['alice','bob'].map(uid=>({op:'update',path:`users/${uid}`,updates:{friendsCount:{__fieldValue:'increment',value:1}}}))];
  assert.equal((await call('alice','/v1/firestore/batch','POST',{operations:ops})).status,403);
  race=()=>sql.prepare('UPDATE friendbook_cf_documents SET document_json=? WHERE path=?').run(JSON.stringify({participants:['alice','eve']}),'friends/old');
  assert.equal((await call('bob','/v1/firestore/batch','POST',{operations:ops})).status,409);
  assert.equal(await hasD1Friendship(db,'alice','bob'),null);
  sql.prepare('UPDATE friendbook_cf_documents SET document_json=? WHERE path=?').run(JSON.stringify({participants:['alice','bob']}),'friends/old');
  assert.equal((await call('bob','/v1/firestore/batch','POST',{operations:ops})).status,200);
  assert.equal(await hasD1Friendship(db,'alice','bob'),true);
  assert.equal(sql.prepare('SELECT friend_path FROM friendbook_cf_trusted_friendships').get().friend_path,'friends/old');
  assert.equal(sql.prepare("SELECT count(*) n FROM friendbook_cf_firestore_documents WHERE path='friends/new'").get().n,0);
  for(const uid of ['alice','bob']) assert.equal(JSON.parse(sql.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?').get(`users/${uid}`).document_json).friendsCount,1);
  assert.equal((await call('bob','/v1/firestore/batch','POST',{operations:ops})).status,403);
  sql.close();
});

test('administrator must explicitly review exact legacy group and chat snapshots before recovery', async () => {
  const sql=new DatabaseSync(':memory:');sql.exec(resourcesSchema);sql.exec(appSchema);sql.exec(schema);
  for(const n of ['0018_social_guards.sql','0020_trusted_groups.sql','0021_trusted_group_chats.sql','0022_social_recovery_audit.sql'])sql.exec(readFileSync(new URL(`../migrations/${n}`,import.meta.url),'utf8'));
  let race=null;
  const db={prepare(q){const stmt=sql.prepare(q);return{bind(...args){return{run:async()=>stmt.run(...args),all:async()=>({results:stmt.all(...args)}),first:async()=>stmt.get(...args)||null};}};},async batch(stmts){if(race){const f=race;race=null;f();}sql.exec('BEGIN');try{for(const s of stmts)await s.run();sql.exec('COMMIT');}catch(e){sql.exec('ROLLBACK');throw e;}}};
  const app=createWorker(async t=>t),env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test',PAYMENT_ADMIN_UIDS:'admin'};
  const call=(uid,path,method='GET',body)=>app.fetch(new Request('https://example.test'+path,{method,headers:{authorization:'Bearer '+uid,'content-type':'application/json'},...(body?{body:JSON.stringify(body)}:{})}),env);
  const old={name:'Old',privacy:'Private',adminId:'alice',adminIds:['alice','eve'],moderatorIds:['eve'],members:['alice','bob'],pendingMemberIds:['eve'],invitedUids:['eve'],bannedMemberIds:['mallory']};
  sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)').run('groups/old','groups','old','alice',JSON.stringify(old),1);
  const url='/v1/admin/recover-social?kind=group&id=old';
  assert.equal((await call('eve',url)).status,403);
  const preview=await (await call('admin',url)).json();
  assert.equal(preview.owner,'alice');assert.deepEqual(preview.participants,['alice','bob']);
  const body={kind:'group',id:'old',owner:'alice',revision:preview.revision,confirm:true};
  assert.equal((await call('eve','/v1/admin/recover-social','POST',body)).status,403);
  assert.equal((await call('admin','/v1/admin/recover-social','POST',{...body,confirm:false})).status,409);
  assert.equal((await call('admin','/v1/admin/recover-social','POST',{...body,owner:'eve'})).status,409);
  race=()=>sql.prepare('UPDATE friendbook_cf_documents SET document_json=? WHERE path=?').run(JSON.stringify({...old,name:'changed'}),'groups/old');
  assert.equal((await call('admin','/v1/admin/recover-social','POST',body)).status,409);
  assert.equal(sql.prepare('SELECT count(*) n FROM friendbook_cf_trusted_groups').get().n,0);
  const newer=await (await call('admin',url)).json();
  assert.equal((await call('admin','/v1/admin/recover-social','POST',{...body,revision:newer.revision})).status,200);
  const group=JSON.parse(sql.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?').get('groups/old').document_json);
  assert.deepEqual(group.members,['alice','bob']);assert.deepEqual(group.adminIds,['alice']);assert.deepEqual(group.moderatorIds,[]);assert.deepEqual(group.invitedUids,[]);
  assert.equal(sql.prepare('SELECT owner_uid FROM friendbook_cf_trusted_groups WHERE group_path=?').get('groups/old').owner_uid,'alice');
  const audit=sql.prepare('SELECT * FROM friendbook_cf_social_recovery_audit WHERE path=?').get('groups/old');assert.equal(audit.admin_uid,'admin');assert.equal(JSON.parse(audit.original_json).name,'changed');
  assert.equal((await call('admin','/v1/admin/recover-social','POST',{...body,revision:newer.revision})).status,409);
  const room={isGroup:true,createdBy:'bob',participants:['bob','alice'],adminIds:['bob','alice'],onlyAdminsCanSend:true};
  sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)').run('chats/old','chats','old','bob',JSON.stringify(room),1);
  const cp=await (await call('admin','/v1/admin/recover-social?kind=chat&id=old')).json();
  assert.equal((await call('admin','/v1/admin/recover-social','POST',{kind:'chat',id:'old',owner:'bob',revision:cp.revision,confirm:true})).status,200);
  assert.equal(sql.prepare('SELECT owner_uid FROM friendbook_cf_trusted_group_chats').get().owner_uid,'bob');
  assert.deepEqual(JSON.parse(sql.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?').get('chats/old').document_json).adminIds,['bob']);
  sql.close();
});

test('view counts enforce post privacy, filter batches and forbid unscoped top lists', async () => {
  const {resetViewState}=await import('../view-counters.js');resetViewState();
  const sql=new DatabaseSync(':memory:');sql.exec(resourcesSchema);sql.exec(appSchema);sql.exec(schema);
  const add=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
  for(const uid of ['alice','bob']) add.run(`users/${uid}`,'users','users',uid,uid,JSON.stringify({uid}),1,1);
  add.run('posts/public','posts','posts','public','alice',JSON.stringify({authorId:'alice',privacy:'Public'}),1,1);
  add.run('posts/secret','posts','posts','secret','alice',JSON.stringify({authorId:'alice',privacy:'Only me'}),1,1);
  const db={prepare(q){const stmt=sql.prepare(q);return{bind(...args){return{run:async()=>stmt.run(...args),all:async()=>({results:stmt.all(...args)}),first:async()=>stmt.get(...args)||null};}};}};
  const events=[],env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test',ANALYTICS_ENGINE:{writeDataPoint:e=>events.push(e)}};
  const app=createWorker(async t=>t);
  const call=(uid,path,method='GET')=>app.fetch(new Request('https://example.test'+path,{method,headers:{authorization:'Bearer '+uid}}),env,{waitUntil:()=>{}});
  assert.equal((await call('bob','/v1/views/posts/secret','POST')).status,403);
  assert.equal((await call('bob','/v1/views/posts/missing')).status,403);
  assert.equal(events.length,0);
  assert.equal((await call('bob','/v1/views/posts/public','POST')).status,200);
  assert.equal(events.length,1);
  const batch=await (await call('bob','/v1/views?collection=posts&ids=public,secret,missing')).json();
  assert.deepEqual(Object.keys(batch.views),['public']);
  assert.equal((await call('bob','/v1/views?collection=posts&top=10')).status,400);
  assert.equal((await call('alice','/v1/views/posts/secret')).status,200);
  resetViewState();sql.close();
});

test('direct chat replies accept reversed participant order without adding a new member', async () => {
  const sql=new DatabaseSync(':memory:');sql.exec(resourcesSchema);sql.exec(appSchema);sql.exec(schema);sql.exec(socialGuardSchema);
  const db={prepare(q){const s=sql.prepare(q);return{bind(...a){return{run:async()=>s.run(...a),all:async()=>({results:s.all(...a)}),first:async()=>s.get(...a)||null};}};},async batch(stmts){sql.exec('BEGIN');try{for(const s of stmts)await s.run();sql.exec('COMMIT');}catch(e){sql.exec('ROLLBACK');throw e;}}};
  const app=createWorker(async t=>t),env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test'};
  const batch=(uid,ops)=>app.fetch(new Request('https://example.test/v1/firestore/batch',{method:'POST',headers:{authorization:'Bearer '+uid,'content-type':'application/json'},body:JSON.stringify({operations:ops})}),env);
  const send=(uid,other,msg)=>[{op:'set',path:`chats/direct/messages/${msg}`,data:{senderId:uid,text:msg}},{op:'set',path:'chats/direct',merge:true,data:{participants:[uid,other],lastMessage:msg}}];
  assert.equal((await batch('alice',send('alice','bob','hello'))).status,200);
  assert.equal((await batch('bob',send('bob','alice','reply'))).status,200);
  assert.equal(JSON.parse(sql.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?').get('chats/direct/messages/reply').document_json).text,'reply');
  assert.equal((await batch('bob',send('bob','eve','forged'))).status,403);
  sql.close();
});

test('sender create-only notifications are atomic and never overwrite recipient read state', async () => {
  const sql=new DatabaseSync(':memory:');sql.exec(resourcesSchema);sql.exec(appSchema);sql.exec(schema);sql.exec(socialGuardSchema);
  let race=false;
  const db={prepare(q){const s=sql.prepare(q);return{bind(...a){return{run:async()=>s.run(...a),all:async()=>({results:s.all(...a)}),first:async()=>s.get(...a)||null};}};},async batch(stmts){if(race){race=false;sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)').run('notifications/race','notifications','race','alice',JSON.stringify({fromUserId:'alice',toUserId:'bob',isRead:true,text:'winner'}),1);}sql.exec('BEGIN');try{for(const s of stmts)await s.run();sql.exec('COMMIT');}catch(e){sql.exec('ROLLBACK');throw e;}}};
  const app=createWorker(async t=>t),env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test'};
  const req=(uid,id,method,body)=>app.fetch(new Request('https://example.test/v1/firestore/document?path=notifications/'+id,{method,headers:{authorization:'Bearer '+uid,'content-type':'application/json'},...(body?{body:JSON.stringify(body)}:{})}),env);
  const data={fromUserId:'alice',toUserId:'bob',isRead:false,text:'hello'};
  assert.equal((await req('alice','n','PUT',{data,createOnly:true})).status,200);
  assert.equal((await req('alice','n','GET')).status,403);
  assert.equal((await req('bob','n','PATCH',{fields:{isRead:true}})).status,200);
  assert.equal((await req('alice','n','PUT',{data,createOnly:true})).status,409);
  assert.equal(JSON.parse(sql.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?').get('notifications/n').document_json).isRead,true);
  race=true;assert.equal((await req('alice','race','PUT',{data,createOnly:true})).status,409);
  assert.equal(sql.prepare('SELECT path FROM friendbook_cf_firestore_documents WHERE path=?').get('notifications/race'),undefined);
  assert.equal(JSON.parse(sql.prepare('SELECT document_json FROM friendbook_cf_documents WHERE path=?').get('notifications/race').document_json).text,'winner');
  sql.close();
});

test('chat message query checks room membership once, not once per message', async () => {
  const sql=new DatabaseSync(':memory:');sql.exec(resourcesSchema);sql.exec(appSchema);sql.exec(schema);
  const insert=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
  insert.run('chats/room','chats','chats','room','alice',JSON.stringify({participants:['alice','bob']}),1,1);
  for(let i=0;i<25;i++)insert.run(`chats/room/messages/m${i}`,'chats/room/messages','messages',`m${i}`,'alice',JSON.stringify({senderId:'alice',text:'x'}),1,1);
  let lookups=0;
  const db={prepare(q){const s=sql.prepare(q);return{bind(...a){return{run:async()=>s.run(...a),all:async()=>({results:s.all(...a)}),first:async()=>{if(q.includes('WHERE path = ?') && a[0]==='chats/room')lookups++;return s.get(...a)||null;}};}};}};
  const app=createWorker(async t=>t),env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test'};
  const query=uid=>app.fetch(new Request('https://example.test/v1/firestore/query',{method:'POST',headers:{authorization:'Bearer '+uid,'content-type':'application/json'},body:JSON.stringify({parent:'chats/room/messages',limit:10})}),env);
  const res=await query('bob');assert.equal(res.status,200);assert.equal((await res.json()).documents.length,10);assert.equal(lookups,1);
  assert.equal((await query('eve')).status,403);
  sql.close();
});

test('query cursor bounds work for prefixes, ties and deleted page anchors', async () => {
  const sql=new DatabaseSync(':memory:'); sql.exec(resourcesSchema);sql.exec(appSchema);sql.exec(schema);
  const add=sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)');
  for(const [id,name] of [['u1','Alice'],['u2','Alina'],['u3','Bob'],['u4','Bobby'],['u5','Charlie']])
    add.run(`users/${id}`,'users',id,id,JSON.stringify({uid:id,displayName:name}),1);
  const db={prepare(query){const stmt=sql.prepare(query);return{bind(...args){return{run:async()=>stmt.run(...args),all:async()=>({results:stmt.all(...args)}),first:async()=>stmt.get(...args)||null};}};}};
  const app=createWorker(async()=> 'u1');
  const query=async spec=>{
    const result=await app.fetch(new Request('https://example.test/v1/firestore/query',{
      method:'POST',headers:{authorization:'Bearer test','content-type':'application/json'},
      body:JSON.stringify({parent:'users',orderBy:[{field:'displayName',direction:'ASCENDING'}],...spec})
    }),{DB:db,FIREBASE_PROJECT_ID:'friendbook-test'});
    assert.equal(result.status,200);return (await result.json()).documents.map(d=>d.id);
  };
  assert.deepEqual(await query({startAt:['Bo'],endAt:['Bo\uf8ff']}),['u3','u4']);
  assert.deepEqual(await query({startAfter:['Bob']}),['u4','u5']);
  assert.deepEqual(await query({endBefore:['Bob']}),['u1','u2']);
  assert.deepEqual(await query({startAfter:['Bob','u3']}),['u4','u5']);
  add.run('users/u6','users','u6','u6',JSON.stringify({uid:'u6',displayName:'Bob'}),1);
  assert.deepEqual(await query({startAt:['Bob'],endAt:['Bob']}),['u3','u6']);
  assert.deepEqual(await query({startAfter:['Bob']}),['u4','u5']);
  assert.deepEqual(await query({startAfter:['Bob','u3']}),['u6','u4','u5']);
  assert.deepEqual(await query({orderBy:[{field:'displayName',direction:'DESCENDING'}],startAfter:['Bob','u6']}),['u3','u2','u1']);
  sql.prepare('DELETE FROM friendbook_cf_documents WHERE path=?').run('users/u3');
  assert.deepEqual(await query({startAfter:['Bob','u3']}),['u6','u4','u5']);
  assert.deepEqual(await query({startAt:['ZZZ']}),[]);
  sql.close();
});


test('stories load with owner and privacy checks without exposing viewers', async () => {
  const sql=new DatabaseSync(':memory:');sql.exec(resourcesSchema);sql.exec(appSchema);sql.exec(schema);sql.exec(socialGuardSchema);
  const db={prepare(query){const stmt=sql.prepare(query);return{bind(...args){return{run:async()=>stmt.run(...args),all:async()=>({results:stmt.all(...args)}),first:async()=>stmt.get(...args)||null};}};},async batch(stmts){sql.exec('BEGIN');try{for(const st of stmts)await st.run();sql.exec('COMMIT');}catch(e){sql.exec('ROLLBACK');throw e;}}};
  const add=sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)');
  for(const uid of ['alice','bob'])add.run(`users/${uid}`,'users',uid,uid,JSON.stringify({uid}),1);
  const app=createWorker(async token=>token),env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test'};
  const call=(uid,path,method='GET',body)=>app.fetch(new Request('https://example.test'+path,{method,headers:{authorization:'Bearer '+uid,'content-type':'application/json'},...(body?{body:JSON.stringify(body)}:{})}),env);
  const story={authorId:'alice',privacy:'Public',caption:'hello',viewedBy:['bob'],reactions:{bob:'heart'},expiresAt:{__type:'timestamp',millis:2000000000000}};
  assert.equal((await call('alice','/v1/firestore/document?path=stories/s1','PUT',{data:story})).status,200);
  assert.equal((await call('bob','/v1/firestore/document?path=stories/s1','PATCH',{fields:{caption:'forged'}})).status,403);
  assert.equal((await call('bob','/v1/docs/stories/s1','PUT',{caption:'forged'})).status,403);
  const read=await call('bob','/v1/firestore/document?path=stories/s1');assert.equal(read.status,200);
  const data=(await read.json()).data;assert.equal(data.caption,'hello');assert.equal(data.viewedBy,undefined);assert.equal(data.reactions,undefined);
  const query=await call('bob','/v1/firestore/query','POST',{parent:'stories',filters:[{field:'expiresAt',op:'>',value:{__type:'timestamp',millis:1}}]});
  assert.equal(query.status,200);assert.equal((await query.json()).documents.length,1);
  const listing=await call('bob','/v1/docs?collection=stories');assert.equal((await listing.json()).documents[0].viewedBy,undefined);
  assert.equal((await call('alice','/v1/firestore/document?path=stories/s1','PATCH',{fields:{privacy:'Only me'}})).status,200);
  assert.equal((await call('bob','/v1/firestore/document?path=stories/s1')).status,403);
  assert.equal((await (await call('bob','/v1/firestore/query','POST',{parent:'stories'})).json()).documents.length,0);
  assert.equal((await (await call('bob','/v1/docs?collection=stories')).json()).documents.length,0);
  assert.equal((await call('alice','/v1/firestore/document?path=stories/s1','DELETE')).status,200);
  sql.close();
});

test('chat send retries rolled-back room metadata conflicts without duplicating a message', async () => {
  const sql=new DatabaseSync(':memory:');sql.exec(resourcesSchema);sql.exec(appSchema);sql.exec(schema);sql.exec(socialGuardSchema);
  let race=false;
  const db={prepare(q){const stmt=sql.prepare(q);return{bind(...a){return{run:async()=>stmt.run(...a),all:async()=>({results:stmt.all(...a)}),first:async()=>stmt.get(...a)||null};}};},async batch(stmts){
    if(race){race=false;sql.prepare("UPDATE friendbook_cf_firestore_documents SET document_json=json_set(document_json,'$.otherUpdate','new') WHERE path='chats/room'").run();}
    sql.exec('BEGIN');try{for(const st of stmts)await st.run();sql.exec('COMMIT');}catch(e){sql.exec('ROLLBACK');throw e;}
  }};
  const app=createWorker(async t=>t),env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test'};
  const call=(uid,ops)=>app.fetch(new Request('https://example.test/v1/firestore/batch',{method:'POST',headers:{authorization:'Bearer '+uid,'content-type':'application/json'},body:JSON.stringify({operations:ops})}),env);
  const send=[{op:'set',path:'chats/room/messages/m1',data:{senderId:'alice',text:'hello'}},{op:'set',path:'chats/room',merge:true,data:{participants:['alice','bob'],unreadCount:{bob:{__fieldValue:'increment',value:1}}}}];
  assert.equal((await call('alice',[{op:'set',path:'chats/room',data:{participants:['alice','bob'],unreadCount:{bob:0}}}])).status,200);
  race=true;const sent=await call('alice',send);assert.equal(sent.status,200,await sent.text());
  assert.equal(sql.prepare("SELECT COUNT(*) n FROM friendbook_cf_firestore_documents WHERE path='chats/room/messages/m1'").get().n,1);
  const room=JSON.parse(sql.prepare("SELECT document_json FROM friendbook_cf_firestore_documents WHERE path='chats/room'").get().document_json);
  assert.equal(room.unreadCount.bob,1);assert.equal(room.otherUpdate,'new');
  sql.close();
});

test('pending friend requests are atomically unique per sender and recipient', async () => {
 const sql=new DatabaseSync(':memory:');sql.exec(resourcesSchema);sql.exec(appSchema);sql.exec(schema);sql.exec(socialGuardSchema);
 sql.exec(readFileSync(new URL('../migrations/0023_friend_request_lookup_indexes.sql',import.meta.url),'utf8'));
 const db={prepare(q){const stmt=sql.prepare(q);return{bind(...a){return{run:async()=>stmt.run(...a),all:async()=>({results:stmt.all(...a)}),first:async()=>stmt.get(...a)||null};}};},async batch(stmts){sql.exec('BEGIN');try{for(const st of stmts)await st.run();sql.exec('COMMIT');}catch(e){sql.exec('ROLLBACK');throw e;}}};
 const app=createWorker(async token=>token),env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test'};
 const send=to=>app.fetch(new Request('https://example.test/v1/firestore/document?parent=friend_requests',{method:'POST',headers:{authorization:'Bearer alice','content-type':'application/json'},body:JSON.stringify({data:{fromUid:'alice',toUid:to,status:'pending'}})}),env);
 const first=await send('bob');assert.equal(first.status,201);const id=(await first.json()).id;
 assert.equal((await send('bob')).status,409);assert.equal((await send('carol')).status,201);
 assert.equal(sql.prepare("SELECT COUNT(*) n FROM friendbook_cf_firestore_documents WHERE parent_path='friend_requests'").get().n,2);
 const remove=await app.fetch(new Request(`https://example.test/v1/firestore/document?path=friend_requests/${id}`,{method:'DELETE',headers:{authorization:'Bearer bob'}}),env);
 assert.equal(remove.status,200);assert.equal((await send('bob')).status,201);
 sql.close();
});

test('legacy chat lookup uses both indexes and returns the same rows as the former scan', () => {
 const sql=new DatabaseSync(':memory:');sql.exec(appSchema);
 sql.exec(readFileSync(new URL('../migrations/0019_legacy_query_indexes.sql',import.meta.url),'utf8'));
 sql.exec(readFileSync(new URL('../migrations/0024_legacy_chat_prefix_index.sql',import.meta.url),'utf8'));
 const add=sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)');
 for(let i=0;i<5000;i++) {const id=`chat${i}_m`;add.run('messages/'+id,'messages',id,'u',JSON.stringify({chatId:'chat'+i}),1);}
 for(const id of ['chat42_😀','chat42_x','chat42`x','other'])add.run('messages/'+id,'messages',id,'u',JSON.stringify({chatId:id==='other'?'chat42':'other'}),1);
 sql.exec('ANALYZE');
 const previous="SELECT path FROM friendbook_cf_documents WHERE collection='messages' AND (substr(doc_id,1,?)=? OR json_extract(document_json,'$.chatId')=?)";
 const next="SELECT path FROM friendbook_cf_documents WHERE collection='messages' AND doc_id>=? AND doc_id<? UNION ALL SELECT path FROM friendbook_cf_documents WHERE collection='messages' AND json_extract(document_json,'$.chatId')=? AND NOT (doc_id>=? AND doc_id<?)";
 const prefix='chat42_',upper='chat42`',args=[prefix,upper,'chat42',prefix,upper];
 const before=sql.prepare(previous).all(prefix.length,prefix,'chat42').map(row=>row.path).sort();
 const after=sql.prepare(next).all(...args).map(row=>row.path).sort();
 assert.deepEqual(after,before);assert.equal(new Set(after).size,after.length);
 const plan=sql.prepare('EXPLAIN QUERY PLAN '+next).all(...args).map(row=>row.detail).join('\n');
 assert.match(plan,/idx_friendbook_cf_documents_message_doc_id/);
 assert.match(plan,/idx_friendbook_cf_documents_message_chat_id/);
 sql.close();
});

test('inbox narrowing preserves canonical precedence and private legacy compatibility', async () => {
 const sql=new DatabaseSync(':memory:');sql.exec(resourcesSchema);sql.exec(appSchema);sql.exec(schema);
 sql.exec(readFileSync(new URL('../migrations/0025_inbox_recipient_indexes.sql',import.meta.url),'utf8'));
 const canon=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
 const legacy=sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)');
 function a(id,data){legacy.run('notifications/'+id,'notifications',id,'',JSON.stringify(data),1);}
 function f(id,data){canon.run('notifications/'+id,'notifications','notifications',id,'',JSON.stringify(data),1,1);}
 f('mine',{toUserId:'alice'});f('notmine',{toUserId:'bob'});a('notmine',{toUserId:'alice'});
 f('override',{toUserId:'alice'});a('override',{toUserId:'bob'});
 a('raw',{toUserId:'alice'});a('wrapped',{fields:{toUserId:{stringValue:'alice'}}});a('hidden',{toUserId:'bob'});
 const db={prepare(q){const stmt=sql.prepare(q);return{bind(...a){return{all:async()=>({results:stmt.all(...a)}),first:async()=>stmt.get(...a)||null};}};}};
 const app=createWorker(async token=>token),env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test'};
 const request=(token)=>new Request('https://example.test/v1/firestore/query',{method:'POST',headers:{authorization:'Bearer '+token,'content-type':'application/json'},body:JSON.stringify({parent:'notifications'})});
 const alice=await app.fetch(request('alice'),env);assert.equal(alice.status,200);
 assert.deepEqual((await alice.json()).documents.map(d=>d.id).sort(),['mine','override','raw','wrapped']);
 const bob=await app.fetch(request('bob'),env);assert.deepEqual((await bob.json()).documents.map(d=>d.id).sort(),['hidden','notmine']);
 const query="SELECT path FROM friendbook_cf_firestore_documents WHERE parent_path='notifications' AND json_extract(document_json,'$.toUserId')=?";
 assert.match(sql.prepare('EXPLAIN QUERY PLAN '+query).all('alice').map(x=>x.detail).join(),/idx_cf_firestore_inbox_recipient/);
 sql.close();
});

test('indexed profile and request reads preserve privacy and canonical precedence', async () => {
 const sql=new DatabaseSync(':memory:');sql.exec(resourcesSchema);sql.exec(appSchema);sql.exec(schema);
 sql.exec(readFileSync(new URL('../migrations/0026_profile_request_indexes.sql',import.meta.url),'utf8'));
 const a=sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)');const f=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
 const legacy=(collection,id,data)=>a.run(`${collection}/${id}`,collection,id,'',JSON.stringify(data),1);
 const canon=(collection,id,data)=>f.run(`${collection}/${id}`,collection,collection,id,'',JSON.stringify(data),1,1);
 for(const uid of ['alice','bob','eve'])legacy('users',uid,{uid});
 canon('posts','p1',{authorId:'alice',privacy:'Public'});canon('posts','private',{authorId:'alice',privacy:'Only me'});
 canon('posts','stale',{authorId:'bob',privacy:'Public'});legacy('posts','stale',{authorId:'alice',privacy:'Public'});
 legacy('posts','old',{fields:{authorId:{stringValue:'alice'},privacy:{stringValue:'Public'}}});
 canon('friend_requests','r1',{fromUid:'alice',toUid:'bob',status:'pending'});
 canon('friend_requests','r2',{fromUid:'eve',toUid:'bob',status:'pending'});legacy('friend_requests','r2',{fromUid:'alice',toUid:'bob'});
 legacy('friend_requests','old',{fields:{fromUid:{stringValue:'alice'},toUid:{stringValue:'eve'}}});
 const db={prepare(q){const stmt=sql.prepare(q);return{bind(...args){return{all:async()=>({results:stmt.all(...args)}),first:async()=>stmt.get(...args)||null};}};}};
 const app=createWorker(async t=>t),env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test'};
 const query=async(uid,parent,filters=[])=>{const r=await app.fetch(new Request('https://example.test/v1/firestore/query',{method:'POST',headers:{authorization:'Bearer '+uid,'content-type':'application/json'},body:JSON.stringify({parent,filters})}),env);assert.equal(r.status,200);return (await r.json()).documents.map(d=>d.id).sort();};
 assert.deepEqual(await query('bob','posts',[{field:'authorId',op:'==',value:'alice'}]),['old','p1']);
 assert.deepEqual(await query('alice','posts',[{field:'authorId',op:'==',value:'alice'}]),['old','p1','private']);
 assert.deepEqual(await query('alice','friend_requests'),['old','r1']);
 assert.deepEqual(await query('eve','friend_requests'),['old','r2']);
 const q="SELECT path FROM friendbook_cf_firestore_documents WHERE parent_path='friend_requests' AND json_extract(document_json,'$.fromUid')=? UNION ALL SELECT path FROM friendbook_cf_firestore_documents WHERE parent_path='friend_requests' AND json_extract(document_json,'$.toUid')=? AND json_extract(document_json,'$.fromUid') IS NOT ?";
 const plan=sql.prepare('EXPLAIN QUERY PLAN '+q).all('alice','alice','alice').map(x=>x.detail).join('\n');assert.match(plan,/SEARCH.*idx_cf_request_fromUid/);assert.match(plan,/SEARCH.*idx_cf_request_toUid/);
 sql.close();
});

test('comments and reactions authorize parent once while enforcing post privacy', async () => {
 const sql=new DatabaseSync(':memory:');sql.exec(resourcesSchema);sql.exec(appSchema);sql.exec(schema);
 const a=sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)');
 for(const uid of ['alice','bob'])a.run(`users/${uid}`,'users',uid,uid,JSON.stringify({uid}),1);
 a.run('posts/p','posts','p','alice',JSON.stringify({authorId:'alice',privacy:'Public'}),1);
 const f=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
 for(let i=0;i<30;i++)f.run(`posts/p/comments/c${i}`,'posts/p/comments','comments',`c${i}`,'alice',JSON.stringify({content:'x'}),1,1);
 let postReads=0;const db={prepare(q){const s=sql.prepare(q);return{bind(...args){return{all:async()=>({results:s.all(...args)}),first:async()=>{if(q.includes('SELECT document_json') && args[0]==='posts/p')postReads++;return s.get(...args)||null;}};}};}};
 const app=createWorker(async t=>t),env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test'};
 const query=()=>app.fetch(new Request('https://example.test/v1/firestore/query',{method:'POST',headers:{authorization:'Bearer bob','content-type':'application/json'},body:JSON.stringify({parent:'posts/p/comments',limit:10})}),env);
 let r=await query();assert.equal((await r.json()).documents.length,10);assert.equal(postReads,1); // one lookup including the legacy fallback
 a.run('posts/other','posts','other','alice',JSON.stringify({authorId:'alice'}),1);
 sql.prepare('UPDATE friendbook_cf_documents SET document_json=? WHERE path=?').run(JSON.stringify({authorId:'alice',privacy:'Only me'}),'posts/p');
 r=await query();assert.deepEqual((await r.json()).documents,[]);
 // A stale public mirror must not override a private canonical post.
 sql.prepare('UPDATE friendbook_cf_documents SET document_json=? WHERE path=?').run(JSON.stringify({authorId:'alice',privacy:'Public'}),'posts/p');
 f.run('posts/p','posts','posts','p','alice',JSON.stringify({authorId:'alice',privacy:'Only me'}),1,1);
 r=await query();assert.deepEqual((await r.json()).documents,[]);assert.equal(postReads,3);
 sql.close();
});

test('feed coalesces repeated author and viewer checks per request but refreshes privacy next time', async () => {
 const sql=new DatabaseSync(':memory:');sql.exec(resourcesSchema);sql.exec(appSchema);sql.exec(schema);
 const add=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
 function insert(collection,id,data){add.run(`${collection}/${id}`,collection,collection,id,'',JSON.stringify(data),1,1);}
 insert('users','alice',{uid:'alice'});insert('users','bob',{uid:'bob'});
 for(let i=0;i<25;i++)insert('posts',`p${i}`,{authorId:'alice',privacy:'Public',createdAt:i});
 const reads={};const db={prepare(q){const stmt=sql.prepare(q);return{bind(...args){return{all:async()=>({results:stmt.all(...args)}),first:async()=>{if(q.includes('SELECT document_json') && String(args[0]).startsWith('users/'))reads[args[0]]=(reads[args[0]]||0)+1;return stmt.get(...args)||null;}};}};}};
 const app=createWorker(async t=>t),env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test'};
 const query=()=>app.fetch(new Request('https://example.test/v1/firestore/query',{method:'POST',headers:{authorization:'Bearer bob','content-type':'application/json'},body:JSON.stringify({parent:'posts',limit:10})}),env);
 // The request-level account check and feed ACL share one fresh viewer lookup.
 let response=await query();assert.equal((await response.json()).documents.length,10);assert.equal(reads['users/bob'],1);assert.equal(reads['users/alice'],1);
 sql.prepare('UPDATE friendbook_cf_firestore_documents SET document_json=? WHERE path=?').run(JSON.stringify({uid:'alice',blocked:['bob']}),'users/alice');
 response=await query();assert.deepEqual((await response.json()).documents,[]);assert.equal(reads['users/bob'],2);assert.equal(reads['users/alice'],2);
 sql.close();
});

test('friendship reads use participant indexes without leaking or reviving stale mirrors', async () => {
 const sql=new DatabaseSync(':memory:');sql.exec(resourcesSchema);sql.exec(appSchema);sql.exec(schema);sql.exec(readFileSync(new URL('../migrations/0027_friend_participant_indexes.sql',import.meta.url),'utf8'));
 const a=sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)'),f=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
 const legacy=(id,data)=>a.run(`friends/${id}`,'friends',id,'',JSON.stringify(data),1);
 const canonical=(id,data)=>f.run(`friends/${id}`,'friends','friends',id,'',JSON.stringify(data),1,1);
 canonical('first',{participants:['alice','bob'],secret:'hide'});canonical('second',{participants:['carol','alice']});
 canonical('shadow',{participants:['bob','carol']});legacy('shadow',{participants:['alice','bob']});
 legacy('wrapped',{fields:{participants:{arrayValue:{values:[{stringValue:'bob'},{stringValue:'alice'}]}}}});
 legacy('malformed',{participants:['alice','alice']});legacy('other',{participants:['bob','carol']});
 const db={prepare(q){const stmt=sql.prepare(q);return{bind(...args){return{all:async()=>({results:stmt.all(...args)}),first:async()=>stmt.get(...args)||null};}};}};
 const app=createWorker(async t=>t),env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test'};
 const r=await app.fetch(new Request('https://example.test/v1/firestore/query',{method:'POST',headers:{authorization:'Bearer alice','content-type':'application/json'},body:JSON.stringify({parent:'friends'})}),env);
 assert.equal(r.status,200);const docs=(await r.json()).documents;assert.deepEqual(docs.map(x=>x.id).sort(),['first','second','wrapped']);assert.equal(docs.find(d=>d.id==='first').data.secret,undefined);
 const q="SELECT path FROM friendbook_cf_firestore_documents WHERE parent_path='friends' AND json_extract(document_json,'$.participants[0]')=? UNION ALL SELECT path FROM friendbook_cf_firestore_documents WHERE parent_path='friends' AND json_extract(document_json,'$.participants[1]')=? AND json_extract(document_json,'$.participants[0]') IS NOT ?";
 const plan=sql.prepare('EXPLAIN QUERY PLAN '+q).all('alice','alice','alice').map(x=>x.detail).join('\n');assert.match(plan,/SEARCH.*idx_cf_friend_participant_0/);assert.match(plan,/SEARCH.*idx_cf_friend_participant_1/);
 sql.close();
});

test('profile bootstrap combines first page with ordinary projection and post privacy', async () => {
 const sql=new DatabaseSync(':memory:');sql.exec(resourcesSchema);sql.exec(appSchema);sql.exec(schema);
 const insert=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
 const add=(coll,id,data)=>insert.run(`${coll}/${id}`,coll,coll,id,id,JSON.stringify(data),1,1);
 add('users','alice',{uid:'alice',displayName:'Alice',email:'private'});add('users','bob',{uid:'bob'});
 add('posts','p1',{authorId:'alice',privacy:'Public'});add('posts','p2',{authorId:'alice',privacy:'Only me'});
 add('posts','other',{authorId:'bob',privacy:'Public'});
 const db={prepare(q){const s=sql.prepare(q);return{bind(...args){return{all:async()=>({results:s.all(...args)}),first:async()=>s.get(...args)||null};}};}};
 const app=createWorker(async t=>t),env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test'};
 const req=uid=>new Request('https://example.test/v1/firestore/profile-bootstrap?uid='+uid,{headers:{authorization:'Bearer bob'}});
 const result=await app.fetch(req('alice'),env);assert.equal(result.status,200);const body=await result.json();
 assert.equal(body.profile.status,200);assert.equal(body.profile.body.data.displayName,'Alice');assert.equal(body.profile.body.data.email,undefined);
 assert.equal(body.posts.status,200);assert.deepEqual(body.posts.body.documents.map(d=>d.id),['p1']);
 const invalid=await app.fetch(req(encodeURIComponent('a/b')),env);assert.equal(invalid.status,400);
 const unauth=await app.fetch(new Request('https://example.test/v1/firestore/profile-bootstrap?uid=alice'),env);assert.equal(unauth.status,401);
 sql.close();
});

test('profile bootstrap selects the newest visible posts before limiting', async () => {
 const sql=new DatabaseSync(':memory:');sql.exec(resourcesSchema);sql.exec(appSchema);sql.exec(schema);
 const insert=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
 const add=(coll,id,data)=>insert.run(`${coll}/${id}`,coll,coll,id,'alice',JSON.stringify(data),1,1);
 add('users','alice',{uid:'alice',displayName:'Alice'});add('users','bob',{uid:'bob'});
 for(let i=0;i<12;i++) add('posts',`p${String(11-i).padStart(2,'0')}`,{authorId:'alice',privacy:'Public',createdAt:i+1});
 add('posts','private',{authorId:'alice',privacy:'Only me',createdAt:20});
 const db={prepare(q){const s=sql.prepare(q);return{bind(...args){return{all:async()=>({results:s.all(...args)}),first:async()=>s.get(...args)||null};}};}};
 const app=createWorker(async t=>t),env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test'};
 const r=await app.fetch(new Request('https://example.test/v1/firestore/profile-bootstrap?uid=alice',{headers:{authorization:'Bearer bob'}}),env);
 assert.equal(r.status,200);const posts=(await r.json()).posts.body.documents;
 assert.deepEqual(posts.map(p=>p.data.createdAt),[12,11,10,9,8,7,6]);
 sql.close();
});

test('bounded chat pages match full merge across ties, cursors and legacy fallback', async () => {
 const sql=new DatabaseSync(':memory:');sql.exec(resourcesSchema);sql.exec(appSchema);sql.exec(schema);
 sql.exec(readFileSync(new URL('../migrations/0019_legacy_query_indexes.sql',import.meta.url),'utf8'));
 sql.exec(readFileSync(new URL('../migrations/0024_legacy_chat_prefix_index.sql',import.meta.url),'utf8'));
 sql.exec(readFileSync(new URL('../migrations/0028_chat_history_page_index.sql',import.meta.url),'utf8'));
 const f=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
 f.run('chats/r','chats','chats','r','alice',JSON.stringify({participants:['alice','bob']}),1,1);
 for(let i=0;i<87;i++) {let id='m'+String(i).padStart(3,'0'),createdAt=i<5?10:{__type:'timestamp',millis:Math.floor(i/3)};f.run(`chats/r/messages/${id}`,'chats/r/messages','messages',id,'alice',JSON.stringify({senderId:'alice',createdAt}),1,1);}
 let fastCalls=0, fullCalls=0;
 const db={prepare(q){const s=sql.prepare(q);return{bind(...args){return{all:async()=>{if(q.includes('ORDER BY CASE WHEN json_type(document_json'))fastCalls++;if(q.includes('WHERE parent_path = ?'))fullCalls++;return{results:s.all(...args)};},first:async()=>s.get(...args)||null};}};}};
 const app=createWorker(async t=>t),env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test'};
 const query=async(spec,forceFull=false,uid='bob')=>{const body={parent:'chats/r/messages',...spec};if(forceFull)body.filters=[{field:'senderId',op:'==',value:'alice'}];const r=await app.fetch(new Request('https://example.test/v1/firestore/query',{method:'POST',headers:{authorization:'Bearer '+uid,'content-type':'application/json'},body:JSON.stringify(body)}),env);if(uid==='eve'){assert.equal(r.status,403);return [];}assert.equal(r.status,200);return (await r.json()).documents.map(d=>d.id);};
 const first={orderBy:[{field:'createdAt',direction:'ASCENDING'}],limit:10,limitToLast:true};
 assert.deepEqual(await query(first),await query(first,true));
 const older={orderBy:[{field:'createdAt',direction:'DESCENDING'}],limit:11,startAfter:[{__type:'timestamp',millis:20},'m061']};
 assert.deepEqual(await query(older),await query(older,true));assert.equal(fastCalls,2);await query(first,false,'eve');
 const plan=sql.prepare("EXPLAIN QUERY PLAN SELECT path FROM friendbook_cf_firestore_documents WHERE parent_path=? ORDER BY CASE WHEN json_type(document_json,'$.createdAt') IN ('integer','real') THEN 2 ELSE 4 END DESC, CASE WHEN json_type(document_json,'$.createdAt') IN ('integer','real') THEN json_extract(document_json,'$.createdAt') ELSE json_extract(document_json,'$.createdAt.millis') END DESC,doc_id DESC LIMIT ?").all('chats/r/messages',10).map(x=>x.detail).join('\n');
 assert.match(plan,/idx_cf_firestore_chat_created_page/);
 // Legacy bridge triggers the existing full merge instead of silently losing old messages.
 sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)').run('messages/r_old','messages','r_old','alice',JSON.stringify({senderId:'alice',chatId:'r',createdAt:100}),1);
 assert.deepEqual(await query(first),await query(first,true));assert.equal(fastCalls,2);
 sql.prepare("DELETE FROM friendbook_cf_documents WHERE path='messages/r_old'").run();
 f.run('chats/r/messages/weird','chats/r/messages','messages','weird','alice',JSON.stringify({senderId:'alice',createdAt:'old'}),1,1);
 assert.deepEqual(await query(first),await query(first,true));assert.equal(fastCalls,2);assert.ok(fullCalls>0);
 sql.close();
});

test('bounded feed pages fill after privacy checks and preserve legacy precedence and cursors', async () => {
 const sql=new DatabaseSync(':memory:');sql.exec(resourcesSchema);sql.exec(appSchema);sql.exec(schema);
 sql.exec(readFileSync(new URL('../migrations/0029_feed_page_indexes.sql',import.meta.url),'utf8'));
 const f=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)'),a=sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)');
 const user=(id,data)=>f.run('users/'+id,'users','users',id,id,JSON.stringify(data),1,1);user('alice',{uid:'alice'});user('bob',{uid:'bob'});
 for(let i=0;i<100;i++){
  const id='p'+String(i).padStart(3,'0'),createdAt={__type:'timestamp',millis:Math.floor(i/3)};
  const data={authorId:'alice',privacy:i>60?'Only me':'Public',createdAt,content:'canonical'};
  if(i%2===0) f.run('posts/'+id,'posts','posts',id,'alice',JSON.stringify(data),1,1);
  else a.run('posts/'+id,'posts',id,'alice',JSON.stringify(data),1);
 }
 // A stale public mirror cannot override the newer private canonical row.
 a.run('posts/p098','posts','p098','alice',JSON.stringify({authorId:'alice',privacy:'Public',createdAt:{__type:'timestamp',millis:100}}),1);
 let batchReads=0,maxRows=0,postReads=0;
 const db={prepare(q){const stmt=sql.prepare(q);return{bind(...args){return{all:async()=>{const results=stmt.all(...args);if(q.includes('ORDER BY CASE WHEN json_type(a.document_json')){batchReads++;maxRows=Math.max(maxRows,results.length);}return{results};},first:async()=>{if(q.includes('SELECT document_json') && String(args[0]).startsWith('posts/'))postReads++;return stmt.get(...args)||null;}};}};}};
 const app=createWorker(async t=>t),env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test'};
 const query=async(spec,full=false)=>{let body={parent:'posts',orderBy:[{field:'createdAt',direction:'DESCENDING'}],limit:12,...spec};if(full)body.filters=[{field:'privacy',op:'!=',value:'Never'}];const r=await app.fetch(new Request('https://example.test/v1/firestore/query',{method:'POST',headers:{authorization:'Bearer bob','content-type':'application/json'},body:JSON.stringify(body)}),env);assert.equal(r.status,200,await r.clone().text());return (await r.json()).documents;};
 const first=await query({}),base=await query({},true);assert.deepEqual(first.map(d=>d.id),base.map(d=>d.id));assert.equal(first.length,12);assert.ok(batchReads>=4);assert.ok(maxRows<=24);
 const last=first.at(-1),after={startAfter:[last.data.createdAt,last.id]};const second=await query(after),baseSecond=await query(after,true);assert.deepEqual(second.map(d=>d.id),baseSecond.map(d=>d.id));assert.equal(new Set([...first,...second].map(d=>d.id)).size,24);
 let prior=batchReads;
 // Fractional timestamp must use the old JS comparator, which truncates millis.
 f.run('posts/odd','posts','posts','odd','alice',JSON.stringify({authorId:'alice',privacy:'Public',createdAt:{__type:'timestamp',millis:22.5}}),1,1);
 postReads=0;
 const fallback=await query({});const fallbackReads=postReads;
 assert.deepEqual(fallback.map(d=>d.id),(await query({},true)).map(d=>d.id));assert.equal(batchReads,prior);
 assert.ok(fallbackReads<100,`fallback should authorize only enough rows to fill the page, not every post: ${fallbackReads}`);
 const next=await query({startAfter:[fallback.at(-1).data.createdAt,fallback.at(-1).id]});
 assert.deepEqual(next.map(d=>d.id),(await query({startAfter:[fallback.at(-1).data.createdAt,fallback.at(-1).id]},true)).map(d=>d.id));
 assert.equal(new Set([...fallback,...next].map(d=>d.id)).size,24);
 sql.close();
});

test('reports accept authenticated submissions and restrict reads and dismissals to configured admins', async () => {
 const sql=new DatabaseSync(':memory:');sql.exec(resourcesSchema);sql.exec(appSchema);sql.exec(schema);
 const db={prepare(q){const s=sql.prepare(q);return{bind(...args){return{run:async()=>s.run(...args),all:async()=>({results:s.all(...args)}),first:async()=>s.get(...args)||null};}};},async batch(stmts){sql.exec('BEGIN');try{for(const s of stmts)await s.run();sql.exec('COMMIT');}catch(e){sql.exec('ROLLBACK');throw e;}}};
 const app=createWorker(async token=>token),env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test',PAYMENT_ADMIN_UIDS:'admin'};
 const call=(uid,path,method='GET',body)=>app.fetch(new Request('https://example.test'+path,{method,headers:{authorization:'Bearer '+uid,'content-type':'application/json'},...(body?{body:JSON.stringify(body)}:{})}),env);
 const post='/v1/firestore/document?parent=reports';
 assert.equal((await call('alice',post,'POST',{data:{reportedBy:'bob',postId:'p'}})).status,400);
 assert.equal((await call('alice',post,'POST',{data:{reportedBy:'alice',reporterId:'bob'}})).status,400);
 assert.equal((await call('alice',post,'POST',{data:{reportedBy:'alice',extra:'bad'}})).status,400);
 const created=await call('alice',post,'POST',{data:{reportedBy:'alice',postId:'p',type:'post',reason:'spam',createdAt:1}});
 assert.equal(created.status,201,await created.clone().text());const report=await created.json();
 assert.notEqual(report.data.createdAt.millis,1);
 const path='/v1/firestore/document?path=reports/'+report.id;
 for(const uid of ['alice','bob']) {
  assert.equal((await call(uid,path)).status,403);
  assert.equal((await call(uid,path,'DELETE')).status,403);
  assert.equal((await call(uid,'/v1/firestore/query','POST',{parent:'reports'})).status,403);
  assert.equal((await call(uid,'/v1/firestore/count?parent=reports')).status,403);
  assert.equal((await call(uid,'/v1/firestore/batch','POST',{operations:[{op:'delete',path:'reports/'+report.id}]})).status,403);
  assert.equal((await call(uid,path,'PATCH',{fields:{reason:'changed'}})).status,403);
  assert.equal((await call(uid,path,'PUT',{data:{reportedBy:uid}})).status,403);
 }
 assert.equal((await call('admin',path)).status,200);
 assert.equal((await (await call('admin','/v1/firestore/query','POST',{parent:'reports'})).json()).documents.length,1);
 assert.equal((await (await call('admin','/v1/firestore/count?parent=reports')).json()).count,1);
 assert.equal((await call('admin',path,'DELETE')).status,200);
 assert.equal(sql.prepare("SELECT COUNT(*) AS n FROM friendbook_cf_firestore_documents WHERE parent_path='reports'").get().n,0);
 assert.equal(sql.prepare("SELECT COUNT(*) AS n FROM friendbook_cf_documents WHERE collection='reports'").get().n,0);
 sql.close();
});

test('a failed root mirror write rolls back the canonical D1 document', async () => {
 const sql=new DatabaseSync(':memory:');sql.exec(resourcesSchema);sql.exec(appSchema);sql.exec(schema);sql.exec(socialGuardSchema);
 sql.exec("CREATE TRIGGER fail_mirror BEFORE INSERT ON friendbook_cf_documents WHEN NEW.path='users/alice' BEGIN SELECT RAISE(ABORT, 'mirror failure'); END;");
 const db={prepare(q){const s=sql.prepare(q);return{bind(...args){return{run:async()=>s.run(...args),all:async()=>({results:s.all(...args)}),first:async()=>s.get(...args)||null};}};},async batch(stmts){sql.exec('BEGIN');try{for(const s of stmts)await s.run();sql.exec('COMMIT');}catch(e){sql.exec('ROLLBACK');throw e;}}};
 const app=createWorker(async()=> 'alice'),env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test'};
 const r=await app.fetch(new Request('https://example.test/v1/firestore/document?path=users/alice',{method:'PUT',headers:{authorization:'Bearer alice','content-type':'application/json'},body:JSON.stringify({data:{uid:'alice',displayName:'Alice'}})}),env);
 assert.equal(r.status,503);
 assert.equal(sql.prepare("SELECT COUNT(*) AS n FROM friendbook_cf_firestore_documents WHERE path='users/alice'").get().n,0);
 assert.equal(sql.prepare("SELECT COUNT(*) AS n FROM friendbook_cf_documents WHERE path='users/alice'").get().n,0);
 sql.close();
});

test('profile merges retry concurrent friendship updates without erasing them', async () => {
 const sql=new DatabaseSync(':memory:');sql.exec(resourcesSchema);sql.exec(appSchema);sql.exec(schema);sql.exec(socialGuardSchema);
 const original={uid:'alice',displayName:'Before',friendsCount:0,followers:[],following:[]};
 const f=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
 const a=sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)');
 f.run('users/alice','users','users','alice','alice',JSON.stringify(original),1,1);
 a.run('users/alice','users','alice','alice',JSON.stringify({...original,id:'alice'}),1);
 let injected=false, batches=0;
 const db={prepare(q){const s=sql.prepare(q);return{bind(...args){return{run:async()=>s.run(...args),all:async()=>({results:s.all(...args)}),first:async()=>s.get(...args)||null};}};},async batch(stmts){
  batches++;
  if(!injected){injected=true;const concurrent={...original,friendsCount:1,followers:['bob']};
   sql.prepare('UPDATE friendbook_cf_firestore_documents SET document_json=? WHERE path=?').run(JSON.stringify(concurrent),'users/alice');
   sql.prepare('UPDATE friendbook_cf_documents SET document_json=? WHERE path=?').run(JSON.stringify({...concurrent,id:'alice'}),'users/alice');}
  sql.exec('BEGIN');try{for(const s of stmts)await s.run();sql.exec('COMMIT');}catch(e){sql.exec('ROLLBACK');throw e;}
 }};
 const app=createWorker(async token=>token),env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test'};
 const call=(uid,body)=>app.fetch(new Request('https://example.test/v1/firestore/document?path=users/alice',{method:'PUT',headers:{authorization:'Bearer '+uid,'content-type':'application/json'},body:JSON.stringify(body)}),env);
 const r=await call('alice',{data:{displayName:'After'},merge:true});assert.equal(r.status,200,await r.clone().text());
 const doc=(await r.json()).data;assert.equal(doc.displayName,'After');assert.equal(doc.friendsCount,1);assert.deepEqual(doc.followers,['bob']);assert.equal(batches,2);
 assert.equal((await call('bob',{data:{displayName:'forged'},merge:true})).status,403);
 assert.equal((await call('alice',{data:{friendsCount:99},merge:true})).status,403);
 const mirrored=JSON.parse(sql.prepare('SELECT document_json FROM friendbook_cf_documents WHERE path=?').get('users/alice').document_json);
 assert.equal(mirrored.friendsCount,1);assert.equal(mirrored.displayName,'After');
 sql.close();
});

test('saving visible posts is actor-scoped and survives concurrent owner edits', async () => {
 const sql=new DatabaseSync(':memory:');sql.exec(resourcesSchema);sql.exec(appSchema);sql.exec(schema);sql.exec(socialGuardSchema);
 const f=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
 for(const uid of ['alice','bob','mallory']) f.run(`users/${uid}`,'users','users',uid,uid,JSON.stringify({uid}),1,1);
 const initial={authorId:'alice',privacy:'Public',content:'original',savedBy:[]};
 f.run('posts/p','posts','posts','p','alice',JSON.stringify(initial),1,1);
 let injected=false;
 const db={prepare(q){const s=sql.prepare(q);return{bind(...args){return{run:async()=>s.run(...args),all:async()=>({results:s.all(...args)}),first:async()=>s.get(...args)||null};}};},async batch(stmts){
  if(!injected){injected=true;sql.prepare("UPDATE friendbook_cf_firestore_documents SET document_json=? WHERE path='posts/p'").run(JSON.stringify({...initial,content:'edited'}));}
  sql.exec('BEGIN');try{for(const s of stmts)await s.run();sql.exec('COMMIT');}catch(e){sql.exec('ROLLBACK');throw e;}
 }};
 const app=createWorker(async t=>t),env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test'};
 const patch=(uid,path,fields)=>app.fetch(new Request('https://example.test/v1/firestore/document?path='+encodeURIComponent(path),{method:'PATCH',headers:{authorization:'Bearer '+uid,'content-type':'application/json'},body:JSON.stringify({fields})}),env);
 const transform=(kind,who)=>({__fieldValue:kind,values:[who]});
 const saved=await patch('bob','posts/p',{savedBy:transform('arrayUnion','bob')});assert.equal(saved.status,200,await saved.clone().text());
 assert.equal((await saved.json()).data.content,'edited');
 assert.equal((await patch('mallory','posts/p',{savedBy:transform('arrayUnion','bob')})).status,403);
 assert.equal((await patch('mallory','posts/p',{savedBy:transform('arrayUnion','mallory'),content:'forged'})).status,403);
 const doc=JSON.parse(sql.prepare("SELECT document_json FROM friendbook_cf_firestore_documents WHERE path='posts/p'").get().document_json);
 assert.deepEqual(doc.savedBy,['bob']);assert.equal(doc.content,'edited');
 assert.equal((await patch('bob','posts/p',{savedBy:transform('arrayRemove','bob')})).status,200);
 const removed=JSON.parse(sql.prepare("SELECT document_json FROM friendbook_cf_firestore_documents WHERE path='posts/p'").get().document_json);assert.deepEqual(removed.savedBy,[]);
 sql.prepare("UPDATE friendbook_cf_firestore_documents SET document_json=? WHERE path='posts/p'").run(JSON.stringify({...initial,privacy:'Only me'}));
 assert.equal((await patch('bob','posts/p',{savedBy:transform('arrayUnion','bob')})).status,403);
 sql.close();
});

test('root post deletion rolls back both copies when a mirror delete fails', async () => {
 const sql=new DatabaseSync(':memory:');sql.exec(resourcesSchema);sql.exec(appSchema);sql.exec(schema);sql.exec(socialGuardSchema);
 const post={authorId:'alice',privacy:'Public'};
 sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)').run('posts/p','posts','posts','p','alice',JSON.stringify(post),1,1);
 sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)').run('posts/p','posts','p','alice',JSON.stringify({...post,id:'p'}),1);
 sql.exec("CREATE TRIGGER fail_delete BEFORE DELETE ON friendbook_cf_documents WHEN OLD.path='posts/p' BEGIN SELECT RAISE(ABORT, 'mirror failure'); END;");
 const db={prepare(q){const s=sql.prepare(q);return{bind(...args){return{run:async()=>s.run(...args),all:async()=>({results:s.all(...args)}),first:async()=>s.get(...args)||null};}};},async batch(stmts){sql.exec('BEGIN');try{for(const s of stmts)await s.run();sql.exec('COMMIT');}catch(e){sql.exec('ROLLBACK');throw e;}}};
 const app=createWorker(async()=> 'alice'),env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test'};
 const r=await app.fetch(new Request('https://example.test/v1/firestore/document?path=posts/p',{method:'DELETE',headers:{authorization:'Bearer alice'}}),env);
 assert.equal(r.status,503);
 assert.equal(sql.prepare("SELECT COUNT(*) AS n FROM friendbook_cf_firestore_documents WHERE path='posts/p'").get().n,1);
 assert.equal(sql.prepare("SELECT COUNT(*) AS n FROM friendbook_cf_documents WHERE path='posts/p'").get().n,1);
 sql.close();
});

test('older app document profile writes use the same guarded merge', async () => {
 const sql=new DatabaseSync(':memory:');sql.exec(resourcesSchema);sql.exec(appSchema);sql.exec(schema);sql.exec(socialGuardSchema);
 const original={uid:'alice',displayName:'Before',friendsCount:0,followers:[],following:[]};
 sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)').run('users/alice','users','users','alice','alice',JSON.stringify(original),1,1);
 let injected=false;
 const db={prepare(q){const s=sql.prepare(q);return{bind(...args){return{run:async()=>s.run(...args),all:async()=>({results:s.all(...args)}),first:async()=>s.get(...args)||null};}};},async batch(stmts){
  if(!injected){injected=true;sql.prepare("UPDATE friendbook_cf_firestore_documents SET document_json=? WHERE path='users/alice'").run(JSON.stringify({...original,friendsCount:1}));}
  sql.exec('BEGIN');try{for(const s of stmts)await s.run();sql.exec('COMMIT');}catch(e){sql.exec('ROLLBACK');throw e;}
 }};
 const app=createWorker(async t=>t),env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test'};
 const r=await app.fetch(new Request('https://example.test/v1/docs/users/alice',{method:'PUT',headers:{authorization:'Bearer alice','content-type':'application/json'},body:JSON.stringify({displayName:'After'})}),env);
 assert.equal(r.status,200,await r.clone().text());
 const profile=(await r.json()).document;assert.equal(profile.displayName,'After');assert.equal(profile.friendsCount,1);
 sql.close();
});

test('admin moderation requires configured UID, preserves audit and atomically bans or deletes', async () => {
 const sql=new DatabaseSync(':memory:');sql.exec(resourcesSchema);sql.exec(appSchema);sql.exec(schema);sql.exec(socialGuardSchema);
 sql.exec(readFileSync(new URL('../migrations/0030_moderation_audit.sql',import.meta.url),'utf8'));
 sql.exec(readFileSync(new URL('../migrations/0037_admin_live_audit.sql',import.meta.url),'utf8'));
 const f=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
 const a=sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)');
 const add=(coll,id,data)=>{f.run(`${coll}/${id}`,coll,coll,id,id,JSON.stringify(data),1,1);a.run(`${coll}/${id}`,coll,id,id,JSON.stringify({...data,id}),1);};
 add('users','alice',{uid:'alice',displayName:'Alice',email:'private@example.test',banned:false,secret:'not-for-admin-list'});
 add('users','admin',{uid:'admin',displayName:'Owner',banned:false});
 add('posts','p',{authorId:'alice',privacy:'Only me',content:'sensitive'});
 const db={prepare(q){const s=sql.prepare(q);return{bind(...args){return{run:async()=>s.run(...args),all:async()=>({results:s.all(...args)}),first:async()=>s.get(...args)||null};}};},async batch(stmts){sql.exec('BEGIN');try{for(const s of stmts)await s.run();sql.exec('COMMIT');}catch(e){sql.exec('ROLLBACK');throw e;}}};
 const app=createWorker(async t=>t),env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test',PAYMENT_ADMIN_UIDS:'admin'};
 const call=(uid,path,method='GET',body)=>app.fetch(new Request('https://example.test'+path,{method,headers:{authorization:'Bearer '+uid,'content-type':'application/json'},...(body?{body:JSON.stringify(body)}:{})}),env);
 const mod=(uid,body)=>call(uid,'/v1/admin/moderation','POST',body);
 const ban={action:'ban_user',collection:'users',id:'alice'};
 assert.equal((await mod('alice',ban)).status,403);
 assert.equal((await mod('admin',{...ban,id:'admin'})).status,403);
 assert.equal((await mod('admin',{...ban,id:'../alice'})).status,400);
 assert.equal((await mod('admin',{...ban,admin:true})).status,400);
 assert.equal((await mod('admin',ban)).status,200);
 const canonical=JSON.parse(sql.prepare("SELECT document_json FROM friendbook_cf_firestore_documents WHERE path='users/alice'").get().document_json);
 const mirror=JSON.parse(sql.prepare("SELECT document_json FROM friendbook_cf_documents WHERE path='users/alice'").get().document_json);
 assert.equal(canonical.banned,true);assert.equal(mirror.banned,true);
 const adminRead=await call('admin','/v1/firestore/document?path=users/alice');assert.equal(adminRead.status,200);
 const view=(await adminRead.json()).data;assert.equal(view.banned,true);assert.equal(view.email,'private@example.test');assert.equal(view.secret,undefined);
 assert.equal((await call('mallory','/v1/firestore/document?path=users/alice')).status,403);
 assert.equal((await mod('admin',{action:'unban_user',collection:'users',id:'alice'})).status,200);
 assert.equal((await mod('alice',{action:'delete_content',collection:'posts',id:'p'})).status,403);
 assert.equal((await mod('admin',{action:'delete_content',collection:'posts',id:'p'})).status,200);
 assert.equal(sql.prepare("SELECT COUNT(*) n FROM friendbook_cf_firestore_documents WHERE path='posts/p'").get().n,0);
 assert.equal(sql.prepare("SELECT COUNT(*) n FROM friendbook_cf_documents WHERE path='posts/p'").get().n,0);
 add('lives','room',{hostId:'alice',status:'live',privacy:'friends'});
 const end={action:'end_live',collection:'lives',id:'room'};
 assert.equal((await mod('alice',end)).status,403);
 assert.equal((await mod('admin',end)).status,200);
 assert.equal((await mod('admin',end)).status,200);
 assert.equal(JSON.parse(sql.prepare("SELECT document_json FROM friendbook_cf_firestore_documents WHERE path='lives/room'").get().document_json).status,'ended');
 assert.equal(JSON.parse(sql.prepare("SELECT document_json FROM friendbook_cf_documents WHERE path='lives/room'").get().document_json).status,'ended');
 for (const collection of ['gifts','referrals','subscriptions']) {
   add(collection,'private',{value:1});
   const path=`/v1/firestore/document?path=${collection}/private`;
   assert.equal((await call('alice',path)).status,403);
   const list=await call('alice','/v1/firestore/query','POST',{parent:collection});
   const count=await call('alice',`/v1/firestore/count?parent=${collection}`);
   if (collection==='subscriptions') {assert.deepEqual((await list.json()).documents,[]);assert.equal((await count.json()).count,0);}
   else {assert.equal(list.status,403);assert.equal(count.status,403);}
   assert.equal((await call('admin',path)).status,200);
   assert.equal((await (await call('admin','/v1/firestore/query','POST',{parent:collection})).json()).documents.length,1);
   assert.equal((await call('admin',path,'DELETE')).status,403);
 }
 for (const collection of ['reports','feedback']) {
   add(collection,'item',{text:'private inbox item'});
   const action={action:'delete_content',collection,id:'item'};
   assert.equal((await mod('alice',action)).status,403);
   assert.equal((await mod('admin',action)).status,200);
   assert.equal((await mod('admin',action)).status,404);
   assert.equal(sql.prepare('SELECT 1 FROM friendbook_cf_documents WHERE path=?').get(`${collection}/item`),undefined);
 }
 sql.exec(readFileSync(new URL('../migrations/0045_admin_account_controls.sql',import.meta.url),'utf8'));
 add('users','mod',{uid:'mod',isModerator:true,displayName:'Mod'});
 add('users','bob',{uid:'bob',displayName:'Bob'});
 const modBan={action:'ban_user',collection:'users',id:'bob'};
 assert.equal((await mod('mod',modBan)).status,403);
 assert.equal((await mod('admin',{action:'set_moderator',collection:'users',id:'mod',value:true})).status,200);
 assert.equal((await mod('mod',modBan)).status,200);
 assert.equal((await mod('mod',{...modBan,id:'admin'})).status,403);
 const modView=await (await call('mod','/v1/firestore/query','POST',{parent:'users'})).json();
 assert.equal(modView.documents.find(d=>d.id==='bob').data.banned,true);
 assert.equal(modView.documents.find(d=>d.id==='alice').data.email,undefined);
 assert.equal((await mod('admin',{action:'set_moderator',collection:'users',id:'mod',value:false})).status,200);
 assert.equal((await mod('mod',{...modBan,action:'unban_user'})).status,403);
 assert.equal((await mod('admin',{action:'set_tick',collection:'users',id:'alice',value:'gold'})).status,200);
 assert.equal((await mod('admin',{action:'set_suspension',collection:'users',id:'alice',value:1})).status,200);
 assert.equal((await call('alice','/v1/firestore/document?path=posts/new','PUT',{data:{authorId:'alice'}})).status,403);
 assert.equal((await call('alice','/v1/firestore/document?path=users/alice')).status,200);
 assert.equal((await mod('admin',{action:'set_suspension',collection:'users',id:'alice',value:0})).status,200);
 assert.equal((await call('alice','/v1/firestore/document?path=posts/new','PUT',{data:{authorId:'alice'}})).status,200);
 assert.equal((await mod('admin',{action:'deactivate_user',collection:'users',id:'alice'})).status,200);
 assert.equal((await call('alice','/v1/firestore/document?path=posts/new')).status,403);
 assert.equal(sql.prepare('SELECT count(*) n FROM friendbook_cf_admin_account_audit').get().n,6);
 const audit=sql.prepare('SELECT action,original_json FROM friendbook_cf_moderation_audit ORDER BY rowid').all();
 assert.deepEqual(audit.map(x=>x.action),['ban_user','unban_user','delete_content','delete_content','delete_content','ban_user']);assert.equal(JSON.parse(audit[2].original_json).content,'sensitive');
 assert.equal(sql.prepare('SELECT COUNT(*) n FROM friendbook_cf_live_moderation_audit').get().n,1);
 sql.close();
});

test('moderation audit and ban roll back together on a storage failure', async () => {
 const sql=new DatabaseSync(':memory:');sql.exec(resourcesSchema);sql.exec(appSchema);sql.exec(schema);sql.exec(socialGuardSchema);
 sql.exec(readFileSync(new URL('../migrations/0030_moderation_audit.sql',import.meta.url),'utf8'));
 const profile={uid:'alice',banned:false};
 sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)').run('users/alice','users','users','alice','alice',JSON.stringify(profile),1,1);
 sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)').run('users/alice','users','alice','alice',JSON.stringify({...profile,id:'alice'}),1);
 sql.exec("CREATE TRIGGER fail_mod BEFORE UPDATE ON friendbook_cf_documents WHEN OLD.path='users/alice' BEGIN SELECT RAISE(ABORT,'mirror failure'); END;");
 const db={prepare(q){const s=sql.prepare(q);return{bind(...args){return{run:async()=>s.run(...args),all:async()=>({results:s.all(...args)}),first:async()=>s.get(...args)||null};}};},async batch(stmts){sql.exec('BEGIN');try{for(const s of stmts)await s.run();sql.exec('COMMIT');}catch(e){sql.exec('ROLLBACK');throw e;}}};
 const app=createWorker(async t=>t),env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test',PAYMENT_ADMIN_UIDS:'admin'};
 const r=await app.fetch(new Request('https://example.test/v1/admin/moderation',{method:'POST',headers:{authorization:'Bearer admin','content-type':'application/json'},body:JSON.stringify({action:'ban_user',collection:'users',id:'alice'})}),env);
 assert.equal(r.status,503);assert.equal(JSON.parse(sql.prepare("SELECT document_json FROM friendbook_cf_firestore_documents WHERE path='users/alice'").get().document_json).banned,false);
 assert.equal(sql.prepare('SELECT COUNT(*) n FROM friendbook_cf_moderation_audit').get().n,0);
 sql.close();
});

test('disabled account cannot use D1 routes but can read its own status for sign-out', async () => {
 const sql=new DatabaseSync(':memory:');sql.exec(resourcesSchema);sql.exec(appSchema);sql.exec(schema);
 sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)').run('users/alice','users','users','alice','alice',JSON.stringify({uid:'alice',banned:true}),1,1);
 sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)').run('users/alice','users','alice','alice',JSON.stringify({uid:'alice',banned:false}),1);
 const db={prepare(q){const s=sql.prepare(q);return{bind(...args){return{run:async()=>s.run(...args),all:async()=>({results:s.all(...args)}),first:async()=>s.get(...args)||null};}};}};
 const app=createWorker(async t=>t),env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test'};
 const call=(path,method='GET',body)=>app.fetch(new Request('https://example.test'+path,{method,headers:{authorization:'Bearer alice','content-type':'application/json'},...(body?{body:JSON.stringify(body)}:{})}),env);
 const own=await call('/v1/firestore/document?path=users/alice');assert.equal(own.status,200);assert.equal((await own.json()).data.banned,true);
 for (const [path,method,body] of [
  ['/v1/firestore/query','POST',{parent:'chats'}],
  ['/v1/firestore/document?parent=reports','POST',{data:{reportedBy:'alice',reason:'spam'}}],
  ['/v1/presence/me','GET'], ['/v1/docs/users/alice','PUT',{displayName:'x'}],
  ['/v1/firestore/document?path=users/alice','PATCH',{fields:{banned:false}}]
 ]) assert.equal((await call(path,method,body)).status,403,path);
 sql.prepare('UPDATE friendbook_cf_firestore_documents SET document_json=? WHERE path=?').run(JSON.stringify({uid:'alice',banned:false}),'users/alice');
 assert.equal((await call('/v1/firestore/query','POST',{parent:'chats'})).status,200);
 sql.close();
});

test('story engagement records only authorized actor, retries races, and mirrors atomically', async () => {
 const sql=new DatabaseSync(':memory:');for(const s of [resourcesSchema,appSchema,schema,socialGuardSchema,readFileSync(new URL('../migrations/0019_trusted_friendships.sql',import.meta.url),'utf8')])sql.exec(s);
 const put=(coll,id,obj)=>sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)').run(`${coll}/${id}`,coll,coll,id,id,JSON.stringify(obj),1,1);
 put('users','alice',{uid:'alice'});put('users','bob',{uid:'bob'});put('users','carol',{uid:'carol'});
 put('stories','s1',{authorId:'alice',privacy:'Public',expiresAt:Date.now()+60000,viewedBy:[],reactions:{}});
 let race=true,blockRace=false;
 const db={prepare(q){const s=sql.prepare(q);return{bind(...args){return{run:async()=>s.run(...args),all:async()=>({results:s.all(...args)}),first:async()=>s.get(...args)||null};}};},async batch(stmts){
  if(race){race=false;const row=JSON.parse(sql.prepare("SELECT document_json FROM friendbook_cf_firestore_documents WHERE path='stories/s1'").get().document_json);row.viewedBy=['carol'];sql.prepare("UPDATE friendbook_cf_firestore_documents SET document_json=? WHERE path='stories/s1'").run(JSON.stringify(row));}
  if(blockRace){blockRace=false;sql.prepare("UPDATE friendbook_cf_firestore_documents SET document_json=? WHERE path='users/alice'").run(JSON.stringify({uid:'alice',blocked:['bob']}));}
  sql.exec('BEGIN');try{for(const s of stmts)await s.run();sql.exec('COMMIT');}catch(e){sql.exec('ROLLBACK');throw e;}
 }};
 const app=createWorker(async t=>t),env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test'};
 const call=(who,body)=>app.fetch(new Request('https://example.test/v1/firestore/story-engagement',{method:'POST',headers:{authorization:'Bearer '+who,'content-type':'application/json'},body:JSON.stringify(body)}),env);
 assert.equal((await call('bob',{id:'s1',op:'view'})).status,200);
 let story=JSON.parse(sql.prepare("SELECT document_json FROM friendbook_cf_firestore_documents WHERE path='stories/s1'").get().document_json);
 assert.deepEqual(story.viewedBy,['carol','bob']);
 assert.equal((await call('bob',{id:'s1',op:'view'})).status,200);
 assert.equal((await call('bob',{id:'s1',op:'react',emoji:'❤️'})).status,200);
 story=JSON.parse(sql.prepare("SELECT document_json FROM friendbook_cf_firestore_documents WHERE path='stories/s1'").get().document_json);
 assert.deepEqual(story.viewedBy,['carol','bob']);assert.deepEqual(story.reactions,{bob:'❤️'});
 const mirror=JSON.parse(sql.prepare("SELECT document_json FROM friendbook_cf_documents WHERE path='stories/s1'").get().document_json);assert.deepEqual(mirror.viewedBy,story.viewedBy);assert.deepEqual(mirror.reactions,story.reactions);
 assert.equal((await call('bob',{id:'s1',op:'react',emoji:'x',uid:'carol'})).status,400);
 story.privacy='Friends';sql.prepare("UPDATE friendbook_cf_firestore_documents SET document_json=? WHERE path='stories/s1'").run(JSON.stringify(story));
 assert.equal((await call('bob',{id:'s1',op:'view'})).status,403);
 sql.prepare('INSERT INTO friendbook_cf_trusted_friendships(uid_low,uid_high,friend_path) VALUES (?,?,?)').run('alice','bob','friends/f1');
 assert.equal((await call('bob',{id:'s1',op:'view'})).status,200);
 blockRace=true;
 assert.equal((await call('bob',{id:'s1',op:'react',emoji:'x'})).status,403);
 story=JSON.parse(sql.prepare("SELECT document_json FROM friendbook_cf_firestore_documents WHERE path='stories/s1'").get().document_json);
 assert.equal(story.reactions.bob,'❤️'); // a block racing the batch prevented the write
 sql.close();
});

test('only the story author can delete, and concurrent modifications roll back deletion', async () => {
 const sql=new DatabaseSync(':memory:');for(const s of [resourcesSchema,appSchema,schema,socialGuardSchema])sql.exec(s);
 const add=(id,owner)=>{const doc={authorId:owner,caption:'original'};sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)').run(`stories/${id}`,'stories','stories',id,owner,JSON.stringify(doc),1,1);sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)').run(`stories/${id}`,'stories',id,owner,JSON.stringify({...doc,id}),1);};
 add('s1','alice');add('s2','alice');let race=false;
 const db={prepare(q){const s=sql.prepare(q);return{bind(...args){return{run:async()=>s.run(...args),all:async()=>({results:s.all(...args)}),first:async()=>s.get(...args)||null};}};},async batch(stmts){
  if(race){race=false;sql.prepare("UPDATE friendbook_cf_firestore_documents SET document_json=? WHERE path='stories/s2'").run(JSON.stringify({authorId:'alice',caption:'new'}));}
  sql.exec('BEGIN');try{for(const s of stmts)await s.run();sql.exec('COMMIT');}catch(e){sql.exec('ROLLBACK');throw e;}
 }};
 const app=createWorker(async t=>t),env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test'};
 const del=(who,id)=>app.fetch(new Request(`https://example.test/v1/firestore/document?path=stories/${id}`,{method:'DELETE',headers:{authorization:`Bearer ${who}`}}),env);
 assert.equal((await del('bob','s1')).status,403);assert.ok(sql.prepare("SELECT 1 FROM friendbook_cf_firestore_documents WHERE path='stories/s1'").get());
 race=true;assert.equal((await del('alice','s2')).status,409);
 assert.equal(JSON.parse(sql.prepare("SELECT document_json FROM friendbook_cf_firestore_documents WHERE path='stories/s2'").get().document_json).caption,'new');
 assert.ok(sql.prepare("SELECT 1 FROM friendbook_cf_documents WHERE path='stories/s2'").get());
 assert.equal((await del('alice','s1')).status,200);assert.equal(sql.prepare("SELECT COUNT(*) n FROM friendbook_cf_documents WHERE path='stories/s1'").get().n,0);
 sql.close();
});

test('feedback is create-only for authenticated sender and private to configured admin', async () => {
 const sql=new DatabaseSync(':memory:');for(const s of [resourcesSchema,appSchema,schema,socialGuardSchema])sql.exec(s);
 const db={prepare(q){const s=sql.prepare(q);return{bind(...args){return{run:async()=>s.run(...args),all:async()=>({results:s.all(...args)}),first:async()=>s.get(...args)||null};}};},async batch(stmts){sql.exec('BEGIN');try{for(const s of stmts)await s.run();sql.exec('COMMIT');}catch(e){sql.exec('ROLLBACK');throw e;}}};
 const app=createWorker(async t=>t),env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test',PAYMENT_ADMIN_UIDS:'admin'};
 const call=(uid,path,method='GET',body)=>app.fetch(new Request('https://example.test'+path,{method,headers:{authorization:`Bearer ${uid}`,'content-type':'application/json'},...(body?{body:JSON.stringify(body)}:{})}),env);
 const submission={data:{uid:'alice',name:'Alice',text:'Please fix loading',app:'android',createdAt:{__fieldValue:'serverTimestamp'}}};
 assert.equal((await call('alice','/v1/firestore/document?parent=feedback','POST',{data:{...submission.data,uid:'bob'}})).status,400);
 assert.equal((await call('alice','/v1/firestore/document?parent=feedback','POST',{data:{...submission.data,admin:true}})).status,400);
 const created=await call('alice','/v1/firestore/document?parent=feedback','POST',submission);assert.equal(created.status,201);const doc=await created.json();
 for(const uid of ['alice','bob']){
  assert.equal((await call(uid,`/v1/firestore/document?path=${doc.path}`)).status,403);
  assert.equal((await call(uid,'/v1/firestore/query','POST',{parent:'feedback'})).status,403);
  assert.equal((await call(uid,'/v1/firestore/count?parent=feedback')).status,403);
  assert.equal((await call(uid,`/v1/firestore/document?path=${doc.path}`,'DELETE')).status,403);
 }
 assert.equal((await call('alice',`/v1/firestore/document?path=${doc.path}`,'PATCH',{fields:{text:'changed'}})).status,403);
 assert.equal((await call('alice',`/v1/firestore/document?path=${doc.path}`,'PUT',{data:submission.data})).status,403);
 assert.equal((await call('alice','/v1/firestore/batch','POST',{operations:[{op:'delete',path:doc.path}]})).status,403);
 const inbox=await (await call('admin','/v1/firestore/query','POST',{parent:'feedback'})).json();assert.equal(inbox.documents.length,1);assert.equal(inbox.documents[0].data.text,'Please fix loading');
 assert.equal((await call('admin',`/v1/firestore/document?path=${doc.path}`,'DELETE')).status,200);
 assert.equal(sql.prepare("SELECT COUNT(*) n FROM friendbook_cf_documents WHERE collection='feedback'").get().n,0);
 sql.close();
});

test('ads config permits limited authenticated reads and guarded admin-only writes', async () => {
 const sql=new DatabaseSync(':memory:');for(const s of [resourcesSchema,appSchema,schema,socialGuardSchema])sql.exec(s);
 const db={prepare(q){const s=sql.prepare(q);return{bind(...args){return{run:async()=>s.run(...args),all:async()=>({results:s.all(...args)}),first:async()=>s.get(...args)||null};}};},async batch(stmts){sql.exec('BEGIN');try{for(const s of stmts)await s.run();sql.exec('COMMIT');}catch(e){sql.exec('ROLLBACK');throw e;}}};
 const app=createWorker(async t=>t),env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test',PAYMENT_ADMIN_UIDS:'admin'};
 const call=(uid,path,method='GET',body)=>app.fetch(new Request('https://example.test'+path,{method,headers:{authorization:`Bearer ${uid}`,'content-type':'application/json'},...(body?{body:JSON.stringify(body)}:{})}),env);
 const data={appId:'ca-app',feed_native_unit_v3:'feed',reels_native_unit_v3:'reel',ads_enabled_v3:true,websiteUrl:'https://example.test',appAdsTxtUrl:'https://example.test/app-ads.txt',updatedBy:'admin',updatedAt:{__fieldValue:'serverTimestamp'}};
 assert.equal((await call('alice','/v1/firestore/document?path=config/ads','PUT',{data})).status,403);
 assert.equal((await call('admin','/v1/firestore/document?path=config/ads','PUT',{data:{...data,secret:'no'}})).status,400);
 assert.equal((await call('admin','/v1/firestore/document?path=config/ads','PUT',{data,merge:true})).status,200);
 sql.prepare("UPDATE friendbook_cf_firestore_documents SET document_json=json_set(document_json,'$.privateLegacy','secret') WHERE path='config/ads'").run();
 const read=await call('alice','/v1/firestore/document?path=config/ads');assert.equal(read.status,200);const body=await read.json();assert.equal(body.data.feed_native_unit_v3,'feed');assert.equal(body.data.privateLegacy,undefined);
 assert.equal((await call('alice','/v1/firestore/document?path=config/secret')).status,403);
 assert.equal((await call('alice','/v1/firestore/query','POST',{parent:'config'})).status,403);
 assert.equal((await call('admin','/v1/firestore/document?path=config/ads','PATCH',{fields:{ads_enabled_v3:false}})).status,403);
 assert.equal((await call('admin','/v1/firestore/document?path=config/ads','DELETE')).status,403);
 assert.equal((await call('alice','/v1/firestore/batch','POST',{operations:[{op:'set',path:'config/ads',data}]})).status,403);
 sql.close();
});

test('archive and trash queries reveal only the owner’s hidden posts, never in normal feeds or counts', async () => {
  const sql=new DatabaseSync(':memory:');
  for(const s of [resourcesSchema,appSchema,schema,socialGuardSchema]) sql.exec(s);
  const db={prepare(q){const stmt=sql.prepare(q);return{bind(...args){return{run:async()=>stmt.run(...args),all:async()=>({results:stmt.all(...args)}),first:async()=>stmt.get(...args)||null};}};},async batch(stmts){sql.exec('BEGIN');try{for(const s of stmts)await s.run();sql.exec('COMMIT');}catch(e){sql.exec('ROLLBACK');throw e;}}};
  const app=createWorker(async token=>token), env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test'};
  const call=(uid,path,method='GET',body)=>app.fetch(new Request('https://example.test'+path,{method,headers:{authorization:'Bearer '+uid,'content-type':'application/json'},...(body?{body:JSON.stringify(body)}:{})}),env);
  for(const uid of ['alice','bob']) assert.equal((await call(uid,`/v1/firestore/document?path=users/${uid}`,'PUT',{data:{uid}})).status,200);
  for(const [id,extra] of [['visible',{}],['archive',{archived:true}],['trash',{trashedAt:12345}]]) {
    assert.equal((await call('alice',`/v1/firestore/document?path=posts/${id}`,'PUT',{data:{authorId:'alice',privacy:'Public',...extra}})).status,200);
  }
  const query=(uid,filters)=>call(uid,'/v1/firestore/query','POST',{parent:'posts',filters});
  const owner={field:'authorId',op:'==',value:'alice'}, archived={field:'archived',op:'==',value:true}, trashed={field:'trashedAt',op:'>',value:0};
  assert.deepEqual((await (await query('alice',[owner])).json()).documents.map(x=>x.id),['visible']);
  assert.deepEqual((await (await query('alice',[owner,archived])).json()).documents.map(x=>x.id),['archive']);
  assert.deepEqual((await (await query('alice',[owner,trashed])).json()).documents.map(x=>x.id),['trash']);
  assert.deepEqual((await (await query('bob',[owner,archived])).json()).documents,[]);
  assert.equal((await call('alice','/v1/firestore/document?path=posts/archive')).status,200);
  assert.equal((await call('bob','/v1/firestore/document?path=posts/archive')).status,403);
  assert.equal((await (await call('alice','/v1/firestore/count?parent=posts')).json()).count,1);
  assert.equal((await call('alice','/v1/firestore/document?path=posts/archive','PATCH',{fields:{archived:false}})).status,200);
  assert.equal((await call('bob','/v1/firestore/document?path=posts/archive')).status,200);
  sql.close();
});

test('new account signup can finish an existing presence-only profile without privilege escalation', async () => {
 const sql=new DatabaseSync(':memory:'); for (const s of [resourcesSchema,appSchema,schema,socialGuardSchema]) sql.exec(s);
 const db={prepare(q){const st=sql.prepare(q);return{bind(...a){return{run:async()=>st.run(...a),all:async()=>({results:st.all(...a)}),first:async()=>st.get(...a)||null};}};},async batch(ss){sql.exec('BEGIN');try{for(const s of ss)await s.run();sql.exec('COMMIT');}catch(e){sql.exec('ROLLBACK');throw e;}}};
 const app=createWorker(async()=> 'new_user'),env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test'};
 const put=(data,extra={})=>app.fetch(new Request('https://example.test/v1/firestore/document?path=users/new_user',{method:'PUT',headers:{authorization:'Bearer test','content-type':'application/json'},body:JSON.stringify({data,...extra})}),env);
 assert.equal((await put({isOnline:true,lastSeenMs:100},{merge:true})).status,200);
 assert.equal((await put({displayName:'New person',bio:'hello',friendsCount:0,followers:[],following:[],isAdmin:false,isVerified:false})).status,200);
 const doc=JSON.parse(sql.prepare("SELECT document_json FROM friendbook_cf_firestore_documents WHERE path='users/new_user'").get().document_json);
 assert.equal(doc.displayName,'New person');assert.deepEqual(doc.followers,[]);
 assert.equal((await put({displayName:'Fallback overwrites name'},{createOnly:true})).status,409);
 assert.equal((await put({...doc,isAdmin:true})).status,403);
 sql.close();
});

test('own follow lists include locked people without exposing their private lists to other viewers',async()=>{
 const sql=new DatabaseSync(':memory:'); for(const s of [resourcesSchema,appSchema,schema,socialGuardSchema])sql.exec(s);
 const put=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
 const insert=(id,doc)=>put.run('users/'+id,'users','users',id,id,JSON.stringify(doc),1,1);
 insert('alice',{uid:'alice',followers:['bob'],following:['bob']});
 insert('bob',{uid:'bob',profileLocked:true,followers:['alice','eve'],following:['alice','eve']});
 insert('eve',{uid:'eve'});
 const db={prepare(q){const s=sql.prepare(q);return{bind(...a){return{run:async()=>s.run(...a),all:async()=>({results:s.all(...a)}),first:async()=>s.get(...a)||null}}}}};
 const app=createWorker(async token=>token),env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test'};
 const query=async(uid,target,field)=>app.fetch(new Request('https://example.test/v1/firestore/query',{method:'POST',headers:{authorization:'Bearer '+uid,'content-type':'application/json'},body:JSON.stringify({parent:'users',filters:[{field,op:'array-contains',value:target}]})}),env);
 for(const field of ['followers','following']) {
   const own=await (await query('alice','alice',field)).json();assert.deepEqual(own.documents.map(x=>x.id),['bob']);
   assert.equal(own.documents[0].data.followers,undefined);assert.equal(own.documents[0].data.following,undefined);
   const other=await (await query('eve','alice',field)).json();assert.deepEqual(other.documents,[]);
   const filters=encodeURIComponent(JSON.stringify([{field,op:'array-contains',value:'alice'}]));
   const count=await app.fetch(new Request('https://example.test/v1/firestore/count?parent=users&filters='+filters,{headers:{authorization:'Bearer alice'}}),env);
   assert.equal((await count.json()).count,1);
 }
 sql.close();
});

test('transient authorization reads never turn feed or chat data into empty success',async()=>{
 const sql=new DatabaseSync(':memory:'); for(const s of [resourcesSchema,appSchema,schema,socialGuardSchema,readFileSync(new URL('../migrations/0019_trusted_friendships.sql',import.meta.url),'utf8')])sql.exec(s);
 const put=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
 const insert=(path,parent,id,data)=>put.run(path,parent,parent.split('/').at(-1),id,'',JSON.stringify(data),1,1);
 insert('users/alice','users','alice',{uid:'alice'}); insert('users/bob','users','bob',{uid:'bob'});
 insert('posts/p1','posts','p1',{authorId:'bob',privacy:'Public'});
 insert('chats/c1','chats','c1',{participants:['alice','bob']});
 insert('chats/c1/messages/m1','chats/c1/messages','m1',{senderId:'bob',text:'hello'});
 let fail='';
 const db={prepare(q){const stmt=sql.prepare(q);return{bind(...a){return{
  run:async()=>stmt.run(...a),all:async()=>({results:stmt.all(...a)}),first:async()=>{
   if(fail && q.includes('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path') && a[0]===fail)throw Error('temporary D1 failure');
   if(fail && q.includes('SELECT path, parent_path, collection_id, doc_id, owner_uid, document_json, create_time, update_time') && q.includes('FROM friendbook_cf_firestore_documents WHERE path') && a[0]===fail)throw Error('temporary D1 failure');
   return stmt.get(...a)||null;
  }};}};}};
 const app=createWorker(async()=> 'alice'),env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test'},headers={authorization:'Bearer token','content-type':'application/json'};
 const feed=()=>app.fetch(new Request('https://example.test/v1/firestore/query',{method:'POST',headers,body:JSON.stringify({parent:'posts'})}),env);
 const chat=()=>app.fetch(new Request('https://example.test/v1/firestore/query',{method:'POST',headers,body:JSON.stringify({parent:'chats/c1/messages'})}),env);
 assert.equal((await (await feed()).json()).documents.length,1);
 fail='users/bob'; assert.equal((await feed()).status,503);
 fail=''; assert.equal((await (await feed()).json()).documents.length,1);
 assert.equal((await (await chat()).json()).documents.length,1);
 fail='chats/c1'; assert.equal((await chat()).status,503);
 fail=''; assert.equal((await (await chat()).json()).documents.length,1);
 sql.close();
});

test('unrecognized chat subcollections are denied without attempting to read or write their data', async () => {
  const app = createWorker(async () => 'alice');
  const env = {FIREBASE_PROJECT_ID:'friendbook-test', DB:{prepare(query) {
    assert.match(query,/SELECT document_json/);
    return {bind(path) { assert.equal(path,'users/alice'); return {first:async()=>null}; }};
  }}};
  const headers = {authorization:'Bearer token','content-type':'application/json'};
  for (const [route, method, body] of [
    ['document?path=chats/room/internal/item','GET'],
    ['document?path=chats/room/internal/item','PUT',{data:{senderId:'alice'}}],
    ['query','POST',{parent:'chats/room/internal'}],
    ['count?parent=chats/room/internal','GET'],
  ]) {
    const result = await app.fetch(new Request(`https://example.test/v1/firestore/${route}`, {
      method,headers,...(body ? {body:JSON.stringify(body)} : {}),
    }),env);
    assert.equal(result.status,403,`${method} ${route}`);
  }
});

test('direct chat reads fail closed with a JSON 503 when membership lookup fails', async () => {
  const app = createWorker(async () => 'alice');
  const env = {FIREBASE_PROJECT_ID:'friendbook-test', DB:{prepare(query) {
    return {bind(path) { return {first:async()=>{
      if (path==='users/alice') return null;
      assert.equal(path,'chats/room');
      if (query.startsWith('SELECT document_json')) throw new Error('private storage diagnostic');
      return {path,doc_id:'room',parent_path:'chats',collection_id:'chats',document_json:'{"participants":["alice","bob"]}'};
    }}; }};
  }}};
  const response=await app.fetch(new Request('https://example.test/v1/firestore/document?path=chats/room',{
    headers:{authorization:'Bearer token'},
  }),env);
  assert.equal(response.status,503);
  assert.deepEqual(await response.json(),{error:'Chat access unavailable'});
});

test('query and count reject malformed filters rather than falling back to an unfiltered read', async () => {
  const app = createWorker(async () => 'alice');
  const env = {FIREBASE_PROJECT_ID:'friendbook-test',DB:{prepare() {
    return {bind(path) { assert.equal(path,'users/alice'); return {first:async()=>null}; }};
  }}};
  const headers={authorization:'Bearer token','content-type':'application/json'};
  const base='https://example.test/v1/firestore/';
  for (const filters of [null,{},[null],[{field:'authorId',op:'invalid',value:'alice'}],[{field:'authorId',op:'=='}]]) {
    const query=await app.fetch(new Request(base+'query',{method:'POST',headers,body:JSON.stringify({parent:'posts',filters})}),env);
    assert.equal(query.status,400);
    const count=await app.fetch(new Request(base+'count?parent=posts&filters='+encodeURIComponent(JSON.stringify(filters)),{headers}),env);
    assert.equal(count.status,400);
  }
  const broken=await app.fetch(new Request(base+'count?parent=posts&filters=%5B',{headers}),env);
  assert.equal(broken.status,400);
});

test('Cloudflare restores event RSVP and page follows without granting ownership or verification', async () => {
  const sql=new DatabaseSync(':memory:');
  for (const ddl of [resourcesSchema,appSchema,schema,readFileSync(new URL('../migrations/0018_social_guards.sql',import.meta.url),'utf8')]) sql.exec(ddl);
  const db={prepare(query){const stmt=sql.prepare(query);return{bind(...args){return{run:async()=>stmt.run(...args),all:async()=>({results:stmt.all(...args)}),first:async()=>stmt.get(...args)||null};}};},async batch(stmts){sql.exec('BEGIN');try{for(const stmt of stmts)await stmt.run();sql.exec('COMMIT');}catch(e){sql.exec('ROLLBACK');throw e;}}};
  const app=createWorker(async token=>token),env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test'};
  const call=(uid,route,method='GET',body)=>app.fetch(new Request(`https://example.test/v1/firestore/${route}`,{method,headers:{authorization:`Bearer ${uid}`,'content-type':'application/json'},...(body?{body:JSON.stringify(body)}:{})}),env);
  for (const [collection,owner,members] of [['events','hostId','interested'],['pages','ownerId','followers']]) {
    const create=`document?parent=${collection}`;
    assert.equal((await call('bob',create,'POST',{data:{[owner]:'alice'}})).status,403);
    assert.equal((await call('alice',create,'POST',{data:{[owner]:'alice',[members]:['bob']}})).status,403);
    const created=await call('alice',create,'POST',{data:{[owner]:'alice',[members]:['alice'],...(collection==='pages'?{tickLevel:'none'}:{})}});
    assert.equal(created.status,201,await created.clone().text());
    const {path}=await created.json(), doc=`document?path=${path}`;
    assert.equal((await call('bob','query','POST',{parent:collection})).status,200);
    assert.equal((await call('bob',doc,'PATCH',{fields:{[members]:{__fieldValue:'arrayUnion',values:['bob']}}})).status,200);
    assert.deepEqual((await (await call('bob',doc)).json()).data[members],['alice','bob']);
    assert.equal((await call('bob',doc,'PATCH',{fields:{[members]:['bob']}})).status,403);
    assert.equal((await call('bob',doc,'PATCH',{fields:{[owner]:'bob'}})).status,403);
    assert.equal((await call('bob',doc,'DELETE')).status,403);
    if (collection==='pages') assert.equal((await call('alice',doc,'PATCH',{fields:{tickLevel:'gold'}})).status,403);
    assert.equal((await call('alice',doc,'DELETE')).status,200);
    assert.equal((await (await call('bob',doc)).json()).exists,false);
  }
  sql.close();
});

test('live room creation, discovery and end transitions enforce host and viewer permissions', async () => {
  const sql=new DatabaseSync(':memory:');
  for (const ddl of [resourcesSchema,appSchema,schema,readFileSync(new URL('../migrations/0018_social_guards.sql',import.meta.url),'utf8'),readFileSync(new URL('../migrations/0019_trusted_friendships.sql',import.meta.url),'utf8')]) sql.exec(ddl);
  const insert=sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)');
  for (const uid of ['alice','bob']) insert.run(`users/${uid}`,'users',uid,uid,JSON.stringify({displayName:uid}),1);
  const db={prepare(query){const stmt=sql.prepare(query);return{bind(...args){return{run:async()=>stmt.run(...args),all:async()=>({results:stmt.all(...args)}),first:async()=>stmt.get(...args)||null};}};},async batch(stmts){sql.exec('BEGIN');try{for(const stmt of stmts)await stmt.run();sql.exec('COMMIT');}catch(e){sql.exec('ROLLBACK');throw e;}}};
  const app=createWorker(async token=>token),env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test'};
  const call=(uid,path,method='GET',body)=>app.fetch(new Request(`https://example.test${path}`,{method,headers:{authorization:`Bearer ${uid}`,'content-type':'application/json'},...(body?{body:JSON.stringify(body)}:{})}),env);
  const doc='/v1/firestore/document?path=lives/room';
  const data={hostId:'alice',status:'live',privacy:'friends'};
  assert.equal((await call('bob',doc,'PUT',{data})).status,403);
  assert.equal((await call('alice',doc,'PUT',{data})).status,200);
  assert.equal((await call('bob',doc)).status,403);
  assert.deepEqual((await (await call('bob','/v1/docs?collection=lives')).json()).documents,[]);
  assert.equal((await call('alice',doc,'PATCH',{fields:{privacy:'public'}})).status,200);
  assert.equal((await call('bob',doc)).status,200);
  const chat='lives/room/chat', send=`/v1/firestore/document?parent=${chat}`;
  const msg={type:'chat',uid:'bob',text:'Hello',name:'Fake Host',isHost:true};
  const created=await call('bob',send,'POST',{data:msg});
  assert.equal(created.status,201);
  const saved=(await created.json()).data;
  assert.equal(saved.name,'bob');
  assert.equal(saved.isHost,false);
  assert.equal((await call('bob',send,'POST',{data:{...msg,uid:'alice'}})).status,400);
  assert.equal((await call('bob',send,'POST',{data:{...msg,type:'gift'}})).status,400);
  assert.equal((await call('bob',send,'POST',{data:{...msg,type:'system'}})).status,403);
  assert.equal((await call('alice',doc,'PATCH',{fields:{slowMode:true}})).status,200);
  assert.equal((await call('bob',send,'POST',{data:msg})).status,409);
  const chatQuery='/v1/firestore/query';
  assert.equal((await (await call('bob',chatQuery,'POST',{parent:chat})).json()).documents.length,1);
  assert.equal((await call('alice',doc,'PATCH',{fields:{kickedUserIds:['bob']}})).status,200);
  assert.equal((await call('bob',chatQuery,'POST',{parent:chat})).status,403);
  assert.equal((await call('bob',send,'POST',{data:msg})).status,403);
  assert.equal((await call('alice',doc,'PATCH',{fields:{kickedUserIds:[]}})).status,200);
  assert.equal((await call('bob',send,'POST',{data:{...msg,type:'reaction',text:'heart'}})).status,201);
  assert.equal((await (await call('bob',doc)).json()).data.likesCount,1);
  assert.equal((await (await call('bob','/v1/docs/lives/room')).json()).document.likesCount,1);
  const gift={type:'gift',uid:'bob',giftId:'rose',requestId:'11111111-1111-4111-8111-111111111111',stars:9999};
  assert.equal((await call('alice',send,'POST',{data:{...gift,uid:'alice'}})).status,403);
  const giftResponse=await call('bob',send,'POST',{data:gift});
  assert.equal(giftResponse.status,201);
  assert.equal((await giftResponse.json()).data.stars,1);
  assert.equal((await call('bob',send,'POST',{data:gift})).status,200);
  assert.equal((await call('bob',send,'POST',{data:{...gift,giftId:'lion'}})).status,409);
  assert.equal((await call('bob',send,'POST',{data:{...gift,requestId:'22222222-2222-4222-8222-222222222222'}})).status,409);
  assert.equal((await (await call('bob',doc)).json()).data.socialGiftPoints,1);
  assert.equal((await call('alice',doc,'PATCH',{fields:{socialGiftPoints:999}})).status,403);
  assert.equal(sql.prepare("SELECT count(*) n FROM friendbook_cf_firestore_documents WHERE parent_path='gifts'").get().n,0);
  assert.equal((await call('alice',doc,'PATCH',{fields:{giftsEnabled:false}})).status,200);
  assert.equal((await call('bob',send,'POST',{data:gift})).status,403);
  assert.equal((await call('alice',doc,'PATCH',{fields:{likesCount:999}})).status,403);
  assert.equal((await call('bob',doc,'PATCH',{fields:{status:'ended'}})).status,403);
  assert.equal((await call('alice',doc,'PATCH',{fields:{audioB64:'cannot store media'}})).status,403);
  for(const field of ['coHostFrameB64','coHostAudioB64']) {
    assert.equal((await call('alice',doc,'PATCH',{fields:{[field]:'cannot store media'}})).status,403);
  }
  for(const type of ['cohost_media','pk_invite']) {
    assert.equal((await call('bob',send,'POST',{data:{type,uid:'bob',text:'legacy control'}})).status,400);
  }
  assert.equal((await call('alice','/v1/firestore/document?path=chats/pkbattle_test','PUT',{data:{hostAUid:'alice',hostBUid:'bob',status:'invited'}})).status,403);
  assert.equal((await call('alice',doc,'PATCH',{fields:{status:'ended'}})).status,200);
  assert.equal((await (await call('bob',doc)).json()).data.status,'ended');
  assert.equal((await call('alice',doc,'PATCH',{fields:{status:'live'}})).status,403);
  sql.close();
});

test('official broadcast advances an indexed D1 cursor and writes private Team messages exactly once', async () => {
  const sql=new DatabaseSync(':memory:');
  for (const ddl of [resourcesSchema,appSchema,schema,socialGuardSchema,readFileSync(new URL('../migrations/0039_broadcast_recipient_indexes.sql',import.meta.url),'utf8'),readFileSync(new URL('../migrations/0042_broadcast_push_outbox.sql',import.meta.url),'utf8')]) sql.exec(ddl);
  const legacy=sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)');
  for (const [uid, data] of [['alice',{}],['bob',{banned:true}],['cara',{}],...Array.from({length:5},(_,i)=>['z'+i,{}])]) legacy.run(`users/${uid}`,'users',uid,uid,JSON.stringify(data),1);
  sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)').run('users/alice','users','users','alice','alice','{}',1,1);
  const db={prepare(q){const s=sql.prepare(q);return{bind(...args){return{run:async()=>s.run(...args),all:async()=>({results:s.all(...args)}),first:async()=>s.get(...args)||null};}};},async batch(stmts){sql.exec('BEGIN');try{for(const stmt of stmts)await stmt.run();sql.exec('COMMIT');}catch(e){sql.exec('ROLLBACK');throw e;}}};
  const app=createWorker(async token=>token),env={DB:db,FIREBASE_PROJECT_ID:'test',PAYMENT_ADMIN_UIDS:'admin',CLOUDINARY_CLOUD_NAME:'example'};
  const call=(uid,path,method='GET',body)=>app.fetch(new Request(`https://example.test${path}`,{method,headers:{authorization:`Bearer ${uid}`,'content-type':'application/json'},...(body?{body:JSON.stringify(body)}:{})}),env);
  const post=(uid,body)=>call(uid,'/v1/admin/broadcast','POST',body);
  const queue={action:'queue',id:'job',text:'Team update',mediaType:'',mediaUrl:''};
  assert.equal((await post('alice',queue)).status,403);
  assert.equal((await post('admin',{...queue,mediaType:'image',mediaUrl:'https://other.example/image.png'})).status,400);
  assert.equal((await call('alice','/v1/firestore/document?path=chats/team_cara','PUT',{data:{participants:['alice','cara']}})).status,403);
  assert.equal((await call('alice','/v1/firestore/document?path=notifications/broadcast_job_cara','PUT',{data:{fromUserId:'alice',toUserId:'cara',isRead:false}})).status,403);
  assert.equal((await post('admin',queue)).status,201);
  assert.equal((await post('admin',queue)).status,200);
  assert.equal((await post('admin',{...queue,text:'mutated'})).status,409);
  const readJob='/v1/firestore/document?path=officialBroadcasts/job';
  assert.equal((await call('alice',readJob)).status,403);
  assert.equal((await call('admin',readJob)).status,200);
  assert.equal((await call('admin',readJob,'PATCH',{fields:{processed:999}})).status,403);
  sql.exec("CREATE TRIGGER fail_team_message BEFORE INSERT ON friendbook_cf_firestore_documents WHEN NEW.collection_id='messages' BEGIN SELECT RAISE(ABORT, 'fixture failure'); END;");
  assert.equal((await post('admin',{action:'advance',id:'job'})).status,503);
  assert.equal(sql.prepare("SELECT count(*) n FROM friendbook_cf_firestore_documents WHERE parent_path IN ('notifications','chats')").get().n,0);
  assert.equal((await (await call('admin',readJob)).json()).data.cursor,'');
  sql.exec('DROP TRIGGER fail_team_message;');
  const firstBatch=await post('admin',{action:'advance',id:'job'});
  assert.equal(firstBatch.status,200);
  assert.deepEqual(await firstBatch.json(),{complete:false,processed:5});
  await resumeOfficialBroadcasts(env);
  const retry=await post('admin',{action:'advance',id:'job'});
  assert.deepEqual(await retry.json(),{complete:true,processed:7});
  assert.equal(sql.prepare("SELECT count(*) n FROM friendbook_cf_firestore_documents WHERE collection_id='messages'").get().n,7);
  assert.equal(sql.prepare('SELECT count(*) n FROM friendbook_cf_broadcast_push').get().n,7);
  const pushes=[];
  const send=async(_env,_db,actor,target,data,event)=>{
    pushes.push({actor,target,data,event});
    return {status:target==='alice' && pushes.filter(p=>p.target==='alice').length===1?503:200};
  };
  const now=Date.now()+1;
  await drainBroadcastPush(env,now,send);
  assert.equal(pushes.length,7);
  assert.equal(sql.prepare('SELECT count(*) n FROM friendbook_cf_broadcast_push').get().n,1);
  await drainBroadcastPush(env,now+300000,send);
  assert.equal(pushes.filter(p=>p.target==='alice').length,2);
  assert.equal(pushes.find(p=>p.target==='alice').data.chatId,'team_alice');
  assert.equal(sql.prepare('SELECT count(*) n FROM friendbook_cf_broadcast_push').get().n,0);
  const inbox='/v1/firestore/document?path=notifications/broadcast_job_alice';
  assert.equal((await call('cara',inbox)).status,403);
  assert.equal((await (await call('alice',inbox)).json()).data.fromUserId,'friendbook_team');
  const team='/v1/firestore/document?path=chats/team_alice';
  assert.equal((await (await call('alice',team)).json()).data.unreadCount.alice,1);
  assert.equal((await call('alice',team,'PATCH',{fields:{unreadCount:{alice:0}}})).status,200);
  assert.equal((await call('alice',team,'PATCH',{fields:{participantNames:{friendbook_team:'fake'}}})).status,403);
  sql.close();
});

test('chat membership migration backfills both stores and maintains indexed lookups', () => {
  const sql=new DatabaseSync(':memory:');
  for (const ddl of [appSchema,schema]) sql.exec(ddl);
  const legacy=sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)');
  legacy.run('chats/old','chats','old','alice',JSON.stringify({fields:{participants:{arrayValue:{values:[{stringValue:'alice'}, 'malformed']}}}}),1);
  sql.exec(readFileSync(new URL('../migrations/0040_chat_participant_lookup.sql',import.meta.url),'utf8'));
  const byUser=sql.prepare('SELECT chat_path FROM friendbook_cf_chat_participants WHERE participant_uid=?');
  assert.deepEqual(byUser.all('alice').map(r=>r.chat_path),['chats/old']);
  const insert=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
  insert.run('chats/new','chats','chats','new','alice','{"participants":["alice","bob"]}',1,1);
  assert.equal(byUser.get('bob').chat_path,'chats/new');
  sql.exec(`UPDATE friendbook_cf_firestore_documents SET document_json='{"participants":["alice"]}' WHERE path='chats/new'`);
  assert.equal(byUser.get('bob'),undefined);
  sql.exec("DELETE FROM friendbook_cf_firestore_documents WHERE path='chats/new'");
  assert.deepEqual(byUser.all('alice').map(r=>r.chat_path),['chats/old']);
  assert.match(sql.prepare('EXPLAIN QUERY PLAN SELECT chat_path FROM friendbook_cf_chat_participants WHERE participant_uid=?').get('alice').detail,/SEARCH.*PRIMARY KEY/);
  sql.close();
});

test('indexed tag search observes private posts, canonical precedence and edits', async () => {
  const sql=new DatabaseSync(':memory:');
  for(const ddl of [resourcesSchema,appSchema,schema]) sql.exec(ddl);
  const legacy=sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)');
  legacy.run('users/alice','users','alice','alice','{"displayName":"Alice"}',1);
  legacy.run('users/bob','users','bob','bob','{"displayName":"Bob"}',1);
  for(const [id,content,privacy] of [['visible','#دوستی @Alice','Public'],['private','#دوستی @Alice','Only me'],['prefix','@Alice2','Public'],['shadow','@Alice','Public']]) {
    legacy.run(`posts/${id}`,'posts',id,'alice',JSON.stringify({authorId:'alice',createdAt:1,content,privacy}),1);
  }
  sql.exec(readFileSync(new URL('../migrations/0041_post_tag_search.sql',import.meta.url),'utf8'));
  sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)').run('posts/shadow','posts','posts','shadow','alice','{"authorId":"alice","createdAt":1,"content":"no tag"}',1,1);
  const db={prepare(q){const s=sql.prepare(q);return{bind(...args){return{all:async()=>({results:s.all(...args)}),first:async()=>s.get(...args)||null};}};}};
  const app=createWorker(async()=> 'bob');
  const search=async postTag=>{
    const response=await app.fetch(new Request('https://example.test/v1/firestore/query',{method:'POST',headers:{authorization:'Bearer token','content-type':'application/json'},body:JSON.stringify({parent:'posts',postTag,limit:10})}),{DB:db,FIREBASE_PROJECT_ID:'test'});
    assert.equal(response.status,200,await response.clone().text());
    return (await response.json()).documents.map(d=>d.path);
  };
  assert.deepEqual(await search('@alice'),['posts/visible']);
  assert.deepEqual(await search('#دوستی'),['posts/visible']);
  sql.exec(`UPDATE friendbook_cf_documents SET document_json=json_set(document_json,'$.content','@Carol') WHERE path='posts/visible'`);
  assert.deepEqual(await search('@alice'),[]);
  assert.deepEqual(await search('@carol'),['posts/visible']);
  sql.close();
});

test('referral attribution is single-use and only the inviter can read their own count', async () => {
  const sql=new DatabaseSync(':memory:');
  for(const ddl of [resourcesSchema,appSchema,schema,socialGuardSchema,readFileSync(new URL('../migrations/0046_referral_attribution.sql',import.meta.url),'utf8')]) sql.exec(ddl);
  const put=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
  const now=Date.now();
  for(const [uid,time] of [['new',now],['alice',1],['bob',1],['old',1]]) put.run(`users/${uid}`,'users','users',uid,uid,JSON.stringify({displayName:uid}),time,time);
  const db={prepare(q){const s=sql.prepare(q);return{bind(...args){return{run:async()=>s.run(...args),all:async()=>({results:s.all(...args)}),first:async()=>s.get(...args)||null};}};},async batch(stmts){sql.exec('BEGIN');try{for(const stmt of stmts)await stmt.run();sql.exec('COMMIT');}catch(e){sql.exec('ROLLBACK');throw e;}}};
  const app=createWorker(async t=>t),env={DB:db,FIREBASE_PROJECT_ID:'test',PAYMENT_ADMIN_UIDS:'admin'};
  const call=(uid,path,body)=>app.fetch(new Request(`https://example.test${path}`,{method:body?'POST':'GET',headers:{authorization:`Bearer ${uid}`,'content-type':'application/json'},...(body?{body:JSON.stringify(body)}:{})}),env);
  const claim=(uid,inviter)=>call(uid,'/v1/firestore/claim-referral',{inviter});
  assert.equal((await claim('new','new')).status,400);
  assert.equal((await claim('old','alice')).status,403);
  const first=await claim('new','alice');assert.equal(first.status,201,await first.clone().text());
  assert.equal((await claim('new','alice')).status,200);
  assert.equal((await claim('new','bob')).status,409);
  const count='/v1/firestore/count?parent=referrals&filters='+encodeURIComponent(JSON.stringify([{field:'refBy',op:'==',value:'alice'}]));
  assert.equal((await call('bob',count)).status,403);
  assert.equal((await (await call('alice',count)).json()).count,1);
  assert.equal((await call('alice','/v1/firestore/query',{parent:'referrals'})).status,403);
  assert.equal((await (await call('admin','/v1/firestore/query',{parent:'referrals'})).json()).documents.length,1);
  sql.close();
});

test('copied D1 post timestamps retain order, media and privacy without overwriting mirrors', async () => {
  const sql=new DatabaseSync(':memory:');
  for(const ddl of [resourcesSchema,appSchema,schema]) sql.exec(ddl);
  const put=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
  const legacy=sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)');
  const old=1700000000000, newer=old+1000;
  put.run('users/owner','users','users','owner','owner','{"displayName":"Creator"}',1,1);
  put.run('users/viewer','users','users','viewer','viewer','{"displayName":"Viewer"}',1,1);
  const insert=(id,data)=>put.run(`posts/${id}`,'posts','posts',id,'owner',JSON.stringify({authorId:'owner',...data}),1,1);
  insert('newer',{createdAt:newer,mediaUrl:'https://res.cloudinary.com/example/image/upload/original.jpg'});
  insert('older',{createdAt:{__type:'timestamp',millis:old}});
  insert('private',{createdAt:newer+3000,privacy:'Only me'});
  legacy.run('posts/private','posts','private','owner',JSON.stringify({authorId:'owner',createdAt:newer+3000,privacy:'Public'}),1);
  legacy.run('posts/historical','posts','historical','owner',JSON.stringify({createTime:new Date(newer+1000).toISOString(),fields:{authorId:{stringValue:'owner'}}}),1);
  const ddl=readFileSync(new URL('../migrations/0047_legacy_post_feed_timestamps.sql',import.meta.url),'utf8');
  sql.exec(ddl);sql.exec(ddl);
  const db={prepare(q){const s=sql.prepare(q);return{bind(...args){return{all:async()=>({results:s.all(...args)}),first:async()=>s.get(...args)||null};}};}};
  const app=createWorker(async()=> 'viewer');
  const query=async startAfter=>{
    const res=await app.fetch(new Request('https://example.test/v1/firestore/query',{method:'POST',headers:{authorization:'Bearer token','content-type':'application/json'},body:JSON.stringify({parent:'posts',orderBy:[{field:'createdAt',direction:'DESCENDING'}],limit:2,startAfter})}),{DB:db,FIREBASE_PROJECT_ID:'test'});
    assert.equal(res.status,200);
    return (await res.json()).documents;
  };
  const first=await query();assert.deepEqual(first.map(d=>d.id),['historical','newer']);
  assert.equal(first[1].data.createdAt.millis,newer);
  assert.equal(first[1].data.mediaUrl,'https://res.cloudinary.com/example/image/upload/original.jpg');
  assert.deepEqual((await query([first[1].data.createdAt,first[1].id])).map(d=>d.id),['older']);
  sql.close();
});

test('direct calls authorize both call participants, reject forged transitions and stop ended media', async () => {
 const {authorizeCallMedia}=await import('../call-access.js');
 const sql=new DatabaseSync(':memory:');
 for (const ddl of [resourcesSchema,appSchema,schema,socialGuardSchema]) sql.exec(ddl);
 sql.exec(readFileSync(new URL('../migrations/0048_direct_chat_calls.sql',import.meta.url),'utf8'));
 const insert=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
 for (const uid of ['alice','bob','eve']) insert.run('users/'+uid,'users','users',uid,uid,JSON.stringify({displayName:uid}),1,1);
 const db={prepare(q){const stmt=sql.prepare(q);return{bind(...a){return{run:async()=>stmt.run(...a),first:async()=>stmt.get(...a)||null};}};},
   async batch(statements){sql.exec('BEGIN');try{for(const s of statements) await s.run();sql.exec('COMMIT');}catch(e){sql.exec('ROLLBACK');throw e;}}};
 const worker=createWorker(async token=>token),env={DB:db,FIREBASE_PROJECT_ID:'test'};
 const send=(uid,action,callId='first',callType='video')=>worker.fetch(new Request('https://example.test/v1/chat/call',{
   method:'POST',headers:{authorization:`Bearer ${uid}`,'content-type':'application/json'},
   body:JSON.stringify({chatId:'alice_bob',callId,action,peerId:'bob',callType})}),env);
 let r=await send('alice','start');assert.equal(r.status,200,await r.text());
 assert.equal(await authorizeCallMedia(db,'alice_bob','first','alice'),null);
 assert.equal((await send('alice','accept')).status,403);
 assert.equal((await send('eve','accept')).status,403);
 assert.equal((await send('bob','accept')).status,200);
 assert.equal(await authorizeCallMedia(db,'alice_bob','first','alice'),'video');
 assert.equal(await authorizeCallMedia(db,'alice_bob','first','bob'),'video');
 assert.equal(await authorizeCallMedia(db,'alice_bob','first','eve'),null);
 assert.equal((await send('alice','start','other')).status,409);
 assert.equal((await send('alice','end')).status,200);
 assert.equal(await authorizeCallMedia(db,'alice_bob','first','alice'),null);
 assert.equal((await send('alice','start','second','voice')).status,200);
 assert.equal((await send('alice','end','first')).status,409);
 assert.equal((await send('bob','accept','second','voice')).status,200);
 assert.equal(await authorizeCallMedia(db,'alice_bob','second','bob'),'voice');
 sql.prepare('UPDATE friendbook_cf_firestore_documents SET document_json=? WHERE path=?').run('{"blocked":["bob"]}','users/alice');
 assert.equal(await authorizeCallMedia(db,'alice_bob','second','bob'),null);
 sql.close();
});

test('indexed display-name prefix keeps canonical precedence and supports legacy names', async () => {
  const sql = new DatabaseSync(':memory:');
  for (const ddl of [resourcesSchema, appSchema, schema,
    readFileSync(new URL('../migrations/0053_people_name_prefix.sql', import.meta.url), 'utf8')]) sql.exec(ddl);
  const put=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
  const legacy=sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)');
  put.run('users/actor','users','users','actor','actor','{"displayName":"Viewer"}',1,1);
  put.run('users/changed','users','users','changed','changed','{"displayName":"New name"}',1,1);
  put.run('users/match','users','users','match','match','{"displayName":"Amir"}',1,1);
  legacy.run('users/changed','users','changed','changed','{"displayName":"Amir old"}',1);
  legacy.run('users/legacy','users','legacy','legacy','{"fields":{"displayName":{"stringValue":"Amina"}}}',1);
  const db={prepare(q){const s=sql.prepare(q);return {bind(...a){return {first:async()=>s.get(...a)||null,all:async()=>({results:s.all(...a)})};}};}};
  const response=await createWorker(async token=>token).fetch(new Request('https://example.test/v1/firestore/query',{
    method:'POST',headers:{authorization:'Bearer actor','content-type':'application/json'},
    body:JSON.stringify({parent:'users',limit:12,filters:[{field:'displayName',op:'>=',value:'Am'},{field:'displayName',op:'<=',value:'Am\uf8ff'}]}),
  }),{DB:db,FIREBASE_PROJECT_ID:'test'});
  assert.equal(response.status,200);
  assert.deepEqual((await response.json()).documents.map(doc=>doc.id).sort(),['legacy','match']);
  const plan=sql.prepare("EXPLAIN QUERY PLAN SELECT * FROM friendbook_cf_firestore_documents INDEXED BY idx_cf_users_display_name WHERE parent_path='users' AND json_extract(document_json,'$.displayName')>=? AND json_extract(document_json,'$.displayName')<?").all('Am','An');
  assert.ok(plan.some(row=>row.detail.includes('idx_cf_users_display_name')));
});

test('notification pages use bounded per-store reads and do not expose stale mirrors', async () => {
 const sql=new DatabaseSync(':memory:');sql.exec(resourcesSchema);sql.exec(appSchema);sql.exec(schema);
 sql.exec(readFileSync(new URL('../migrations/0055_notification_page_indexes.sql',import.meta.url),'utf8'));
 const f=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)'),a=sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)');
 const data=(uid,i)=>JSON.stringify({toUserId:uid,fromUserId:'sender',isRead:i%3===0,createdAt:{__type:'timestamp',millis:i}});
 for(let i=0;i<200;i++){
  const id='n'+i,path='notifications/'+id;
  if(i%2===0) f.run(path,'notifications','notifications',id,'sender',data('alice',i),1,1);
  else a.run(path,'notifications',id,'sender',data('alice',i),1);
 }
 f.run('notifications/shadow','notifications','notifications','shadow','sender',data('bob',999),1,1);
 a.run('notifications/shadow','notifications','shadow','sender',data('alice',999),1);
 let rows=0;
 const db={prepare(q){const stmt=sql.prepare(q);return{bind(...args){return{all:async()=>{const results=stmt.all(...args);rows+=results.length;return{results};},first:async()=>stmt.get(...args)||null};}};}};
 const app=createWorker(async t=>t),env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test'};
 const filters=[{field:'toUserId',op:'==',value:'alice'},{field:'isRead',op:'==',value:false}];
 const query=async extra=>{const res=await app.fetch(new Request('https://example.test/v1/firestore/query',{method:'POST',headers:{authorization:'Bearer alice','content-type':'application/json'},body:JSON.stringify({parent:'notifications',filters,orderBy:[{field:'createdAt',direction:'DESCENDING'}],limit:10,...extra})}),env);assert.equal(res.status,200,await res.clone().text());return await res.json();};
 const first=(await query({})).documents;const last=first.at(-1);const second=(await query({startAfter:[last.data.createdAt,last.id]})).documents;
 assert.equal(rows,40);const actual=[...first,...second].map(d=>d.id);
 assert.deepEqual(actual,Array.from({length:200},(_,i)=>199-i).filter(i=>i%3!==0).slice(0,20).map(i=>'n'+i));
 const initial=await query({knownRevisions:{}});
 const unchanged=await query({knownRevisions:initial.revisions});
 assert.deepEqual(unchanged.order,initial.order);assert.equal(unchanged.documents.length,0);
 const removed=initial.order[0];sql.prepare('DELETE FROM friendbook_cf_documents WHERE doc_id=?').run(removed);
 const delta=await query({knownRevisions:initial.revisions});
 assert.ok(!delta.order.includes(removed));assert.equal(delta.documents.length,1);
 sql.close();
});
