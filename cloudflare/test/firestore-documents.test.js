import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import { createWorker } from '../../worker.js';
import { hasD1Friendship } from '../app-documents.js';

const resourcesSchema = readFileSync(new URL('../migrations/0003_additive_interactions.sql', import.meta.url), 'utf8');
const schema = readFileSync(new URL('../migrations/0014_additive_firestore_documents.sql', import.meta.url), 'utf8');
const appSchema = readFileSync(new URL('../migrations/0011_additive_app_documents.sql', import.meta.url), 'utf8');
const mirrorMigration = readFileSync(new URL('../migrations/0017_sync_firestore_app_documents.sql', import.meta.url), 'utf8');

test('Firestore-compatible D1 endpoint accepts Firebase UIDs containing x', async () => {
  const sql = new DatabaseSync(':memory:');
  sql.exec(resourcesSchema);
  sql.exec(appSchema);
  sql.exec(schema);
  sql.exec(mirrorMigration);
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
  sql.exec(resourcesSchema); sql.exec(appSchema); sql.exec(schema);
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
  const count=await call('u1','/v1/firestore/count?parent=notifications');
  assert.equal((await count.json()).count,0);
  assert.equal((await call('u1','/v1/firestore/document?path=notifications/n1','PATCH',{fields:{toUserId:'u1'}})).status,403);
  assert.equal((await call('u1','/v1/docs/notifications/n1','PUT',{toUserId:'u1'})).status,403);
  assert.equal((await call('u1','/v1/firestore/batch','POST',{operations:[{op:'update',path:'notifications/n1',updates:{toUserId:'u1'}}]})).status,403);
  assert.equal((await call('u1','/v1/firestore/document?path=notifications/n1','DELETE')).status,403);
  assert.equal((await call('u2','/v1/firestore/batch','POST',{operations:[{op:'update',path:'notifications/n1',updates:{isRead:true}}]})).status,200);
  assert.equal((await call('u2','/v1/firestore/document?path=notifications/n1','PATCH',{fields:{text:'tampered'}})).status,403);
  assert.equal((await call('u3','/v1/firestore/document?parent=notifications','POST',{data:{fromUserId:'u1',toUserId:'u2',isRead:false}})).status,403);
  sql.close();
});

test('profiles and chats: private fields, membership and write bypasses across APIs', async () => {
  const sql=new DatabaseSync(':memory:'); sql.exec(resourcesSchema);sql.exec(appSchema);sql.exec(schema);
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
  const sql=new DatabaseSync(':memory:');sql.exec(resourcesSchema);sql.exec(appSchema);sql.exec(schema);
  const db={prepare(query){const stmt=sql.prepare(query);return{bind(...args){return{run:async()=>stmt.run(...args),all:async()=>({results:stmt.all(...args)}),first:async()=>stmt.get(...args)||null};}};},async batch(stmts){for(const s of stmts)await s.run();}};
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
  for (const collection of ['sessions','stories']) {
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
  assert.equal((await call('mallory','/v1/firestore/document?path=groups/old','PATCH',{fields:{name:'forged'}})).status,403);
  assert.equal((await call('alice',uri,'DELETE')).status,200);
  sql.close();
});
