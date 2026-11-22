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
  let perNotificationReads=0;
  const db = { prepare(query) { const stmt = sql.prepare(query); return { bind(...args) {
    return { run: async () => stmt.run(...args), all: async () => ({results:stmt.all(...args)}), first: async () => {if(args[0]==='notifications/n1')perNotificationReads++;return stmt.get(...args)||null;} };
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
  sql.prepare("UPDATE friendbook_cf_documents SET document_json=json_set(document_json,'$.toUserId','u1') WHERE path='notifications/n1'").run();
  perNotificationReads=0;
  assert.deepEqual((await (await call('u1','/v1/docs?collection=notifications')).json()).documents,[]);
  assert.equal((await (await call('u2','/v1/docs?collection=notifications')).json()).documents.length,1);
  assert.equal(perNotificationReads,0,'compatibility list resolves canonical rows in the list statement');
  sql.exec(readFileSync(new URL('../migrations/0036_inbox_unread_indexes.sql',import.meta.url),'utf8'));
  sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)').run('notifications/old','notifications','old','u2',JSON.stringify({fields:{toUserId:{stringValue:'u2'},isRead:{booleanValue:false}}}),1);
  const unread=[{field:'isRead',op:'==',value:false}];
  const unreadCount=uid=>call(uid,`/v1/firestore/count?parent=notifications&filters=${encodeURIComponent(JSON.stringify(unread))}`);
  assert.equal((await (await unreadCount('u2')).json()).count,2);
  assert.equal((await (await unreadCount('u1')).json()).count,0);
  const path='/v1/firestore/document?path=notifications/n1', read={fields:{isRead:true}};
  assert.equal((await call('u2',path,'PATCH',read)).status,200);
  const writes=()=>sql.prepare('SELECT total_changes() n').get().n;
  const before=writes();
  assert.equal((await call('u2',path,'PATCH',read)).status,200);
  assert.equal((await call('u2','/v1/firestore/batch','POST',{operations:[{op:'update',path:'notifications/n1',fields:{isRead:true}}]})).status,200);
  assert.equal(writes(),before,'duplicate notification opens write no rows');
  assert.equal((await call('u1',path,'PATCH',read)).status,403);
  assert.equal((await (await unreadCount('u2')).json()).count,1);

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
  assert.equal((await call('u1','/v1/firestore/document?path=notifications/n2','PUT',{data:{fromUserId:'u1',toUserId:'u2',isRead:false}})).status,200);
  const snapshot=sql.prepare("SELECT document_json,update_time FROM friendbook_cf_firestore_documents WHERE path='notifications/n1'").get();
  const mixedBefore=writes();
  const mixed={operations:['n1','n2'].map(id=>({op:'update',path:`notifications/${id}`,fields:{isRead:true}}))};
  assert.equal((await call('u2','/v1/firestore/batch','POST',mixed)).status,200);
  assert.equal(writes()-mixedBefore,2,'only newly read notification writes canonical and mirror');
  assert.deepEqual(sql.prepare("SELECT document_json,update_time FROM friendbook_cf_firestore_documents WHERE path='notifications/n1'").get(),snapshot);
  assert.equal((await call('u1','/v1/firestore/batch','POST',mixed)).status,403);
  sql.close();
});

test('profiles and chats: private fields, membership and write bypasses across APIs', async () => {
  const sql=new DatabaseSync(':memory:'); sql.exec(resourcesSchema);sql.exec(appSchema);sql.exec(schema);sql.exec(socialGuardSchema);
  let roomReads=0,roomGuards=0;
  const db={prepare(query){const stmt=sql.prepare(query);return {bind(...args){return {run:async()=>{if(query.includes('INSERT INTO friendbook_cf_social_guards') && (args[1]==='chats/c1' || (query.includes('FROM json_each(?) j') && JSON.parse(args[0]).some(([path])=>path==='chats/c1'))))roomGuards++;return stmt.run(...args);},all:async()=>({results:stmt.all(...args)}),first:async()=>{if(args[0]==='chats/c1')roomReads++;return stmt.get(...args)||null;}};}};},async batch(stmts){for(const s of stmts)await s.run();}};
  const app=createWorker(async token=>token),env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test'};
  const call=(uid,path,method='GET',body)=>app.fetch(new Request('https://example.test'+path,{method,headers:{authorization:'Bearer '+uid,'content-type':'application/json'},...(body?{body:JSON.stringify(body)}:{})}),env);
  const put=(uid,path,data)=>call(uid,'/v1/firestore/document?path='+path,'PUT',{data});
  assert.equal((await put('alice','users/alice',{uid:'alice',displayName:'Alice',email:'private@example.test',twoFactorPin:'1234',isAdmin:false})).status,200);
  const profileChanges=()=>sql.prepare('SELECT total_changes() n').get().n;
  const saved=profileChanges();
  assert.equal((await call('alice','/v1/firestore/document?path=users/alice','PATCH',{fields:{displayName:'Alice'}})).status,200);
  assert.equal(profileChanges(),saved,'unchanged literal profile edit uses no writes');
  assert.equal((await call('alice','/v1/firestore/document?path=users/alice','PUT',{data:{displayName:'Alice'},merge:true})).status,200);
  assert.equal(profileChanges(),saved,'unchanged literal profile merge uses no writes');
  assert.equal((await call('bob','/v1/firestore/document?path=users/alice','PUT',{data:{displayName:'Alice'},merge:true})).status,403);
  assert.equal((await call('alice','/v1/firestore/document?path=users/alice','PATCH',{fields:{bio:'new'}})).status,200);
  assert.ok(profileChanges()>saved,'a changed profile still commits');
  const profileHeartbeat={isOnline:true,isActive:true,showActiveStatus:true,lastSeenMs:Date.now(),lastSeen:{__fieldValue:'serverTimestamp'}};
  const profilePath='/v1/firestore/document?path=users/alice';
  assert.equal((await call('alice',profilePath,'PATCH',{fields:profileHeartbeat})).status,200);
  const mirrorProfile=()=>sql.prepare("SELECT document_json FROM friendbook_cf_documents WHERE path='users/alice'").get().document_json;
  const beforeHeartbeatMirror=mirrorProfile();
  sql.exec("UPDATE friendbook_cf_firestore_documents SET document_json=json_set(document_json,'$.lastSeen.millis',1) WHERE path='users/alice'");
  const beforeQuietHeartbeat=profileChanges();
  const heartbeatResult=await call('alice',profilePath,'PATCH',{fields:profileHeartbeat});
  assert.equal(heartbeatResult.status,200);
  assert.ok((await heartbeatResult.json()).data.lastSeen.millis>1);
  assert.equal(profileChanges()-beforeQuietHeartbeat,1,'quiet fallback heartbeat needs only one canonical row change');
  assert.equal(mirrorProfile(),beforeHeartbeatMirror);
  assert.equal((await call('alice',profilePath,'PATCH',{fields:{...profileHeartbeat,isOnline:false,isActive:false,showActiveStatus:false}})).status,200);
  assert.equal(JSON.parse(mirrorProfile()).showActiveStatus,false,'privacy transitions still update both stores');
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
  for (const path of ['/v1/docs/chats/c1','/v1/firestore/document?path=chats/c1']) {
    roomReads=0;
    assert.equal((await call('bob',path)).status,200);
    assert.equal(roomReads,1,'authorize against the fetched room without another point read');
  }
  assert.equal((await call('eve','/v1/firestore/document?path=chats/c1/messages/m1')).status,403);
  roomReads=0;
  assert.equal((await call('bob','/v1/firestore/document?path=chats/c1/messages/m1')).status,200);
  assert.equal(roomReads,1,'direct message reads authorize against one canonical room snapshot');
  sql.exec(readFileSync(new URL('../migrations/0040_chat_participant_lookup.sql',import.meta.url),'utf8'));
  sql.exec(readFileSync(new URL('../migrations/0058_skip_unchanged_chat_members.sql',import.meta.url),'utf8'));
  roomReads=0;
  const inbox=await (await call('bob','/v1/firestore/query','POST',{parent:'chats'})).json();
  assert.deepEqual(inbox.documents.map(doc=>doc.id),['c1']);
  assert.equal((await (await call('bob','/v1/firestore/count?parent=chats')).json()).count,1);
  assert.equal(roomReads,0,'indexed inbox pages and counts do not re-fetch canonical rooms');
  // The lookup index identifies candidates, but the stored room still decides access.
  sql.exec("INSERT INTO friendbook_cf_chat_participants(participant_uid,chat_path,source) VALUES ('eve','chats/c1',1)");
  const chats=await (await call('eve','/v1/firestore/query','POST',{parent:'chats'})).json();assert.deepEqual(chats.documents,[]);
  assert.equal((await call('eve','/v1/firestore/document?path=chats/c1','PATCH',{fields:{participants:['alice','eve']}})).status,403);
  assert.equal((await call('bob','/v1/firestore/document?path=chats/c1','PATCH',{fields:{participants:['alice','bob','eve']}})).status,403);
  assert.equal((await call('alice','/v1/firestore/document?path=chats/bypass','PUT',{data:{participants:['alice','bob','eve']}})).status,403);
  assert.equal((await call('alice','/v1/firestore/document?path=chats/solo','PUT',{data:{participants:['alice']}})).status,403);
  assert.equal((await call('eve','/v1/firestore/batch','POST',batch([{op:'set',path:'chats/c1/messages/m2',data:{senderId:'eve',text:'spoof'}}]))).status,403);
  assert.equal((await call('bob','/v1/firestore/batch','POST',batch([{op:'update',path:'chats/c1/messages/m1',updates:{text:'tamper'}}]))).status,403);
  roomReads=0;
  const beforeDelivered=profileChanges();
  assert.equal((await call('bob','/v1/firestore/document?path=chats/c1/messages/m1','PATCH',{fields:{isDelivered:true}})).status,200);
  assert.equal(roomReads,1,'single receipt does not re-read membership before its atomic guard');
  assert.equal(profileChanges()-beforeDelivered,1,'a single receipt changes only the message row');
  assert.equal((await call('bob','/v1/firestore/batch','POST',batch([{op:'update',path:'chats/c1/messages/m1',updates:{isSeen:true}}]))).status,200);
  const beforeRepeatReceipt=profileChanges();
  assert.equal((await call('bob','/v1/firestore/document?path=chats/c1/messages/m1','PATCH',{fields:{isSeen:true}})).status,200);
  assert.equal(profileChanges(),beforeRepeatReceipt,'a repeated single receipt writes no message or guard rows');
  assert.equal((await call('eve','/v1/firestore/document?path=chats/c1/messages/m1','PATCH',{fields:{isSeen:true}})).status,403);
  const messages=Array.from({length:12},(_,i)=>`chats/c1/messages/batch${i}`);
  assert.equal((await call('alice','/v1/firestore/batch','POST',batch(messages.map(path=>({op:'set',path,data:{senderId:'alice',receiverId:'bob',text:'batch'}}))))).status,200);
  const receipts=batch(messages.map(path=>({op:'update',path,updates:{isSeen:true}})));
  roomReads=0;roomGuards=0;
  assert.equal((await call('bob','/v1/firestore/batch','POST',receipts)).status,200);
  assert.equal(roomReads,1); // atomic guard compares the authorized snapshot directly
  assert.equal(roomGuards,1);
  roomGuards=0;
  assert.equal((await call('bob','/v1/firestore/batch','POST',receipts)).status,200);
  assert.equal(roomGuards,0,'repeated receipts perform no atomic guard writes');
  const exactMirror=()=>sql.prepare("SELECT document_json,updated_at FROM friendbook_cf_documents WHERE path='chats/c1'").get();
  const oldMirror=exactMirror();
  const reset={op:'update',path:'chats/c1',updates:{'unreadCount.bob':0}};
  const resetBatch=batch([...receipts.operations,reset]);
  const unchangedBefore=sql.prepare("SELECT doc_id,update_time FROM friendbook_cf_firestore_documents WHERE parent_path='chats/c1/messages' ORDER BY doc_id").all();
  const beforeReset=profileChanges();
  assert.equal((await call('bob','/v1/firestore/batch','POST',resetBatch)).status,200);
  assert.equal(roomGuards,1,'first unread reset still commits');
  assert.equal(profileChanges()-beforeReset,3,'only the room update and guard insert/delete write rows');
  assert.deepEqual(sql.prepare("SELECT doc_id,update_time FROM friendbook_cf_firestore_documents WHERE parent_path='chats/c1/messages' ORDER BY doc_id").all(),unchangedBefore);
  assert.deepEqual(exactMirror(),oldMirror,'receipt does not rewrite historical room mirror');
  sql.prepare("UPDATE friendbook_cf_documents SET updated_at=1 WHERE path='chats/c1'").run();
  sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)').run('chats/older','chats','older','alice',JSON.stringify({participants:['alice','bob']}),2);
  const beforeStandaloneMirror=exactMirror();
  sql.exec("UPDATE friendbook_cf_firestore_documents SET document_json=json_set(document_json,'$.unreadCount.bob',7) WHERE path='chats/c1'");
  const beforeStandaloneReset=profileChanges();
  assert.equal((await call('bob','/v1/firestore/document?path=chats/c1','PATCH',{fields:{'unreadCount.bob':0}})).status,200);
  assert.equal(profileChanges()-beforeStandaloneReset,1,'standalone direct reset writes only the canonical room');
  assert.deepEqual(exactMirror(),beforeStandaloneMirror);
  roomReads=0;
  const chatList=await (await call('bob','/v1/docs?collection=chats&limit=1')).json();
  assert.equal(chatList.documents[0].id,'c1','older list endpoint uses the current room order');
  assert.equal(chatList.documents[0].unreadCount.bob,0);
  assert.equal(roomReads,0,'the canonical list row needs no additional room lookup');

  roomGuards=0;
  assert.equal((await call('bob','/v1/firestore/batch','POST',resetBatch)).status,200);
  assert.equal(roomGuards,0,'unchanged receipts and already-zero unread require no guard writes');
  const note='notifications/receipt_mix';
  sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)')
    .run(note,'notifications','notifications','receipt_mix','alice',JSON.stringify({fromUserId:'alice',toUserId:'bob',isRead:true}),1,1);
  const beforeMixedReplay=profileChanges();
  assert.equal((await call('bob','/v1/firestore/batch','POST',batch([
    ...resetBatch.operations,{op:'update',path:note,updates:{isRead:true}}
  ]))).status,200);
  assert.equal(profileChanges(),beforeMixedReplay,'mixed literal acknowledgements need no data or guard writes');
  assert.equal((await call('eve','/v1/firestore/batch','POST',receipts)).status,403);
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
  let snapshotRereads=0;
  const db={prepare(query){const stmt=sql.prepare(query);return{bind(...args){return{run:async()=>stmt.run(...args),all:async()=>({results:stmt.all(...args)}),first:async()=>{if(query==='SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?')snapshotRereads++;return stmt.get(...args)||null;}};}};},async batch(stmts){sql.exec('BEGIN');try{for(const s of stmts)await s.run();sql.exec('COMMIT');}catch(e){sql.exec('ROLLBACK');throw e;}}};
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
  snapshotRereads=0;
  assert.equal((await call('bob','/v1/firestore/batch','POST',{operations:ops})).status,200);
  assert.equal(snapshotRereads,0,'canonical acceptance snapshots are guarded without point re-reads');
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
  db.batch=actualBatch;snapshotRereads=0;
  assert.equal((await call('bob','/v1/firestore/batch','POST',{operations:remove})).status,200);
  assert.equal(snapshotRereads,0,'canonical removal snapshots are guarded without point re-reads');
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

test('payment owner index preserves owner ACL, legacy precedence, count and admin visibility', async () => {
 const sql=new DatabaseSync(':memory:');
 for(const ddl of [resourcesSchema,appSchema,schema,readFileSync(new URL('../migrations/0074_payment_owner_lookup.sql',import.meta.url),'utf8')])sql.exec(ddl);
 const f=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
 const a=sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)');
 for(let i=0;i<1000;i++)f.run(`payments/f${i}`,'payments','payments',`f${i}`,'alice',JSON.stringify({userId:`unrelated${i}`,status:'pending'}),1,1);
 f.run('payments/a','payments','payments','a','wrong',JSON.stringify({userId:'alice',status:'pending'}),1,1);
 f.run('payments/shadow','payments','payments','shadow','alice',JSON.stringify({userId:'bob',status:'pending'}),1,1);
 a.run('payments/shadow','payments','shadow','alice',JSON.stringify({userId:'alice',status:'pending'}),1);
 a.run('payments/primitive','payments','primitive','wrong',JSON.stringify({fields:{userId:'alice',status:'pending'}}),1);
 a.run('payments/wrapped','payments','wrapped','wrong',JSON.stringify({fields:{userId:{stringValue:'alice'},status:{stringValue:'pending'}}}),1);
 a.run('payments/coerced','payments','coerced','wrong',JSON.stringify({fields:{userId:{stringValue:['alice']},status:{stringValue:'pending'}}}),1);
 let forced=false,full=0,returned=0,plans=[];
 const db={prepare(q){
   if(forced && q.includes('INDEXED BY idx_cf_payments_user'))throw Error('no such index: idx_cf_payments_user');
   if(q.includes('quota_root_payments'))full++;
   const stmt=sql.prepare(q);return{bind(...args){return{first:async()=>stmt.get(...args)||null,all:async()=>{
     const result=stmt.all(...args);
     if(q.includes('idx_cf_payments_user') || q.includes('idx_cf_legacy_payments_user')) {
       returned+=result.length;plans.push(...sql.prepare('EXPLAIN QUERY PLAN '+q).all(...args).map(row=>row.detail));
     }
     return{results:result};
   }};}};
 }};
 const worker=createWorker(async token=>token),env={DB:db,FIREBASE_PROJECT_ID:'test',PAYMENT_ADMIN_UIDS:'admin'};
 const query=async(actor,body)=>{
   const r=await worker.fetch(new Request('https://example.test/v1/firestore/query',{method:'POST',headers:{authorization:'Bearer '+actor,'content-type':'application/json'},body:JSON.stringify({parent:'payments',...body})}),env);
   assert.equal(r.status,200,await r.clone().text());return (await r.json()).documents;
 };
 for(const [actor,body] of [['alice',{}],['alice',{filters:[{field:'userId',op:'==',value:'alice'},{field:'status',op:'==',value:'pending'}],limit:1}],['bob',{}],['admin',{filters:[{field:'userId',op:'==',value:'alice'}]}]]) {
   forced=false;full=0;returned=0;const fast=await query(actor,body),scans=full,rows=returned;
   forced=true;const slow=await query(actor,body);forced=false;
   assert.deepEqual(fast,slow);assert.equal(scans,0);assert.ok(rows<10);
 }
 assert.deepEqual((await query('alice',{})).map(doc=>doc.id).sort(),['a','coerced','primitive','wrapped']);
 const count=await worker.fetch(new Request('https://example.test/v1/firestore/count?parent=payments',{headers:{authorization:'Bearer alice'}}),env);
 assert.equal((await count.json()).count,4);
 full=0;assert.equal((await query('admin',{})).length,1005);assert.ok(full>0,'unfiltered administrator retains all records');
 assert.match(plans.join('\n'),/SEARCH .*idx_cf_payments_user/);
 assert.match(plans.join('\n'),/SEARCH .*idx_cf_legacy_payments_user/);
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
  const changes=()=>sql.prepare('SELECT total_changes() n').get().n;
  const beforeReplay=changes();
  assert.equal((await call('bob',uri,'PATCH',{fields:{pendingMemberIds:{__fieldValue:'arrayUnion',values:['bob']}}})).status,200);
  assert.equal(changes(),beforeReplay,'repeated join request does not rewrite group or guard rows');
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

  // Existing canonical snapshots are guarded inside the write batch; avoid
  // re-reading each and do not rewrite an already-verified marker.
  sql.exec(`CREATE TABLE marker_updates(n INTEGER); INSERT INTO marker_updates VALUES(0);
    CREATE TRIGGER marker_update AFTER UPDATE ON friendbook_cf_interaction_resources
    WHEN NEW.resource='posts/gpost' BEGIN UPDATE marker_updates SET n=n+1; END;`);
  const prepared=db.prepare; let redundantReads=0;
  db.prepare=function(query){
    if(query==='SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?') redundantReads++;
    return prepared.call(this,query);
  };
  let canonicalBatchSize=0;
  db.batch=async stmts=>{canonicalBatchSize=stmts.length;return actualBatch(stmts);};
  assert.equal((await call('bob',post,'PATCH',{fields:{content:'edited once'}})).status,200);
  assert.equal(canonicalBatchSize,3,'canonical group edit needs no temporary guard writes');
  assert.equal(redundantReads,0,'canonical group and post snapshots do not get redundant point reads');
  assert.equal(sql.prepare('SELECT n FROM marker_updates').get().n,0,'verified marker is not rewritten');
  sql.prepare("UPDATE friendbook_cf_interaction_resources SET state='staging' WHERE resource='posts/gpost'").run();
  assert.equal((await call('bob',post,'PATCH',{fields:{content:'edited twice'}})).status,200);
  assert.equal(sql.prepare("SELECT state FROM friendbook_cf_interaction_resources WHERE resource='posts/gpost'").get().state,'verified');
  assert.equal(sql.prepare('SELECT n FROM marker_updates').get().n,2,'a staging marker is still promoted');
  db.prepare=prepared;

  // Membership can be revoked after authorization but before the batch. Its
  // guarded snapshot must abort the old write and the retry must reject Bob.
  const groupPath=`groups/${id}`, beforeRace=sql.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?').get(groupPath).document_json;
  let raced=false;
  db.batch=async stmts=>{
    if(!raced){raced=true;const current=JSON.parse(beforeRace);current.members=current.members.filter(member=>member!=='bob');
      sql.prepare('UPDATE friendbook_cf_firestore_documents SET document_json=? WHERE path=?').run(JSON.stringify(current),groupPath);}
    return actualBatch(stmts);
  };
  assert.equal((await call('bob',post,'PATCH',{fields:{content:'must not write'}})).status,403);
  assert.equal(JSON.parse(sql.prepare("SELECT document_json FROM friendbook_cf_firestore_documents WHERE path='posts/gpost'").get().document_json).content,'edited twice');
  db.batch=actualBatch;
  sql.prepare('UPDATE friendbook_cf_firestore_documents SET document_json=? WHERE path=?').run(beforeRace,groupPath);
  // A competing post change is retried against the new canonical state, not
  // silently overwritten by the previously authorized snapshot.
  raced=false;
  db.batch=async stmts=>{
    if(!raced){raced=true;sql.prepare("UPDATE friendbook_cf_firestore_documents SET document_json=json_set(document_json,'$.otherEdit','preserved') WHERE path='posts/gpost'").run();}
    return actualBatch(stmts);
  };
  assert.equal((await call('bob',post,'PATCH',{fields:{content:'after race'}})).status,200);
  const afterRace=JSON.parse(sql.prepare("SELECT document_json FROM friendbook_cf_firestore_documents WHERE path='posts/gpost'").get().document_json);
  assert.equal(afterRace.content,'after race'); assert.equal(afterRace.otherEdit,'preserved');
  db.batch=actualBatch;
  // Revocation of the trusted owner must also abort and reauthorize.
  raced=false;
  db.batch=async stmts=>{
    if(!raced){raced=true;sql.prepare("UPDATE friendbook_cf_trusted_groups SET owner_uid='mallory' WHERE group_path=?").run(groupPath);}
    return actualBatch(stmts);
  };
  assert.equal((await call('bob',post,'PATCH',{fields:{content:'owner race'}})).status,403);
  assert.equal(JSON.parse(sql.prepare("SELECT document_json FROM friendbook_cf_firestore_documents WHERE path='posts/gpost'").get().document_json).content,'after race');
  db.batch=actualBatch;
  sql.prepare("UPDATE friendbook_cf_trusted_groups SET owner_uid='alice' WHERE group_path=?").run(groupPath);
  sql.exec("CREATE TRIGGER fail_group_mirror BEFORE INSERT ON friendbook_cf_documents WHEN NEW.path='posts/gpost' BEGIN SELECT RAISE(ABORT,'mirror failure'); END");
  assert.notEqual((await call('bob',post,'PATCH',{fields:{content:'must roll back'}})).status,200);
  assert.equal(JSON.parse(sql.prepare("SELECT document_json FROM friendbook_cf_firestore_documents WHERE path='posts/gpost'").get().document_json).content,'after race');
  sql.exec('DROP TRIGGER fail_group_mirror');
  const oldMirror=sql.prepare("SELECT document_json FROM friendbook_cf_documents WHERE path='posts/gpost'").get().document_json;
  sql.exec("CREATE TRIGGER fail_group_marker BEFORE INSERT ON friendbook_cf_interaction_resources WHEN NEW.resource='posts/gpost' BEGIN SELECT RAISE(ABORT,'marker failure'); END");
  assert.notEqual((await call('bob',post,'PATCH',{fields:{content:'marker rollback'}})).status,200);
  assert.equal(JSON.parse(sql.prepare("SELECT document_json FROM friendbook_cf_firestore_documents WHERE path='posts/gpost'").get().document_json).content,'after race');
  assert.equal(sql.prepare("SELECT document_json FROM friendbook_cf_documents WHERE path='posts/gpost'").get().document_json,oldMirror);
  sql.exec('DROP TRIGGER fail_group_marker');
  // Historical group rows still take the checked legacy path, rather than
  // treating an unverified mirror as a canonical snapshot.
  const storedGroup=sql.prepare('SELECT * FROM friendbook_cf_firestore_documents WHERE path=?').get(groupPath);
  sql.prepare('DELETE FROM friendbook_cf_firestore_documents WHERE path=?').run(groupPath);
  assert.equal((await call('bob',post,'PATCH',{fields:{content:'legacy group'}})).status,200);
  sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)').run(
    storedGroup.path,storedGroup.parent_path,storedGroup.collection_id,storedGroup.doc_id,storedGroup.owner_uid,
    storedGroup.document_json,storedGroup.create_time,storedGroup.update_time);
  sql.prepare("DELETE FROM friendbook_cf_firestore_documents WHERE path='posts/gpost'").run();
  assert.equal((await call('bob',post,'PATCH',{fields:{content:'legacy post'}})).status,200);
  assert.equal(JSON.parse(sql.prepare("SELECT document_json FROM friendbook_cf_firestore_documents WHERE path='posts/gpost'").get().document_json).content,'legacy post');
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
  const originalBatch=db.batch;let batchSize=0;
  db.batch=async statements=>{batchSize=statements.length;return originalBatch(statements);};
  assert.equal((await call('alice','/v1/firestore/batch','POST',{operations:ops('arrayUnion')})).status,200);
  assert.equal(batchSize,4,'both canonical profiles and mirrors commit without temporary guards');
  db.batch=originalBatch;
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
  db.batch=async statements=>{sql.prepare("UPDATE friendbook_cf_firestore_documents SET document_json=json_set(document_json,'$.blocked',json('[\"alice\"]')) WHERE path='users/bob'").run();return actualBatch(statements);};
  assert.equal((await call('alice','/v1/firestore/batch','POST',{operations:ops('arrayUnion')})).status,409,'a racing block aborts both profile writes');
  assert.deepEqual(data('users/alice').following,[]);assert.deepEqual(data('users/bob').followers,[]);
  db.batch=actualBatch;sql.prepare("UPDATE friendbook_cf_firestore_documents SET document_json=json_remove(document_json,'$.blocked') WHERE path='users/bob'").run();
  sql.exec("CREATE TRIGGER fail_follow_mirror BEFORE INSERT ON friendbook_cf_documents WHEN NEW.path='users/bob' BEGIN SELECT RAISE(ABORT,'mirror failure'); END");
  assert.equal((await call('alice','/v1/firestore/batch','POST',{operations:ops('arrayUnion')})).status,409);
  assert.deepEqual(data('users/alice').following,[]);assert.deepEqual(data('users/bob').followers,[]);
  assert.deepEqual(JSON.parse(sql.prepare("SELECT document_json FROM friendbook_cf_documents WHERE path='users/alice'").get().document_json).following,[],'earlier mirror is also rolled back');
  sql.exec('DROP TRIGGER fail_follow_mirror');
  sql.prepare("DELETE FROM friendbook_cf_firestore_documents WHERE path='users/bob'").run();
  assert.equal((await call('alice','/v1/firestore/batch','POST',{operations:ops('arrayUnion')})).status,200,'legacy-only peer uses guarded promotion');
  assert.deepEqual(data('users/alice').following,['bob']);assert.deepEqual(data('users/bob').followers,['alice']);
  assert.equal((await call('alice','/v1/firestore/batch','POST',{operations:ops('arrayRemove')})).status,200);
  assert.equal((await call('bob','/v1/firestore/document?path=users/bob','PATCH',{fields:{blocked:['alice']}})).status,200);
  assert.equal((await call('alice','/v1/firestore/batch','POST',{operations:ops('arrayUnion')})).status,403);
  sql.close();
});

test('chat atomic guards reject concurrent room and message changes and orphan adoption', async () => {
  const sql=new DatabaseSync(':memory:'); sql.exec(resourcesSchema);sql.exec(appSchema);sql.exec(schema);sql.exec(socialGuardSchema);
  let race=null;
  const db={prepare(query){const stmt=sql.prepare(query);return {bind(...args){return {run:async()=>stmt.run(...args),all:async()=>({results:stmt.all(...args)}),first:async()=>{if(race && query.includes('RETURNING path')){const fn=race;race=null;fn();}return stmt.get(...args)||null;}};}};},async batch(stmts){
    if(race){const fn=race;race=null;fn();}
    sql.exec('BEGIN');try{for(const s of stmts)await s.run();sql.exec('COMMIT');}catch(e){sql.exec('ROLLBACK');throw e;}
  }};
  const app=createWorker(async token=>token),env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test'};
  const call=(uid,path,method,body)=>app.fetch(new Request('https://example.test'+path,{method,headers:{authorization:'Bearer '+uid,'content-type':'application/json'},body:JSON.stringify(body)}),env);
  const put=(uid,path,data)=>call(uid,'/v1/firestore/document?path='+path,'PUT',{data});
  const insert=(path,parent,coll,id,doc)=>sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)').run(path,parent,coll,id,'alice',JSON.stringify(doc),1,1);
  insert('chats/orphan/messages/old','chats/orphan/messages','messages','old',{senderId:'alice',text:'private'});
  assert.notEqual((await put('eve','chats/orphan',{participants:['eve']})).status,200);
  // Every historical representation in each store must still block adoption.
  for (const store of ['canonical','legacy']) {
    for (const kind of ['prefix','plain','typed']) {
      const room=`${store}_${kind}`;
      const id=kind==='prefix'?`${room}_old`:`other_${room}`;
      const doc=kind==='plain'?{chatId:room}:kind==='typed'?{fields:{chatId:{stringValue:room}}}:{};
      if(store==='canonical') insert(`messages/${id}`,'messages','messages',id,doc);
      else sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)').run(`messages/${id}`,'messages',id,'alice',JSON.stringify(doc),1);
      assert.notEqual((await put('eve',`chats/${room}`,{participants:['eve','bob']})).status,200,`${store} ${kind} orphan`);
      sql.prepare(`DELETE FROM ${store==='canonical'?'friendbook_cf_firestore_documents':'friendbook_cf_documents'} WHERE path=?`).run(`messages/${id}`);
      assert.equal((await put('eve',`chats/${room}`,{participants:['eve','bob']})).status,200,`${store} ${kind} control`);
    }
  }
  // An adjacent ID must not be mistaken for the requested room's prefix.
  insert('messages/neighborX_old','messages','messages','neighborX_old',{});
  assert.equal((await put('alice','chats/neighbor',{participants:['alice','bob']})).status,200);
  assert.equal((await put('alice','chats/room',{participants:['alice','bob']})).status,200);
  assert.equal((await call('alice','/v1/docs/chats/room','PUT',{participants:['alice']})).status,403);
  assert.equal((await call('alice','/v1/docs/messages/room_m','PUT',{chatId:'room',senderId:'alice'})).status,403);
  assert.equal((await call('bob','/v1/firestore/document?path=chats/room','PATCH',{fields:{adminIds:['bob']}})).status,403);
  race=()=>sql.prepare('UPDATE friendbook_cf_firestore_documents SET document_json=? WHERE path=?').run(JSON.stringify({participants:['eve']}),'chats/room');
  assert.notEqual((await put('alice','chats/room/messages/m1',{senderId:'alice',text:'must not commit'})).status,200);
  assert.equal(sql.prepare('SELECT path FROM friendbook_cf_firestore_documents WHERE path=?').get('chats/room/messages/m1'),undefined);
  sql.prepare('UPDATE friendbook_cf_firestore_documents SET document_json=? WHERE path=?').run(JSON.stringify({participants:['alice','bob']}),'chats/room');
  const beforeSend=sql.prepare('SELECT total_changes() n').get().n;
  assert.equal((await put('alice','chats/room/messages/m2',{senderId:'alice',text:'original'})).status,200);
  assert.equal(sql.prepare('SELECT total_changes() n').get().n-beforeSend,1,'standalone nested send writes one message and no guard rows');
  race=()=>insert('chats/room/messages/same','chats/room/messages','messages','same',{senderId:'bob',text:'winner'});
  assert.notEqual((await put('alice','chats/room/messages/same',{senderId:'alice',text:'loser'})).status,200);
  assert.equal(JSON.parse(sql.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?').get('chats/room/messages/same').document_json).text,'winner');
  race=()=>sql.prepare('UPDATE friendbook_cf_firestore_documents SET document_json=? WHERE path=?').run(JSON.stringify({senderId:'bob',text:'new owner'}),'chats/room/messages/m2');
  assert.notEqual((await call('alice','/v1/firestore/document?path=chats/room/messages/m2','PATCH',{fields:{text:'overwrite'}})).status,200);
  assert.equal(JSON.parse(sql.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?').get('chats/room/messages/m2').document_json).text,'new owner');
  race=()=>sql.prepare('UPDATE friendbook_cf_firestore_documents SET document_json=? WHERE path=?')
    .run(JSON.stringify({participants:['alice']}),'chats/room');
  assert.equal((await call('bob','/v1/firestore/document?path=chats/room/messages/m2','PATCH',{fields:{isSeen:true}})).status,503);
  assert.equal(JSON.parse(sql.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?').get('chats/room/messages/m2').document_json).isSeen,undefined);
  sql.prepare('UPDATE friendbook_cf_firestore_documents SET document_json=? WHERE path=?')
    .run(JSON.stringify({participants:['alice','bob'],unreadCount:{bob:7}}),'chats/room');
  race=()=>sql.prepare('UPDATE friendbook_cf_firestore_documents SET document_json=? WHERE path=?')
    .run(JSON.stringify({participants:['alice','bob'],unreadCount:{bob:8}}),'chats/room');
  assert.equal((await call('bob','/v1/firestore/document?path=chats/room','PATCH',{fields:{'unreadCount.bob':0}})).status,503);
  assert.equal(JSON.parse(sql.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?').get('chats/room').document_json).unreadCount.bob,8);
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
  const created=await app.fetch(new Request('https://example.test/v1/firestore/batch',{method:'POST',headers:{authorization:'Bearer alice','content-type':'application/json'},body:JSON.stringify({operations:[
    {op:'set',path:'chats/newGroup',data:room},
    {op:'set',path:'chats/newGroup/messages/welcome',data:{senderId:'alice',type:'system',text:'Group created'}},
  ]})}),env);
  assert.equal(created.status,200,await created.text());
  const added=await call('alice','chats/newGroup','PATCH',{fields:{participants:{__fieldValue:'arrayUnion',values:['eve']}}});
  assert.equal(added.status,200);
  const guest=await app.fetch(new Request('https://example.test/v1/firestore/document?path=chats/newGroup',{headers:{authorization:'Bearer eve'}}),env);
  assert.equal(guest.status,200);

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
  const accountReads={};
  const db={prepare(q){const stmt=sql.prepare(q);return{bind(...args){return{run:async()=>stmt.run(...args),all:async()=>({results:stmt.all(...args)}),first:async()=>{if(String(args[0]).startsWith('users/')) accountReads[args[0]]=(accountReads[args[0]]||0)+1;return stmt.get(...args)||null;}};}};}};
  const events=[],env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test',ANALYTICS_ENGINE:{writeDataPoint:e=>events.push(e)}};
  const app=createWorker(async t=>t);
  const call=(uid,path,method='GET')=>app.fetch(new Request('https://example.test'+path,{method,headers:{authorization:'Bearer '+uid}}),env,{waitUntil:()=>{}});
  assert.equal((await call('bob','/v1/views/posts/secret','POST')).status,403);
  assert.equal((await call('bob','/v1/views/posts/missing')).status,403);
  assert.equal(events.length,0);
  assert.equal((await call('bob','/v1/views/posts/public','POST')).status,200);
  assert.equal(events.length,1);
  accountReads['users/alice']=0;accountReads['users/bob']=0;
  const batch=await (await call('bob','/v1/views?collection=posts&ids=public,secret,missing')).json();
  assert.deepEqual(Object.keys(batch.views),['public']);
  assert.deepEqual(accountReads,{'users/alice':1,'users/bob':1});
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
  let race='';
  const db={prepare(q){const s=sql.prepare(q);return{bind(...a){return{run:async()=>s.run(...a),all:async()=>({results:s.all(...a)}),first:async()=>s.get(...a)||null};}};},async batch(stmts){
    if(race==='legacy')sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)').run('notifications/race','notifications','race','alice',JSON.stringify({fromUserId:'alice',toUserId:'bob',isRead:true,text:'winner'}),1);
    if(race==='canonical')sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)').run('notifications/race2','notifications','notifications','race2','alice',JSON.stringify({fromUserId:'alice',toUserId:'bob',isRead:true,text:'winner'}),1,1);
    race='';sql.exec('BEGIN');try{for(const s of stmts)await s.run();sql.exec('COMMIT');}catch(e){sql.exec('ROLLBACK');throw e;}}};
  const app=createWorker(async t=>t),env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test'};
  const req=(uid,id,method,body)=>app.fetch(new Request('https://example.test/v1/firestore/document?path=notifications/'+id,{method,headers:{authorization:'Bearer '+uid,'content-type':'application/json'},...(body?{body:JSON.stringify(body)}:{})}),env);
  const data={fromUserId:'alice',toUserId:'bob',isRead:false,text:'hello'};
  assert.equal((await req('alice','n','PUT',{data,createOnly:true})).status,200);
  assert.equal((await req('alice','n','GET')).status,403);
  assert.equal((await req('bob','n','PATCH',{fields:{isRead:true}})).status,200);
  assert.equal((await req('alice','n','PUT',{data,createOnly:true})).status,409);
  assert.equal(JSON.parse(sql.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?').get('notifications/n').document_json).isRead,true);
  race='legacy';assert.equal((await req('alice','race','PUT',{data,createOnly:true})).status,409);
  assert.equal(sql.prepare('SELECT path FROM friendbook_cf_firestore_documents WHERE path=?').get('notifications/race'),undefined);
  assert.equal(JSON.parse(sql.prepare('SELECT document_json FROM friendbook_cf_documents WHERE path=?').get('notifications/race').document_json).text,'winner');
  race='canonical';assert.equal((await req('alice','race2','PUT',{data,createOnly:true})).status,409);
  assert.equal(sql.prepare('SELECT path FROM friendbook_cf_documents WHERE path=?').get('notifications/race2'),undefined);
  assert.equal(JSON.parse(sql.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?').get('notifications/race2').document_json).isRead,true);
  sql.exec("CREATE TRIGGER fail_notice_mirror BEFORE INSERT ON friendbook_cf_documents WHEN NEW.path='notifications/fail' BEGIN SELECT RAISE(ABORT,'injected failure'); END");
  assert.notEqual((await req('alice','fail','PUT',{data,createOnly:true})).status,200);
  assert.equal(sql.prepare('SELECT path FROM friendbook_cf_firestore_documents WHERE path=?').get('notifications/fail'),undefined,'mirror failure must roll back canonical insert');
  assert.equal((await req('eve','denied','PUT',{data,createOnly:true})).status,403);
  assert.equal(sql.prepare('SELECT path FROM friendbook_cf_firestore_documents WHERE path=?').get('notifications/denied'),undefined);
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

test('chat membership windows reuse canonical rooms and still hide stale legacy membership', async () => {
  const sql=new DatabaseSync(':memory:');
  for (const ddl of [resourcesSchema,appSchema,schema]) sql.exec(ddl);
  sql.exec(readFileSync(new URL('../migrations/0040_chat_participant_lookup.sql',import.meta.url),'utf8'));
  const canonical=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
  const legacy=sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)');
  const addOld=(id,people)=>legacy.run(`chats/${id}`,'chats',id,'alice',JSON.stringify({participants:people}),1);
  const addCurrent=(id,people)=>canonical.run(`chats/${id}`,'chats','chats',id,'alice',JSON.stringify({participants:people}),1,1);
  for(let i=0;i<120;i++){const id=`mirror${i}`;addCurrent(id,['alice','bob']);addOld(id,['alice','bob']);}
  addOld('removed',['alice','bob']);addCurrent('removed',['alice','carol']);
  addOld('historical',['alice','bob']);
  for(let i=0;i<91;i++)addOld(`old${i}`,['alice','bob']);
  // A bad derived-membership row cannot authorize a caller.
  sql.exec("INSERT INTO friendbook_cf_chat_participants VALUES ('eve','chats/mirror0',1)");
  const checked=[],membershipPlans=[];
  const db={prepare(q){const stmt=sql.prepare(q);return{bind(...args){return{
    all:async()=>{if(q.includes('SELECT path FROM friendbook_cf_firestore_documents WHERE path IN ('))checked.push(args);if(q.includes('INDEXED BY idx_cf_chat_participant_source'))membershipPlans.push(...sql.prepare('EXPLAIN QUERY PLAN '+q).all(...args).map(row=>row.detail));return {results:stmt.all(...args)};},
    first:async()=>stmt.get(...args)||null,run:async()=>stmt.run(...args)
  };}};}};
  const app=createWorker(async t=>t),env={DB:db,FIREBASE_PROJECT_ID:'test'};
  const list=async uid=>{
    const response=await app.fetch(new Request('https://example.test/v1/firestore/query',{method:'POST',headers:{authorization:`Bearer ${uid}`,'content-type':'application/json'},body:JSON.stringify({parent:'chats'})}),env);
    assert.equal(response.status,200,await response.clone().text());return (await response.json()).documents;
  };
  const rooms=await list('bob');
  assert.equal(rooms.length,212);
  assert.ok(rooms.some(d=>d.id==='historical'));
  assert.ok(!rooms.some(d=>d.id==='removed'));
  assert.deepEqual(new Set(checked.flat()),new Set(['chats/historical','chats/removed',...Array.from({length:91},(_,i)=>`chats/old${i}`)]));
  assert.equal(checked.length,2);
  assert.ok(checked.every(args=>args.length<=90));
  assert.deepEqual(await list('eve'),[]);
  // Existing deployments without the new index have just used the fallback.
  // With migration 0073, both joins seek the caller AND the selected store.
  sql.exec(readFileSync(new URL('../migrations/0073_chat_membership_source_seek.sql',import.meta.url),'utf8'));
  const indexed=await list('bob');
  assert.deepEqual(indexed.map(d=>d.id).sort(),rooms.map(d=>d.id).sort());
  assert.equal(membershipPlans.filter(p=>/SEARCH m USING COVERING INDEX idx_cf_chat_participant_source \(participant_uid=\? AND source=\?\)/.test(p)).length,2);
  assert.deepEqual(await list('eve'),[]);
  sql.close();
});

test('inbox sorts all eligible rooms by current message time before limiting the page', async () => {
  const sql=new DatabaseSync(':memory:');sql.exec(resourcesSchema);sql.exec(appSchema);sql.exec(schema);sql.exec(socialGuardSchema);
  const insert=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
  for(let i=0;i<12;i++){
    const id=`room${i.toString().padStart(2,'0')}`;
    insert.run(`chats/${id}`,'chats','chats',id,'alice',JSON.stringify({participants:['alice','bob'],lastMessageTime:{__type:'timestamp',millis:200-i},lastMessage:'old',unreadCount:{bob:0}}),1,1);
  }
  const db={prepare(q){const stmt=sql.prepare(q);return{bind(...a){return{run:async()=>stmt.run(...a),all:async()=>({results:stmt.all(...a)}),first:async()=>stmt.get(...a)||null};}};},async batch(statements){
    sql.exec('BEGIN');try{for(const statement of statements)await statement.run();sql.exec('COMMIT');}catch(e){sql.exec('ROLLBACK');throw e;}
  }};
  const app=createWorker(async t=>t),env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test'};
  const fetchPage=async startAfter=>{
    const response=await app.fetch(new Request('https://example.test/v1/firestore/query',{method:'POST',headers:{authorization:'Bearer bob','content-type':'application/json'},body:JSON.stringify({parent:'chats',filters:[{field:'participants',op:'array-contains',value:'bob'}],orderBy:[{field:'inboxActivityAt',direction:'DESCENDING'}],limit:10,startAfter})}),env);
    assert.equal(response.status,200);return (await response.json()).documents;
  };
  const first=await fetchPage();assert.equal(first.length,10);assert.equal(first[0].id,'room00');assert.ok(!first.some(d=>d.id==='room11'));
  const room='chats/room11';
  const send=await app.fetch(new Request('https://example.test/v1/firestore/batch',{method:'POST',headers:{authorization:'Bearer alice','content-type':'application/json'},body:JSON.stringify({operations:[
    {op:'set',path:`${room}/messages/new`,data:{senderId:'alice',receiverId:'bob',text:'new message',createdAt:{__fieldValue:'serverTimestamp'}}},
    {op:'set',path:room,merge:true,data:{participants:['alice','bob'],lastMessage:'new message',lastMessageTime:{__fieldValue:'serverTimestamp'},unreadCount:{bob:{__fieldValue:'increment',value:1}}}},
  ]})}),env);
  assert.equal(send.status,200,await send.text());
  const message=await app.fetch(new Request(`https://example.test/v1/firestore/document?path=${room}/messages/new`,{headers:{authorization:'Bearer bob'}}),env);
  assert.equal(message.status,200);assert.equal((await message.json()).data.text,'new message');
  const refreshed=await fetchPage();
  assert.equal(refreshed[0].id,'room11');assert.equal(refreshed[0].data.lastMessage,'new message');assert.equal(refreshed[0].data.unreadCount.bob,1);
  assert.ok(refreshed[0].data.inboxActivityAt>refreshed[1].data.inboxActivityAt);
  assert.equal(new Set(refreshed.map(d=>d.id)).size,refreshed.length);
  const tail=refreshed.at(-1);
  assert.ok(!(await fetchPage([tail.data.inboxActivityAt,tail.id])).some(d=>d.id==='room11'));
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
  const app=createWorker(async t=>t),env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test',FIREBASE_ADMIN_SERVICE_ACCOUNT:'test-not-used'};
  const pending=[];let commitObserved=false;
  const ctx={waitUntil(promise){commitObserved=!!sql.prepare("SELECT 1 FROM friendbook_cf_firestore_documents WHERE path='chats/room/messages/m1'").get();pending.push(promise);}};
  const call=(uid,ops,chatPush=false)=>app.fetch(new Request('https://example.test/v1/firestore/batch',{method:'POST',headers:{authorization:'Bearer '+uid,'content-type':'application/json'},body:JSON.stringify({operations:ops,chatPush})}),env,ctx);
  const send=[{op:'set',path:'chats/room/messages/m1',data:{senderId:'alice',text:'hello'}},{op:'set',path:'chats/room',merge:true,data:{participants:['alice','bob'],unreadCount:{bob:{__fieldValue:'increment',value:1}}}}];
  assert.equal((await call('alice',[{op:'set',path:'chats/room',data:{participants:['alice','bob'],unreadCount:{bob:0}}}])).status,200);
  race=true;const sent=await call('alice',send,true);assert.equal(sent.status,200);
  assert.equal((await sent.json()).pushScheduled,true);
  assert.equal(commitObserved,true);assert.equal(pending.length,1);
  assert.equal((await call('mallory',send,true)).status,403);
  assert.equal(pending.length,1);await Promise.all(pending);
  assert.equal(sql.prepare("SELECT COUNT(*) n FROM friendbook_cf_firestore_documents WHERE path='chats/room/messages/m1'").get().n,1);
  const room=JSON.parse(sql.prepare("SELECT document_json FROM friendbook_cf_firestore_documents WHERE path='chats/room'").get().document_json);
  assert.equal(room.unreadCount.bob,1);assert.equal(room.otherUpdate,'new');
  sql.close();
});

test('ordinary direct sends guard both snapshots without temporary writes and roll back failures', async () => {
  const sql=new DatabaseSync(':memory:');sql.exec(resourcesSchema);sql.exec(appSchema);sql.exec(schema);sql.exec(socialGuardSchema);
  let race=null,fastBatches=0;
  const db={prepare(q){const stmt=sql.prepare(q);return{bind(...a){return{query:q,run:async()=>stmt.run(...a),all:async()=>({results:stmt.all(...a)}),first:async()=>stmt.get(...a)||null};}};},async batch(stmts){
    if(stmts.some(s=>s.query.includes('THEN ?3 ELSE NULL END')))fastBatches++;
    if(race){const fn=race;race=null;fn();}
    sql.exec('BEGIN');try{for(const st of stmts)await st.run();sql.exec('COMMIT');}catch(e){sql.exec('ROLLBACK');throw e;}
  }};
  const app=createWorker(async t=>t),env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test'};
  const call=(actor,id,participants=['alice','bob'])=>app.fetch(new Request('https://example.test/v1/firestore/batch',{method:'POST',headers:{authorization:'Bearer '+actor,'content-type':'application/json'},body:JSON.stringify({operations:[
    {op:'set',path:`chats/room/messages/${id}`,data:{senderId:actor,receiverId:'bob',text:id}},
    {op:'set',path:'chats/room',merge:true,data:{participants,lastMessage:id,unreadCount:{bob:{__fieldValue:'increment',value:1}}}},
  ]})}),env);
  const initial=JSON.stringify({participants:['alice','bob'],unreadCount:{bob:0}});
  sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)').run('chats/room','chats','chats','room','alice',initial,1,1);
  const changes=()=>sql.prepare('SELECT total_changes() n').get().n;
  let before=changes();assert.equal((await call('alice','m1')).status,200);
  assert.equal(changes()-before,2,'only message and canonical room change');assert.equal(fastBatches,1);
  assert.equal(JSON.parse(sql.prepare("SELECT document_json FROM friendbook_cf_firestore_documents WHERE path='chats/room'").get().document_json).unreadCount.bob,1);
  assert.equal((await call('eve','forged')).status,403);assert.equal(fastBatches,1);
  race=()=>sql.prepare("UPDATE friendbook_cf_firestore_documents SET document_json=? WHERE path='chats/room'").run(JSON.stringify({participants:['eve','bob'],unreadCount:{bob:1}}));
  assert.equal((await call('alice','revoked')).status,403,'retry reauthorizes changed membership');
  assert.equal(sql.prepare("SELECT 1 FROM friendbook_cf_firestore_documents WHERE path='chats/room/messages/revoked'").get(),undefined);
  sql.prepare("UPDATE friendbook_cf_firestore_documents SET document_json=? WHERE path='chats/room'").run(initial);
  race=()=>sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)').run('chats/room/messages/legacy','messages','legacy','bob',JSON.stringify({senderId:'bob',text:'old'}),1);
  assert.equal((await call('alice','legacy')).status,503,'a legacy-only message cannot be overwritten');
  assert.equal(sql.prepare("SELECT 1 FROM friendbook_cf_firestore_documents WHERE path='chats/room/messages/legacy'").get(),undefined);
  assert.equal(JSON.parse(sql.prepare("SELECT document_json FROM friendbook_cf_firestore_documents WHERE path='chats/room'").get().document_json).unreadCount.bob,0);
  sql.exec("CREATE TRIGGER fail_room BEFORE UPDATE ON friendbook_cf_firestore_documents WHEN NEW.path='chats/room' BEGIN SELECT RAISE(ABORT,'simulated failure'); END");
  assert.equal((await call('alice','rollback')).status,503);
  assert.equal(sql.prepare("SELECT 1 FROM friendbook_cf_firestore_documents WHERE path='chats/room/messages/rollback'").get(),undefined,'message insert rolls back if room update fails');
  sql.exec('DROP TRIGGER fail_room');
  assert.equal((await call('alice','rollback')).status,200);
  assert.equal(JSON.parse(sql.prepare("SELECT document_json FROM friendbook_cf_firestore_documents WHERE path='chats/room'").get().document_json).unreadCount.bob,1);
  const roomBeforeMirrorFailure=sql.prepare("SELECT document_json FROM friendbook_cf_firestore_documents WHERE path='chats/room'").get().document_json;
  sql.exec("CREATE TRIGGER fail_mirror BEFORE INSERT ON friendbook_cf_documents WHEN NEW.path='chats/room' BEGIN SELECT RAISE(ABORT,'simulated mirror failure'); END");
  assert.equal((await call('bob','mirrorfail',['bob','alice'])).status,503);
  assert.equal(sql.prepare("SELECT 1 FROM friendbook_cf_firestore_documents WHERE path='chats/room/messages/mirrorfail'").get(),undefined);
  assert.equal(sql.prepare("SELECT document_json FROM friendbook_cf_firestore_documents WHERE path='chats/room'").get().document_json,roomBeforeMirrorFailure,'mirror failure rolls back room too');
  sql.exec('DROP TRIGGER fail_mirror');
  before=changes();assert.equal((await call('bob','reply',['bob','alice'])).status,200);
  assert.equal(changes()-before,3,'reordered participants still update the mirror');
  const mirror=JSON.parse(sql.prepare("SELECT document_json FROM friendbook_cf_documents WHERE path='chats/room'").get().document_json);
  assert.deepEqual(mirror.participants,['bob','alice']);assert.equal(mirror.lastMessage,'reply');
  const fastBeforeRoot=fastBatches;
  const legacyRoot=await app.fetch(new Request('https://example.test/v1/firestore/batch',{method:'POST',headers:{authorization:'Bearer alice','content-type':'application/json'},body:JSON.stringify({operations:[
    {op:'set',path:'messages/root',data:{senderId:'alice',chatId:'room',text:'root message'}},
    {op:'set',path:'chats/room',merge:true,data:{lastMessage:'root message'}},
  ]})}),env);
  assert.equal(legacyRoot.status,200);assert.equal(fastBatches,fastBeforeRoot,'root messages use the compatibility path');
  assert.ok(sql.prepare("SELECT path FROM friendbook_cf_documents WHERE path='messages/root'").get(),'root message mirror is preserved');
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
 for (const file of ['0049_friend_request_status_indexes.sql','0069_remove_redundant_request_indexes.sql'])
   sql.exec(readFileSync(new URL(`../migrations/${file}`,import.meta.url),'utf8'));
 assert.deepEqual(await query('alice','friend_requests'),['old','r1']);
 assert.deepEqual(await query('eve','friend_requests'),['old','r2']);
 const q="SELECT path FROM friendbook_cf_firestore_documents WHERE parent_path='friend_requests' AND json_extract(document_json,'$.fromUid')=? UNION ALL SELECT path FROM friendbook_cf_firestore_documents WHERE parent_path='friend_requests' AND json_extract(document_json,'$.toUid')=? AND json_extract(document_json,'$.fromUid') IS NOT ?";
 const plan=sql.prepare('EXPLAIN QUERY PLAN '+q).all('alice','alice','alice').map(x=>x.detail).join('\n');assert.match(plan,/SEARCH.*idx_cf_request_canonical_out_status/);assert.match(plan,/SEARCH.*idx_cf_request_canonical_in_status/);
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
 const gap={...older,endBefore:[{__type:'timestamp',millis:18},'m055']};
 assert.deepEqual(await query(gap),await query(gap,true));
 assert.equal(fastCalls,3,'gap paging must not use the full history scan');
 assert.deepEqual(await query({...gap,startAfter:[{__type:'timestamp',millis:1},'m005']}),[]);
 assert.equal(fastCalls,4);
 const plan=sql.prepare("EXPLAIN QUERY PLAN SELECT path FROM friendbook_cf_firestore_documents WHERE parent_path=? ORDER BY CASE WHEN json_type(document_json,'$.createdAt') IN ('integer','real') THEN 2 ELSE 4 END DESC, CASE WHEN json_type(document_json,'$.createdAt') IN ('integer','real') THEN json_extract(document_json,'$.createdAt') ELSE json_extract(document_json,'$.createdAt.millis') END DESC,doc_id DESC LIMIT ?").all('chats/r/messages',10).map(x=>x.detail).join('\n');
 assert.match(plan,/idx_cf_firestore_chat_created_page/);
 // Legacy bridge triggers the existing full merge instead of silently losing old messages.
 sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)').run('messages/r_old','messages','r_old','alice',JSON.stringify({senderId:'alice',chatId:'r',createdAt:100}),1);
 assert.deepEqual(await query(first),await query(first,true));assert.equal(fastCalls,4);
 sql.prepare("DELETE FROM friendbook_cf_documents WHERE path='messages/r_old'").run();
 f.run('chats/r/messages/weird','chats/r/messages','messages','weird','alice',JSON.stringify({senderId:'alice',createdAt:'old'}),1,1);
 assert.deepEqual(await query(first),await query(first,true));assert.equal(fastCalls,4);assert.ok(fullCalls>0);
 sql.close();
});

for (const root of ['posts','reels']) test(`bounded ${root} pages fill after privacy checks and preserve legacy precedence and cursors`, async () => {
 const sql=new DatabaseSync(':memory:');sql.exec(resourcesSchema);sql.exec(appSchema);sql.exec(schema);
 sql.exec(readFileSync(new URL('../migrations/0029_feed_page_indexes.sql',import.meta.url),'utf8'));
 sql.exec(readFileSync(new URL('../migrations/0051_feed_exception_indexes.sql',import.meta.url),'utf8'));
 if(root==='reels')sql.exec(readFileSync(new URL('../migrations/0063_reel_page_indexes.sql',import.meta.url),'utf8'));
 const f=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)'),a=sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)');
 const user=(id,data)=>f.run('users/'+id,'users','users',id,id,JSON.stringify(data),1,1);user('alice',{uid:'alice'});user('bob',{uid:'bob'});
 for(let i=0;i<100;i++){
  const id='p'+String(i).padStart(3,'0'),createdAt={__type:'timestamp',millis:Math.floor(i/3)};
  const data={authorId:'alice',privacy:i>60?'Only me':'Public',createdAt,content:'canonical'};
  if(i%2===0) f.run(root+'/'+id,root,root,id,'alice',JSON.stringify(data),1,1);
  else a.run(root+'/'+id,root,id,'alice',JSON.stringify(data),1);
 }
 // A stale public mirror cannot override the newer private canonical row.
 a.run(root+'/p098',root,'p098','alice',JSON.stringify({authorId:'alice',privacy:'Public',createdAt:{__type:'timestamp',millis:100}}),1);
 for(let i=0;i<2000;i++){
  const id=`old${i}`, data=JSON.stringify({authorId:'alice',privacy:'Public',createdAt:{__type:'timestamp',millis:-1-i}});
  f.run(root+'/'+id,root,root,id,'alice',data,1,1);a.run(root+'/'+id,root,id,'alice',data,1);
 }
 let batchReads=0,maxRows=0,postReads=0,legacyProbes=0,shadowChecks=0;
 sql.function('quota_probe',path=>{legacyProbes++;return path;});
 const pageLimits=[];
 const db={prepare(q){const stmt=sql.prepare(q.includes('ORDER BY CASE WHEN json_type(a.document_json')?q.replace('f.path=a.path','f.path=quota_probe(a.path)'):q);return{bind(...args){return{all:async()=>{const results=stmt.all(...args);if(q.includes('FROM friendbook_cf_firestore_documents WHERE path IN (') && (q.startsWith('SELECT path FROM') || q.startsWith('SELECT path,parent_path FROM'))){shadowChecks++;assert.ok(args.length<=100,'bounded D1 variables');}if(q.includes('ORDER BY CASE WHEN json_type(a.document_json')){batchReads++;pageLimits.push(args.at(-1));maxRows=Math.max(maxRows,results.length);}return{results};},first:async()=>{if(q.includes('SELECT document_json') && String(args[0]).startsWith(root+'/'))postReads++;return stmt.get(...args)||null;}};}};}};
 const app=createWorker(async t=>t),env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test'};
 const query=async(spec,full=false)=>{let body={parent:root,orderBy:[{field:'createdAt',direction:'DESCENDING'}],limit:12,...spec};if(full)body.filters=[{field:'privacy',op:'!=',value:'Never'}];const r=await app.fetch(new Request('https://example.test/v1/firestore/query',{method:'POST',headers:{authorization:'Bearer bob','content-type':'application/json'},body:JSON.stringify(body)}),env);assert.equal(r.status,200,await r.clone().text());return (await r.json()).documents;};
 const first=await query({}),base=await query({},true);assert.deepEqual(first.map(d=>d.id),base.map(d=>d.id));assert.equal(first.length,12);assert.ok(batchReads>=4);assert.ok(maxRows<=24);assert.deepEqual(pageLimits.slice(0,2),[12,12]);assert.ok(legacyProbes<=72,`legacy probes: ${legacyProbes}`);
 legacyProbes=0;shadowChecks=0;
 const mirrorCursor={startAfter:[{__type:'timestamp',millis:0},'p000']};
 const mirrored=await query(mirrorCursor);
 assert.equal(shadowChecks,0,'fully mirrored page needs no extra canonical shadow probes');
 assert.deepEqual(mirrored.map(d=>d.id),(await query(mirrorCursor,true)).map(d=>d.id));
 assert.ok(legacyProbes<=12,`mirror-only page must not scan older copies: ${legacyProbes}`);
 // Legacy-only windows may be as large as 100. Split shadow checks below
 // D1's bound-variable ceiling without losing any historical rows.
 for(let i=0;i<100;i++) {
  const id=`legacyOnly${i}`;
  a.run(`${root}/${id}`,root,id,'alice',JSON.stringify({authorId:'alice',privacy:'Public',createdAt:{__type:'timestamp',millis:-3000-i}}),1);
 }
 shadowChecks=0;
 const onlyOld=await query({limit:100,startAfter:[{__type:'timestamp',millis:-2999},'p000']});
 assert.equal(onlyOld.length,100);assert.equal(shadowChecks,2,'100 unknown paths require two bounded checks');
 const last=first.at(-1),after={startAfter:[last.data.createdAt,last.id]};const second=await query(after),baseSecond=await query(after,true);assert.deepEqual(second.map(d=>d.id),baseSecond.map(d=>d.id));assert.equal(new Set([...first,...second].map(d=>d.id)).size,24);
 if(root==='posts') {
  sql.prepare("UPDATE friendbook_cf_documents SET document_json=? WHERE path='posts/p098'").run(JSON.stringify({fields:{authorId:{stringValue:'alice'},privacy:{stringValue:'Public'}}}));
  const filter={field:'authorId',op:'==',value:'alice'};
  const before=batchReads;
  const scoped=await query({filters:[filter]});
  assert.ok(batchReads>before,'shadowed historical mirror must not disable the indexed page');
  assert.deepEqual(scoped.map(d=>d.id),first.map(d=>d.id));
  assert.ok(!scoped.some(d=>d.id==='p098'),'private canonical row must still win');
 }
 let prior=batchReads;
 // A fractional timestamp still uses the JS comparator, without forcing a full scan.
 f.run(root+'/odd',root,root,'odd','alice',JSON.stringify({authorId:'alice',privacy:'Public',createdAt:{__type:'timestamp',millis:22.5}}),1,1);
 a.run(root+'/old-wrapper',root,'old-wrapper','alice',JSON.stringify({fields:{authorId:{stringValue:'alice'},privacy:{stringValue:'Public'},createdAt:{timestampValue:'1970-01-01T00:00:00.019Z'}}}),1);
 postReads=0;
 const fallback=await query({});const fallbackReads=postReads;
 assert.deepEqual(fallback.map(d=>d.id),(await query({},true)).map(d=>d.id));assert.ok(batchReads>prior);
 assert.ok(fallback.some(d=>d.id==='old-wrapper'));
 assert.ok(fallbackReads<100,`fallback should authorize only enough rows to fill the page, not every post: ${fallbackReads}`);
 const next=await query({startAfter:[fallback.at(-1).data.createdAt,fallback.at(-1).id]});
 assert.deepEqual(next.map(d=>d.id),(await query({startAfter:[fallback.at(-1).data.createdAt,fallback.at(-1).id]},true)).map(d=>d.id));
 assert.equal(new Set([...fallback,...next].map(d=>d.id)).size,24);
 if(root==='posts') {
  const filters=[{field:'authorId',op:'==',value:'alice'}];
  const before=batchReads;
  const scoped=await query({filters});
  const scopedNext=await query({filters,startAfter:[scoped.at(-1).data.createdAt,scoped.at(-1).id]});
  assert.ok(batchReads>before,'unshadowed historical posts must still use bounded pages');
  assert.deepEqual(scoped.map(d=>d.id),fallback.map(d=>d.id));
  assert.deepEqual(scopedNext.map(d=>d.id),next.map(d=>d.id));
 }
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
 sql.exec("CREATE TRIGGER fail_existing_profile_mirror BEFORE INSERT ON friendbook_cf_documents WHEN NEW.path='users/alice' BEGIN SELECT RAISE(ABORT,'mirror failure'); END");
 assert.equal((await call('alice',{data:{displayName:'Failed'},merge:true})).status,503);
 assert.equal(JSON.parse(sql.prepare("SELECT document_json FROM friendbook_cf_firestore_documents WHERE path='users/alice'").get().document_json).displayName,'After');
 sql.close();
});

test('no-op profile merges recheck snapshots and preserve legacy promotion', async () => {
 const sql=new DatabaseSync(':memory:');for(const s of [resourcesSchema,appSchema,schema,socialGuardSchema])sql.exec(s);
 const f=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
 const a=sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)');
 const profile={uid:'alice',displayName:'Alice',bio:'original'};
 f.run('users/alice','users','users','alice','alice',JSON.stringify(profile),1,1);
 a.run('users/alice','users','alice','alice',JSON.stringify({...profile,id:'alice'}),1);
 a.run('users/old','users','old','old',JSON.stringify({uid:'old',displayName:'Old'}),1);
 let race=false;
 const db={prepare(q){const stmt=sql.prepare(q);return{bind(...args){return{
   run:async()=>stmt.run(...args),all:async()=>({results:stmt.all(...args)}),first:async()=>{
    if(race && q.includes('AS current,') && q.includes('AS mirror')){
      race=false;sql.prepare("UPDATE friendbook_cf_firestore_documents SET document_json=?,update_time=2 WHERE path='users/alice'")
        .run(JSON.stringify({...profile,displayName:'Concurrent',bio:'external'}));
    }
    return stmt.get(...args)||null;
   }
 };}};},async batch(stmts){sql.exec('BEGIN');try{for(const s of stmts)await s.run();sql.exec('COMMIT');}catch(e){sql.exec('ROLLBACK');throw e;}}};
 const app=createWorker(async t=>t),env={DB:db,FIREBASE_PROJECT_ID:'test'};
 const merge=(uid,data)=>app.fetch(new Request(`https://example.test/v1/firestore/document?path=users/${uid}`,{method:'PUT',headers:{authorization:`Bearer ${uid}`,'content-type':'application/json'},body:JSON.stringify({data,merge:true})}),env);
 const before=sql.prepare('SELECT total_changes() n').get().n;
 assert.equal((await merge('alice',{displayName:'Alice'})).status,200);
 assert.equal(sql.prepare('SELECT total_changes() n').get().n,before);
 sql.prepare("UPDATE friendbook_cf_documents SET document_json=? WHERE path='users/alice'").run(JSON.stringify({...profile,id:'alice',displayName:'Stale'}));
 const staleChanges=sql.prepare('SELECT total_changes() n').get().n;
 assert.equal((await merge('alice',{displayName:'Alice'})).status,200);
 assert.ok(sql.prepare('SELECT total_changes() n').get().n>staleChanges,'a stale mirror is repaired');
 assert.equal(JSON.parse(sql.prepare("SELECT document_json FROM friendbook_cf_documents WHERE path='users/alice'").get().document_json).displayName,'Alice');
 sql.prepare("DELETE FROM friendbook_cf_documents WHERE path='users/alice'").run();
 assert.equal((await merge('alice',{displayName:'Alice'})).status,200);
 assert.ok(sql.prepare("SELECT 1 FROM friendbook_cf_documents WHERE path='users/alice'").get(),'a missing mirror is restored');
 race=true;const response=await merge('alice',{displayName:'Alice'});assert.equal(response.status,200);
 const saved=(await response.json()).data;
 assert.equal(saved.displayName,'Alice');assert.equal(saved.bio,'external','retry keeps the concurrent unrelated edit');
 assert.equal((await merge('old',{displayName:'Old'})).status,200);
 assert.ok(sql.prepare("SELECT 1 FROM friendbook_cf_firestore_documents WHERE path='users/old'").get(),'legacy-only profiles still promote');
 sql.close();
});

test('saving visible posts is actor-scoped and survives concurrent owner edits', async () => {
 const sql=new DatabaseSync(':memory:');sql.exec(resourcesSchema);sql.exec(appSchema);sql.exec(schema);sql.exec(socialGuardSchema);
 const f=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
 for(const uid of ['alice','bob','mallory']) f.run(`users/${uid}`,'users','users',uid,uid,JSON.stringify({uid}),1,1);
 const initial={authorId:'alice',privacy:'Public',content:'original',savedBy:[]};
 f.run('posts/p','posts','posts','p','alice',JSON.stringify(initial),1,1);
 let injected=false,noopRace=false,privacyRace=false;
 const db={prepare(q){const s=sql.prepare(q);return{bind(...args){return{run:async()=>s.run(...args),all:async()=>({results:s.all(...args)}),first:async()=>{
  if(noopRace && q.includes('AS current,') && q.includes('AS mirror')){
   noopRace=false;sql.prepare("UPDATE friendbook_cf_firestore_documents SET document_json=?,update_time=2 WHERE path='posts/p'").run(JSON.stringify({...initial,content:'edited'}));
  }
  return s.get(...args)||null;
 }};}};},async batch(stmts){
  if(!injected){injected=true;sql.prepare("UPDATE friendbook_cf_firestore_documents SET document_json=? WHERE path='posts/p'").run(JSON.stringify({...initial,content:'edited'}));}
  if(privacyRace){privacyRace=false;sql.prepare("UPDATE friendbook_cf_firestore_documents SET document_json=? WHERE path='posts/p2'").run(JSON.stringify({...initial,privacy:'Only me'}));}
  sql.exec('BEGIN');try{for(const s of stmts)await s.run();sql.exec('COMMIT');}catch(e){sql.exec('ROLLBACK');throw e;}
 }};
 const app=createWorker(async t=>t),env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test'};
 const patch=(uid,path,fields)=>app.fetch(new Request('https://example.test/v1/firestore/document?path='+encodeURIComponent(path),{method:'PATCH',headers:{authorization:'Bearer '+uid,'content-type':'application/json'},body:JSON.stringify({fields})}),env);
 const transform=(kind,who)=>({__fieldValue:kind,values:[who]});
 const saved=await patch('bob','posts/p',{savedBy:transform('arrayUnion','bob')});assert.equal(saved.status,200,await saved.clone().text());
 assert.equal((await saved.json()).data.content,'edited');
 const changes=()=>sql.prepare('SELECT total_changes() AS n').get().n, beforeRepeat=changes();
 assert.equal((await patch('bob','posts/p',{savedBy:transform('arrayUnion','bob')})).status,200);
 assert.equal(changes(),beforeRepeat,'an already-saved post needs no mirror or guard writes');
 assert.equal((await patch('alice','posts/p',{content:'edited'})).status,200);
 assert.equal(changes(),beforeRepeat,'an unchanged literal edit needs no writes');
 sql.prepare("UPDATE friendbook_cf_documents SET document_json=? WHERE path='posts/p'").run(JSON.stringify({...initial,id:'p',content:'edited'}));
 assert.equal((await patch('bob','posts/p',{savedBy:transform('arrayUnion','bob')})).status,200);
 assert.deepEqual(JSON.parse(sql.prepare("SELECT document_json FROM friendbook_cf_documents WHERE path='posts/p'").get().document_json).savedBy,['bob'],'stale mirror is repaired');
 noopRace=true;
 assert.equal((await patch('bob','posts/p',{savedBy:transform('arrayUnion','bob')})).status,200);
 assert.deepEqual(JSON.parse(sql.prepare("SELECT document_json FROM friendbook_cf_firestore_documents WHERE path='posts/p'").get().document_json).savedBy,['bob'],'a racing removal is retried');
 assert.equal((await patch('mallory','posts/p',{savedBy:transform('arrayUnion','bob')})).status,403);
 assert.equal((await patch('mallory','posts/p',{savedBy:transform('arrayUnion','mallory'),content:'forged'})).status,403);
 const doc=JSON.parse(sql.prepare("SELECT document_json FROM friendbook_cf_firestore_documents WHERE path='posts/p'").get().document_json);
 assert.deepEqual(doc.savedBy,['bob']);assert.equal(doc.content,'edited');
 assert.equal((await patch('bob','posts/p',{savedBy:transform('arrayRemove','bob')})).status,200);
 const removed=JSON.parse(sql.prepare("SELECT document_json FROM friendbook_cf_firestore_documents WHERE path='posts/p'").get().document_json);assert.deepEqual(removed.savedBy,[]);
 sql.prepare("UPDATE friendbook_cf_firestore_documents SET document_json=? WHERE path='posts/p'").run(JSON.stringify({...initial,privacy:'Only me',savedBy:['bob']}));
 assert.equal((await patch('bob','posts/p',{savedBy:transform('arrayUnion','bob')})).status,403,'an identical save still checks current privacy');
 f.run('posts/p2','posts','posts','p2','alice',JSON.stringify(initial),1,1);
 privacyRace=true;
 assert.equal((await patch('bob','posts/p2',{savedBy:transform('arrayUnion','bob')})).status,403,'privacy change racing the batch is reauthorized');
 assert.deepEqual(JSON.parse(sql.prepare("SELECT document_json FROM friendbook_cf_firestore_documents WHERE path='posts/p2'").get().document_json).savedBy,[]);
 sql.exec("CREATE TRIGGER fail_post_mirror BEFORE INSERT ON friendbook_cf_documents WHEN NEW.path='posts/p2' BEGIN SELECT RAISE(ABORT,'mirror failure'); END");
 assert.notEqual((await patch('alice','posts/p2',{content:'failure'})).status,200);
 assert.equal(JSON.parse(sql.prepare("SELECT document_json FROM friendbook_cf_firestore_documents WHERE path='posts/p2'").get().document_json).content,'original');
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
 let race=true,blockRace=false,friendRace=false,viewerRace=false,preferredAccounts=[];
 const db={prepare(q){const s=sql.prepare(q);return{bind(...args){return{run:async()=>s.run(...args),all:async()=>({results:s.all(...args)}),first:async()=>{if(q.includes('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path = ?1') && String(args[0]).startsWith('users/'))preferredAccounts.push(args[0]);return s.get(...args)||null;}};}};},async batch(stmts){
  if(race){race=false;const row=JSON.parse(sql.prepare("SELECT document_json FROM friendbook_cf_firestore_documents WHERE path='stories/s1'").get().document_json);row.viewedBy=['carol'];sql.prepare("UPDATE friendbook_cf_firestore_documents SET document_json=? WHERE path='stories/s1'").run(JSON.stringify(row));}
  if(blockRace){blockRace=false;sql.prepare("UPDATE friendbook_cf_firestore_documents SET document_json=? WHERE path='users/alice'").run(JSON.stringify({uid:'alice',blocked:['bob']}));}
  if(friendRace){friendRace=false;sql.prepare("DELETE FROM friendbook_cf_trusted_friendships WHERE uid_low='alice' AND uid_high='bob'").run();}
  if(viewerRace){viewerRace=false;sql.prepare("UPDATE friendbook_cf_firestore_documents SET document_json=? WHERE path='users/bob'").run(JSON.stringify({uid:'bob',banned:true}));}
  sql.exec('BEGIN');try{for(const s of stmts)await s.run();sql.exec('COMMIT');}catch(e){sql.exec('ROLLBACK');throw e;}
 }};
 const app=createWorker(async t=>t),env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test'};
 const call=(who,body)=>app.fetch(new Request('https://example.test/v1/firestore/story-engagement',{method:'POST',headers:{authorization:'Bearer '+who,'content-type':'application/json'},body:JSON.stringify(body)}),env);
 assert.equal((await call('bob',{id:'s1',op:'view'})).status,200);
 let story=JSON.parse(sql.prepare("SELECT document_json FROM friendbook_cf_firestore_documents WHERE path='stories/s1'").get().document_json);
 assert.deepEqual(story.viewedBy,['carol','bob']);
 put('stories','s2',{authorId:'alice',privacy:'Public',expiresAt:Date.now()+60000,viewedBy:[],reactions:{}});
 preferredAccounts=[];
 assert.equal((await call('bob',{id:'s2',op:'view'})).status,200);
 assert.ok(!preferredAccounts.includes('users/alice'),'a changing engagement reuses the guarded author snapshot');
 preferredAccounts=[];
 assert.equal((await call('bob',{id:'s2',op:'view'})).status,200);
 assert.ok(preferredAccounts.includes('users/alice'),'a no-op still reads current authorization without committing guards');
 sql.exec("UPDATE friendbook_cf_firestore_documents SET document_json=json_set(document_json,'$.expiresAt',1) WHERE path='stories/s2'");
 preferredAccounts=[];
 assert.equal((await call('bob',{id:'s2',op:'react',emoji:'x'})).status,403);
 assert.ok(preferredAccounts.includes('users/alice'),'an expired story uses fresh authorization before returning its error');
 const changes=()=>sql.prepare('SELECT total_changes() AS n').get().n;
 let before=changes();
 assert.equal((await call('bob',{id:'s1',op:'view'})).status,200);
 assert.equal(changes(),before,'repeat view must not rewrite story or guards');
 assert.equal((await call('bob',{id:'s1',op:'react',emoji:'❤️'})).status,200);
 before=changes();
 assert.equal((await call('bob',{id:'s1',op:'react',emoji:'❤️'})).status,200);
 assert.equal(changes(),before,'repeat reaction must not rewrite story or guards');
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
 sql.prepare("UPDATE friendbook_cf_firestore_documents SET document_json=? WHERE path='users/alice'").run(JSON.stringify({uid:'alice'}));
 friendRace=true;
 assert.equal((await call('bob',{id:'s1',op:'react',emoji:'x'})).status,403);
 story=JSON.parse(sql.prepare("SELECT document_json FROM friendbook_cf_firestore_documents WHERE path='stories/s1'").get().document_json);
 assert.equal(story.reactions.bob,'❤️','friendship removal racing the batch prevents the write');
 put('stories','s3',{authorId:'alice',privacy:'Public',expiresAt:Date.now()+60000,viewedBy:[],reactions:{}});
 viewerRace=true;assert.equal((await call('bob',{id:'s3',op:'view'})).status,403);
 assert.deepEqual(JSON.parse(sql.prepare("SELECT document_json FROM friendbook_cf_firestore_documents WHERE path='stories/s3'").get().document_json).viewedBy,[],'a racing viewer ban must abort');
 sql.prepare("UPDATE friendbook_cf_firestore_documents SET document_json=? WHERE path='users/bob'").run(JSON.stringify({uid:'bob'}));
 sql.exec("CREATE TRIGGER fail_story_mirror BEFORE INSERT ON friendbook_cf_documents WHEN NEW.path='stories/s3' BEGIN SELECT RAISE(ABORT,'injected failure'); END");
 assert.notEqual((await call('bob',{id:'s3',op:'view'})).status,200);
 assert.deepEqual(JSON.parse(sql.prepare("SELECT document_json FROM friendbook_cf_firestore_documents WHERE path='stories/s3'").get().document_json).viewedBy,[],'mirror failure must roll back the view');
 sql.exec('DROP TRIGGER fail_story_mirror');
 assert.equal((await call('bob',{id:'s3',op:'view'})).status,200);
 const legacy={authorId:'alice',privacy:'Public',expiresAt:Date.now()+60000,viewedBy:[],reactions:{},id:'old'};
 sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)').run('stories/old','stories','old','alice',JSON.stringify(legacy),1);
 assert.equal((await call('bob',{id:'old',op:'view'})).status,200);
 assert.deepEqual(JSON.parse(sql.prepare("SELECT document_json FROM friendbook_cf_firestore_documents WHERE path='stories/old'").get().document_json).viewedBy,['bob']);
 sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)').run('users/dana','users','dana','dana',JSON.stringify({uid:'dana',id:'dana'}),1);
 put('stories','s4',{authorId:'alice',privacy:'Public',expiresAt:Date.now()+60000,viewedBy:[],reactions:{}});
 assert.equal((await call('dana',{id:'s4',op:'view'})).status,200);
 assert.deepEqual(JSON.parse(sql.prepare("SELECT document_json FROM friendbook_cf_firestore_documents WHERE path='stories/s4'").get().document_json).viewedBy,['dana']);
 assert.equal((await call('alice',{id:'s4',op:'view'})).status,403,'authors cannot engage with their own story');
 put('stories','missing-author',{authorId:'absent',privacy:'Public',expiresAt:Date.now()+60000});
 assert.equal((await call('bob',{id:'missing-author',op:'view'})).status,403);
 put('stories','invalid-state',{authorId:'alice',privacy:'Public',expiresAt:Date.now()+60000,viewedBy:'invalid'});
 assert.equal((await call('bob',{id:'invalid-state',op:'view'})).status,409);
 sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)').run('users/legacy-author','users','legacy-author','legacy-author',JSON.stringify({uid:'legacy-author',id:'legacy-author'}),1);
 put('stories','legacy-author-story',{authorId:'legacy-author',privacy:'Public',expiresAt:Date.now()+60000,viewedBy:[],reactions:{}});
 assert.equal((await call('bob',{id:'legacy-author-story',op:'view'})).status,200);
 assert.deepEqual(JSON.parse(sql.prepare("SELECT document_json FROM friendbook_cf_firestore_documents WHERE path='stories/legacy-author-story'").get().document_json).viewedBy,['bob']);
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

test('direct chat reads fail closed with a JSON 503 when room snapshot lookup fails', async () => {
  const app = createWorker(async () => 'alice');
  const env = {FIREBASE_PROJECT_ID:'friendbook-test', DB:{prepare() {
    return {bind(path) { return {first:async()=>{
      if (path==='users/alice') return null;
      assert.equal(path,'chats/room');
      throw new Error('private storage diagnostic');
    }}; }};
  }}};
  const response=await app.fetch(new Request('https://example.test/v1/firestore/document?path=chats/room',{
    headers:{authorization:'Bearer token'},
  }),env);
  assert.equal(response.status,503);
  assert.deepEqual(await response.json(),{error:'Read unavailable'});
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
  sql.exec(readFileSync(new URL('../migrations/0060_live_status_indexes.sql',import.meta.url),'utf8'));
  sql.exec(readFileSync(new URL('../migrations/0070_live_chat_page.sql',import.meta.url),'utf8'));
  let pageRows=0;
  const plans=[];let roomReads=0,liveShadowArgs=[],raceLiveSnapshot=false;
  const insert=sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)');
  for (const uid of ['alice','bob']) insert.run(`users/${uid}`,'users',uid,uid,JSON.stringify({displayName:uid}),1);
  const db={prepare(query){const stmt=sql.prepare(query);return{bind(...args){return{run:async()=>stmt.run(...args),all:async()=>{if(query.includes('INDEXED BY idx_cf_') && query.includes('lives_status')) plans.push(...sql.prepare('EXPLAIN QUERY PLAN '+query).all(...args).map(row=>row.detail));if(query.includes('SELECT path,parent_path FROM friendbook_cf_firestore_documents WHERE path IN ('))liveShadowArgs.push(...args);const results=stmt.all(...args);if(args[0]==='lives/room/chat')pageRows+=results.length;return {results};},first:async()=>{if(args[0]==='lives/room')roomReads++;if(raceLiveSnapshot && query.includes('SELECT 1 AS current FROM friendbook_cf_firestore_documents')){raceLiveSnapshot=false;sql.prepare("UPDATE friendbook_cf_firestore_documents SET document_json=json_set(document_json,'$.status','ended') WHERE path='lives/room'").run();}return stmt.get(...args)||null;}};}};},async batch(stmts){sql.exec('BEGIN');try{for(const stmt of stmts)await stmt.run();sql.exec('COMMIT');}catch(e){sql.exec('ROLLBACK');throw e;}}};
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
  const heartbeat={fields:{lastHeartbeatMs:Date.now(),viewerIds:['bob'],peakViewers:1}};
  assert.equal((await call('bob',doc,'PATCH',heartbeat)).status,403);
  const beforeHeartbeat=sql.prepare('SELECT total_changes() n').get().n;
  assert.equal((await call('alice',doc,'PATCH',heartbeat)).status,200);
  assert.equal(sql.prepare('SELECT total_changes() n').get().n-beforeHeartbeat,1,'live telemetry needs only the canonical write');
  assert.equal((await (await call('bob',doc)).json()).data.peakViewers,1);
  const quietBefore=sql.prepare('SELECT total_changes() n').get().n;
  for(let i=0;i<12;i++) {
    const response=await call('alice',doc,'PATCH',{fields:{...heartbeat.fields,lastHeartbeatMs:Date.now()}});
    assert.equal(response.status,200);
    assert.equal((await response.json()).data.lastHeartbeatMs,heartbeat.fields.lastHeartbeatMs,'acknowledgement reflects persisted state');
  }
  assert.equal(sql.prepare('SELECT total_changes() n').get().n-quietBefore,0,'unchanged fresh live telemetry does not rewrite the room');
  let changed=sql.prepare('SELECT total_changes() n').get().n;
  assert.equal((await call('alice',doc,'PATCH',{fields:{lastHeartbeatMs:Date.now(),viewerIds:['bob','alice'],peakViewers:2}})).status,200);
  assert.equal(sql.prepare('SELECT total_changes() n').get().n-changed,1,'audience changes persist immediately');
  assert.deepEqual((await (await call('bob',doc)).json()).data.viewerIds,['bob','alice']);
  changed=sql.prepare('SELECT total_changes() n').get().n;
  assert.equal((await call('alice',doc,'PATCH',{fields:{lastHeartbeatMs:Date.now(),viewerIds:['bob'],peakViewers:2}})).status,200);
  assert.equal(sql.prepare('SELECT total_changes() n').get().n-changed,1,'viewer changes persist even when peak is unchanged');
  changed=sql.prepare('SELECT total_changes() n').get().n;
  assert.equal((await call('alice',doc,'PATCH',{fields:{lastHeartbeatMs:Date.now(),viewerIds:['bob','alice'],peakViewers:2}})).status,200);
  assert.equal(sql.prepare('SELECT total_changes() n').get().n-changed,1);
  const setHeartbeat=time=>sql.prepare("UPDATE friendbook_cf_firestore_documents SET document_json=json_set(document_json,'$.lastHeartbeatMs',?) WHERE path='lives/room'").run(time);
  setHeartbeat(Date.now()-25_000); changed=sql.prepare('SELECT total_changes() n').get().n;
  assert.equal((await call('alice',doc,'PATCH',{fields:{lastHeartbeatMs:Date.now(),viewerIds:['bob','alice'],peakViewers:2}})).status,200);
  assert.equal(sql.prepare('SELECT total_changes() n').get().n-changed,1,'a lease older than the coalescing window is renewed');
  setHeartbeat(Date.now()+60_000); changed=sql.prepare('SELECT total_changes() n').get().n;
  assert.equal((await call('alice',doc,'PATCH',{fields:{lastHeartbeatMs:Date.now(),viewerIds:['bob','alice'],peakViewers:2}})).status,200);
  assert.equal(sql.prepare('SELECT total_changes() n').get().n-changed,1,'future stored clock does not suppress a correction');
  changed=sql.prepare('SELECT total_changes() n').get().n;
  assert.equal((await call('alice',doc,'PATCH',{fields:{lastHeartbeatMs:Date.now()+60_000,viewerIds:['bob','alice'],peakViewers:2}})).status,200);
  assert.equal(sql.prepare('SELECT total_changes() n').get().n-changed,1,'future client clock is not coalesced');
  setHeartbeat(Date.now()-1_000); changed=sql.prepare('SELECT total_changes() n').get().n;
  assert.equal((await call('alice',doc,'PATCH',{fields:{lastHeartbeatMs:Date.now()-2_000,viewerIds:['bob','alice'],peakViewers:2}})).status,200);
  assert.equal(sql.prepare('SELECT total_changes() n').get().n-changed,1,'backwards timestamps do not suppress a write');
  setHeartbeat(Date.now()-1_000); raceLiveSnapshot=true;
  assert.equal((await call('alice',doc,'PATCH',{fields:{lastHeartbeatMs:Date.now(),viewerIds:['bob','alice'],peakViewers:2}})).status,503,'an end racing a quiet heartbeat cannot be acknowledged against stale state');
  assert.equal(sql.prepare("SELECT json_extract(document_json,'$.status') AS status FROM friendbook_cf_firestore_documents WHERE path='lives/room'").get().status,'ended');
  sql.prepare("UPDATE friendbook_cf_firestore_documents SET document_json=json_set(document_json,'$.status','live') WHERE path='lives/room'").run();
  for(let i=0;i<200;i++) insert.run(`lives/old${i}`,'lives',`old${i}`,'alice',JSON.stringify({hostId:'alice',privacy:'public',status:'ended'}),1);
  insert.run('lives/legacyLive','lives','legacyLive','alice',JSON.stringify({fields:{hostId:{stringValue:'alice'},privacy:{stringValue:'public'},status:{stringValue:'live'}}}),1);
  insert.run('lives/shadow','lives','shadow','alice',JSON.stringify({hostId:'alice',privacy:'public',status:'live'}),1);
  sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)').run('lives/shadow','lives','lives','shadow','alice',JSON.stringify({hostId:'alice',privacy:'public',status:'ended'}),1,1);
  const discover=async filter=>{
    const r=await call('bob','/v1/firestore/query','POST',{parent:'lives',limit:25,filters:[{field:'status',...filter}]});
    assert.equal(r.status,200);return (await r.json()).documents.map(row=>row.id);
  };
  liveShadowArgs=[];
  const liveEqual=await discover({op:'==',value:'live'});
  assert.ok(!liveShadowArgs.includes('lives/room'),'a live canonical row needs no shadow lookup');
  assert.ok(liveShadowArgs.includes('lives/shadow'),'an ended canonical row must shadow a stale live mirror');
  assert.ok(liveShadowArgs.includes('lives/legacyLive'),'legacy-only rooms still get a canonical check');
  plans.length=0;
  assert.deepEqual(await discover({op:'in',value:['live']}),liveEqual);
  assert.match(plans.join('\n'),/SEARCH.*idx_cf_lives_status/);
  assert.deepEqual(await discover({op:'==',value:'live'}),['legacyLive','room']);
  assert.match(plans.join('\n'),/SEARCH.*idx_cf_lives_status/);
  assert.match(plans.join('\n'),/SEARCH.*idx_cf_legacy_lives_status/);
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
  const chatQuery='/v1/firestore/query';roomReads=0;
  assert.equal((await (await call('bob',chatQuery,'POST',{parent:chat})).json()).documents.length,1);
  assert.equal(roomReads,1,'live chat only authorizes its room once');
  roomReads=0;
  assert.equal((await (await call('bob',`/v1/firestore/count?parent=${chat}`)).json()).count,1);
  assert.equal(roomReads,1);
  const addLive=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
  for(let j=0;j<1200;j++) {
    const id=`sample${String(j).padStart(4,'0')}`;
    addLive.run(`${chat}/${id}`,chat,'chat',id,'alice',JSON.stringify({createdAtMs:Math.floor(j/2)}),j,j);
  }
  const page={parent:chat,orderBy:[{field:'createdAtMs',direction:'ASCENDING'}],limit:100,limitToLast:true};
  const ids=async()=>{
    const result=await call('bob',chatQuery,'POST',page);
    assert.equal(result.status,200);
    return (await result.json()).documents.map(row=>row.id);
  };
  pageRows=0;
  const boundedIds=await ids();
  assert.equal(pageRows,100);
  sql.exec('DROP INDEX idx_cf_live_chat_page');
  pageRows=0;
  assert.deepEqual(await ids(),boundedIds);
  assert.equal(pageRows,1201);
  sql.prepare("DELETE FROM friendbook_cf_firestore_documents WHERE parent_path=? AND doc_id LIKE 'sample%'").run(chat);
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
  sql.exec(readFileSync(new URL('../migrations/0052_broadcast_active_jobs.sql',import.meta.url),'utf8'));
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
  sql.prepare('INSERT INTO friendbook_cf_broadcast_push VALUES (?,?,?,?,?)').run('expired','alice',now-86400001,now,0);
  sql.exec(`CREATE TRIGGER forbid_expired_lease BEFORE UPDATE ON friendbook_cf_broadcast_push
    WHEN OLD.notification_id='expired' BEGIN SELECT RAISE(ABORT,'expired rows must never be re-leased'); END`);
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
  sql.exec(readFileSync(new URL('../migrations/0065_skip_unchanged_post_search.sql',import.meta.url),'utf8'));
  const changes=()=>sql.prepare('SELECT total_changes() AS n').get().n;
  const before=changes();
  sql.exec(`UPDATE friendbook_cf_documents SET document_json=json_set(document_json,'$.likesCount',1) WHERE path='posts/visible';
    UPDATE friendbook_cf_firestore_documents SET document_json=json_set(document_json,'$.likesCount',1) WHERE path='posts/shadow'`);
  assert.equal(changes()-before,2,'metadata-only updates must not write into FTS');
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
  sql.exec(`UPDATE friendbook_cf_firestore_documents SET document_json=json_set(document_json,'$.content','@Carol') WHERE path='posts/shadow'`);
  assert.deepEqual((await search('@carol')).sort(),['posts/shadow','posts/visible']);
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
  sql.exec(readFileSync(new URL('../migrations/0061_legacy_people_cursor_prefix.sql', import.meta.url), 'utf8'));
  const plans=[];
  let scans=0;
  const put=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
  const legacy=sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)');
  put.run('users/actor','users','users','actor','actor','{"displayName":"Viewer"}',1,1);
  put.run('users/changed','users','users','changed','changed','{"displayName":"New name"}',1,1);
  put.run('users/match','users','users','match','match','{"displayName":"Amir"}',1,1);
  legacy.run('users/changed','users','changed','changed','{"displayName":"Amir old"}',1);
  legacy.run('users/legacy','users','legacy','legacy','{"fields":{"displayName":{"stringValue":"Amina"}}}',1);
  const db={prepare(q){if(q.includes('quota_root_users_name')) scans++;const s=sql.prepare(q);return {bind(...a){return {first:async()=>s.get(...a)||null,all:async()=>{if(q.includes('idx_cf_legacy_people_cursor_prefix')) plans.push(...sql.prepare('EXPLAIN QUERY PLAN '+q).all(...a).map(row=>row.detail));return {results:s.all(...a)};}};}};}};
  const response=await createWorker(async token=>token).fetch(new Request('https://example.test/v1/firestore/query',{
    method:'POST',headers:{authorization:'Bearer actor','content-type':'application/json'},
    body:JSON.stringify({parent:'users',limit:12,filters:[{field:'displayName',op:'>=',value:'Am'},{field:'displayName',op:'<=',value:'Am\uf8ff'}]}),
  }),{DB:db,FIREBASE_PROJECT_ID:'test'});
  assert.equal(response.status,200);
  assert.deepEqual((await response.json()).documents.map(doc=>doc.id).sort(),['legacy','match']);
  const plan=sql.prepare("EXPLAIN QUERY PLAN SELECT * FROM friendbook_cf_firestore_documents INDEXED BY idx_cf_users_display_name WHERE parent_path='users' AND json_extract(document_json,'$.displayName')>=? AND json_extract(document_json,'$.displayName')<?").all('Am','An');
  assert.ok(plan.some(row=>row.detail.includes('idx_cf_users_display_name')));
  legacy.run('users/primitive','users','primitive','primitive','{"fields":{"displayName":"Amber"}}',1);
  legacy.run('users/coerced','users','coerced','coerced','{"fields":{"displayName":{"stringValue":["Amanda"]}}}',1);
  for(let i=0;i<250;i++) legacy.run(`users/unrelated${i}`,'users',`unrelated${i}`,'',JSON.stringify({displayName:'No match'}),1);
  const mentions=async extra=>{
    const r=await createWorker(async token=>token).fetch(new Request('https://example.test/v1/firestore/query',{
      method:'POST',headers:{authorization:'Bearer actor','content-type':'application/json'},body:JSON.stringify({
        parent:'users',limit:10,orderBy:[{field:'displayName',direction:'ASCENDING'}],startAt:['Am'],endAt:['Am\uf8ff'],...extra,
      }),
    }),{DB:db,FIREBASE_PROJECT_ID:'test'});
    assert.equal(r.status,200);return (await r.json()).documents.map(doc=>doc.id);
  };
  const wanted=['coerced','primitive','legacy','match'];
  assert.deepEqual(await mentions({}),wanted);
  assert.deepEqual(await mentions({startAfter:['Amber','primitive']}),['legacy','match']);
  assert.deepEqual(await mentions({endBefore:['Amir','match']}),wanted.slice(0,-1));
  const filtered=await createWorker(async token=>token).fetch(new Request('https://example.test/v1/firestore/query',{
    method:'POST',headers:{authorization:'Bearer actor','content-type':'application/json'},body:JSON.stringify({
      parent:'users',limit:10,filters:[{field:'displayName',op:'>=',value:'Am'},{field:'displayName',op:'<=',value:'Am\uf8ff'}],
    }),
  }),{DB:db,FIREBASE_PROJECT_ID:'test'});
  assert.equal(filtered.status,200);assert.deepEqual((await filtered.json()).documents.map(doc=>doc.id).sort(),wanted.slice().sort());
  assert.equal(scans,0,'prefix pages must not scan the users collection');
  assert.match(plans.join('\n'),/SEARCH a USING INDEX idx_cf_legacy_people_cursor_prefix/);

});

test('Unicode mention-prefix index is a superset of the UTF-16 cursor and preserves visibility', async () => {
  const sql=new DatabaseSync(':memory:');
  for(const ddl of [resourcesSchema,appSchema,schema,readFileSync(new URL('../migrations/0053_people_name_prefix.sql',import.meta.url),'utf8'),readFileSync(new URL('../migrations/0061_legacy_people_cursor_prefix.sql',import.meta.url),'utf8')])sql.exec(ddl);
  const f=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
  const a=sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)');
  f.run('users/actor','users','users','actor','actor','{"displayName":"Viewer"}',1,1);
  for(let i=0;i<1200;i++)f.run(`users/filler${i}`,'users','users',`filler${i}`,'',JSON.stringify({displayName:`Other ${i}`}),1,1);
  const names=['آمنہ','آمر','آ😀','آ\ue000','आम','अक्षय','你好','你好😀','école','e\u0301clair','😀','😀 suffix','😀\uf900','\ud7fftest','\ud7ff😀','\u{10ffff}only','Z\u{10ffff}tail'];
  names.forEach((name,i)=>{
    const id=`n${i}`;
    f.run(`users/${id}`,'users','users',id,'',JSON.stringify({displayName:name,email:'private'}),1,1);
  });
  // Both JSON-envelope variants and a coerced stringValue share the same evaluator.
  a.run('users/envelope','users','envelope','',JSON.stringify({fields:{displayName:{stringValue:'آئینہ'}}}),1);
  a.run('users/coerced','users','coerced','',JSON.stringify({fields:{displayName:{stringValue:['آبت']}}}),1);
  a.run('users/shadow','users','shadow','',JSON.stringify({displayName:'آئندہ'}),1);
  f.run('users/shadow','users','users','shadow','',JSON.stringify({displayName:'Moved'}),1,1);
  f.run('users/hidden','users','users','hidden','',JSON.stringify({displayName:'آباد',banned:true}),1,1);
  let force=false,scans=0,indexed=0,plans=[];
  const db={prepare(q){
    if(force && q.includes('INDEXED BY idx_cf_users_display_name'))throw Error('no such index: idx_cf_users_display_name');
    if(q.includes('quota_root_users_name'))scans++;
    const stmt=sql.prepare(q);return {bind(...args){return {first:async()=>stmt.get(...args)||null,all:async()=>{
      if(q.includes('INDEXED BY idx_cf_users_display_name') || q.includes('INDEXED BY idx_cf_legacy_people_cursor_prefix')) {
        indexed++;plans.push(...sql.prepare('EXPLAIN QUERY PLAN '+q).all(...args).map(row=>row.detail));
      }
      return {results:stmt.all(...args)};
    }};}};
  }};
  const worker=createWorker(async token=>token),env={DB:db,FIREBASE_PROJECT_ID:'test'};
  const query=async(term,extra={})=>{
    const response=await worker.fetch(new Request('https://example.test/v1/firestore/query',{method:'POST',headers:{authorization:'Bearer actor','content-type':'application/json'},body:JSON.stringify({parent:'users',limit:10,orderBy:[{field:'displayName',direction:'ASCENDING'}],startAt:[term],endAt:[term+'\uf8ff'],...extra})}),env);
    assert.equal(response.status,200,await response.clone().text());return (await response.json()).documents;
  };
  for(const term of ['آ','आ','你好','é','e\u0301','😀','\ud7ff','Z\u{10ffff}']) {
    force=false;scans=0;indexed=0;const actual=await query(term),fastScans=scans;
    force=true;const expected=await query(term);force=false;
    assert.deepEqual(actual,expected,`UTF-16 boundary for ${term}`);
    assert.equal(fastScans,0,`indexed prefix ${term}`);assert.ok(indexed>=2);
    assert.ok(actual.every(doc=>doc.data.email===undefined && doc.id!=='hidden' && doc.id!=='shadow'));
  }
  const filtered=async(term,extra={})=>{
    const response=await worker.fetch(new Request('https://example.test/v1/firestore/query',{method:'POST',headers:{authorization:'Bearer actor','content-type':'application/json'},body:JSON.stringify({parent:'users',limit:10,filters:[{field:'displayName',op:'>=',value:term},{field:'displayName',op:'<=',value:term+'\uf8ff'}],...extra})}),env);
    assert.equal(response.status,200,await response.clone().text());return (await response.json()).documents;
  };
  for(const term of ['آ','आ','你好','é','e\u0301','😀','\ud7ff','Z\u{10ffff}']) {
    force=false;scans=0;indexed=0;const actual=await filtered(term),fastScans=scans;
    force=true;const expected=await filtered(term);force=false;
    assert.deepEqual(actual,expected,`filtered UTF-16 boundary for ${term}`);
    assert.equal(fastScans,0);assert.ok(indexed>=2);
    assert.ok(actual.every(doc=>doc.data.email===undefined && doc.id!=='hidden' && doc.id!=='shadow'));
  }
  // The UTF-16 U+F8FF bound excludes BMP U+F900, but includes a supplementary suffix.
  const arabic=await query('آ');assert.ok(arabic.some(doc=>doc.data.displayName==='آ😀'));
  const arabicFiltered=await filtered('آ');assert.ok(arabicFiltered.some(doc=>doc.id==='envelope'));assert.ok(arabicFiltered.some(doc=>doc.id==='coerced'));
  const emoji=await query('😀');assert.ok(!emoji.some(doc=>doc.data.displayName==='😀\uf900'));
  const constrained={startAfter:['آبت','coerced'],endBefore:['آ😀','n2'],limit:2};
  force=false;const page=await query('آ',constrained);force=true;assert.deepEqual(page,await query('آ',constrained));
  force=false;scans=0;await query('\u{10ffff}');assert.ok(scans>0,'no successor uses compatibility path');
  force=false;scans=0;await query('\ud83d');assert.ok(scans>0,'unpaired cursor surrogate uses compatibility path');
  assert.match(plans.join('\n'),/SEARCH .*idx_cf_users_display_name/);
  assert.match(plans.join('\n'),/SEARCH .*idx_cf_legacy_people_cursor_prefix/);
  sql.close();
});

test('empty mention prefix uses bounded heads only when exact results are provable', async () => {
 const sql=new DatabaseSync(':memory:');
 for(const ddl of [resourcesSchema,appSchema,schema,readFileSync(new URL('../migrations/0053_people_name_prefix.sql',import.meta.url),'utf8'),readFileSync(new URL('../migrations/0061_legacy_people_cursor_prefix.sql',import.meta.url),'utf8')])sql.exec(ddl);
 const f=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
 const a=sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)');
 for(let i=0;i<1200;i++){
   const id=`p${String(i).padStart(4,'0')}`,data=JSON.stringify({displayName:`Name ${String(i).padStart(4,'0')}`,email:'private@example.invalid'});
   f.run(`users/${id}`,'users','users',id,id,data,1,1);a.run(`users/${id}`,'users',id,id,data,1);
 }
 f.run('users/actor','users','users','actor','actor','{"displayName":"Viewer"}',1,1);
 let fallback=false,scans=0,heads=0;const plans=[];
 const db={prepare(q){
   if(fallback && q.includes('INDEXED BY idx_cf_users_display_name'))throw Error('no such index: idx_cf_users_display_name');
   if(q.includes('quota_root_users_name'))scans++;
   const stmt=sql.prepare(q);
   return {bind(...args){return {first:async()=>stmt.get(...args)||null,all:async()=>{
     const rows=stmt.all(...args);
     if(q.includes('INDEXED BY idx_cf_users_display_name') || q.includes('INDEXED BY idx_cf_legacy_people_cursor_prefix')){
       heads+=rows.length;plans.push(...sql.prepare('EXPLAIN QUERY PLAN '+q).all(...args).map(row=>row.detail));
     }
     return {results:rows};
   }};}};
 }};
 const worker=createWorker(async token=>token),env={DB:db,FIREBASE_PROJECT_ID:'test'};
 const get=async extra=>{
   const response=await worker.fetch(new Request('https://example.test/v1/firestore/query',{method:'POST',
     headers:{authorization:'Bearer actor','content-type':'application/json'},body:JSON.stringify({
       parent:'users',limit:10,orderBy:[{field:'displayName',direction:'ASCENDING'}],startAt:[''],endAt:['\uf8ff'],...extra,
     })}),env);
   assert.equal(response.status,200,await response.clone().text());return (await response.json()).documents;
 };
 async function compare(extra={}){
   scans=0;heads=0;fallback=false;const actual=await get(extra), fastScans=scans, fastHeads=heads;
   fallback=true;const expected=await get(extra);fallback=false;
   assert.deepEqual(actual,expected);return {actual,fastScans,fastHeads};
 }
 let result=await compare();
 assert.equal(result.fastScans,0);assert.ok(result.fastHeads<=20,'only bounded canonical and legacy heads were returned');
 assert.equal(result.actual.length,10);assert.ok(result.actual.every(doc=>doc.data.email===undefined),'private profile fields stay hidden');
 assert.match(plans.join('\n'),/SEARCH .*idx_cf_users_display_name/);
 assert.match(plans.join('\n'),/SEARCH .*idx_cf_legacy_people_cursor_prefix/);
 result=await compare({startAt:['Name'],endAt:['Name\uf8ff']});
 assert.equal(result.fastScans,0);assert.ok(result.fastHeads<=20,'ASCII prefix stops at bounded indexed heads');
 assert.equal(result.actual.length,10);
 // A stale early legacy name must not reappear when canonical has moved.
 sql.prepare("UPDATE friendbook_cf_firestore_documents SET document_json=? WHERE path='users/p0000'").run('{"displayName":"Zulu"}');
 result=await compare();assert.equal(result.fastScans,0);assert.ok(!result.actual.some(doc=>doc.id==='p0000'));
 // Coerced Firestore stringValue encodings remain sorted with ordinary names.
 a.run('users/odd','users','odd','odd',JSON.stringify({fields:{displayName:{stringValue:['!','A']}}}),1);
 result=await compare();assert.equal(result.fastScans,0);assert.equal(result.actual[0].id,'odd');
 // An oversized unusual-value bucket must not silently omit a competing name.
 for(let i=0;i<65;i++)a.run(`users/strange${i}`,'users',`strange${i}`,`strange${i}`,JSON.stringify({fields:{displayName:{stringValue:[`Odd${i}`]}}}),1);
 result=await compare();assert.ok(result.fastScans>0);assert.equal(result.actual.length,10);
 sql.prepare("DELETE FROM friendbook_cf_documents WHERE doc_id LIKE 'strange%'").run();
 // Complex Unicode ordering must use the complete compatibility evaluator.
 f.run('users/unicode','users','users','unicode','unicode',JSON.stringify({displayName:'!\ud83d\ude00'}),1,1);
 result=await compare();assert.ok(result.fastScans>0);assert.equal(result.actual[1].id,'unicode');
 sql.prepare("DELETE FROM friendbook_cf_firestore_documents WHERE path='users/unicode'").run();
 // Too many hidden names in the heads cannot shrink the requested page.
 for(let i=1;i<12;i++)sql.prepare('UPDATE friendbook_cf_firestore_documents SET document_json=? WHERE path=?').run(JSON.stringify({displayName:`Name ${String(i).padStart(4,'0')}`,banned:true}),`users/p${String(i).padStart(4,'0')}`);
 result=await compare();assert.ok(result.fastScans>0);assert.equal(result.actual.length,10);
 // Additional pagination bounds deliberately stay on the established path.
 result=await compare({startAfter:['Name 0100','p0100']});assert.ok(result.fastScans>0);
 sql.close();
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
 a.run('notifications/shadow','notifications','shadow','sender',JSON.stringify({fields:{toUserId:{stringValue:'alice'},isRead:{booleanValue:false}}}),1);
 for(let i=1;i<=1000;i++){
  const id=`old${i}`,path=`notifications/${id}`;
  f.run(path,'notifications','notifications',id,'sender',data('alice',-i),1,1);
  a.run(path,'notifications',id,'sender',data('alice',-i),1);
 }
 let rows=0,probes=0,shadowChecks=0,shadowCandidates=0;const deltaPlans=[];
 sql.function('quota_probe',path=>{probes++;return path;});
 const db={prepare(q){const stmt=sql.prepare(q.includes("ORDER BY CASE WHEN json_type(a.document_json")?q.replace("f.path=a.path","f.path=quota_probe(a.path)"):q);return{bind(...args){return{all:async()=>{const results=stmt.all(...args);if(q.includes('SELECT path FROM friendbook_cf_firestore_documents WHERE path IN (')){shadowChecks++;shadowCandidates+=args.length;}if(q.includes(' ASC') && q.includes('>=?'))deltaPlans.push(...sql.prepare('EXPLAIN QUERY PLAN '+q).all(...args).map(row=>row.detail));rows+=results.length;return{results};},first:async()=>stmt.get(...args)||null};}};}};
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
 probes=0;shadowChecks=0;shadowCandidates=0;
 const mirrorOnly=(await query({startAfter:[{__type:'timestamp',millis:0},'n0']})).documents;
 assert.deepEqual(mirrorOnly.map(d=>d.id),Array.from({length:16},(_,i)=>i+1).filter(i=>i%3!==0).slice(0,10).map(i=>`old${i}`));
 assert.equal(shadowChecks,0,'a fully mirrored window needs no extra canonical lookups');
 assert.ok(probes<=10,`older copies probed: ${probes}`);
 rows=0;probes=0;
 const anchor=[{__type:'timestamp',millis:165},'n165'];
 const headDelta=(await query({endBefore:anchor,limitToLast:true})).documents;
 const expected=Array.from({length:34},(_,i)=>166+i).filter(i=>i%3!==0).slice(0,10).reverse().map(i=>`n${i}`);
 assert.deepEqual(headDelta.map(d=>d.id),expected,'large bursts deliver the closest newer page first');
 assert.equal(rows,20,'head refresh must not return old inbox history from SQLite');
 assert.match(deltaPlans.join('\n'),/SEARCH.*idx_cf_notif_unread.*<expr>>/);
 assert.match(deltaPlans.join('\n'),/SEARCH.*idx_cf_legacy_notif_unread.*<expr>>/);
 // Canonical copies outside the current window still take precedence, even
 // when read state, recipient or ordering differ from a stale legacy mirror.
 for(const [id,canonicalData] of [
   ['staleRead',data('alice',501)],
   ['staleOwner',data('bob',502)],
   ['staleOrder',data('alice',-5000)],
 ]) {
   const path='notifications/'+id;
   f.run(path,'notifications','notifications',id,'sender',canonicalData,1,1);
   a.run(path,'notifications',id,'sender',data('alice',503),1);
 }
 a.run('notifications/legacyOnly','notifications','legacyOnly','sender',data('alice',505),1);
 shadowChecks=0;shadowCandidates=0;
 const newest=(await query({})).documents.map(d=>d.id);
 assert.ok(newest.includes('legacyOnly'));
 for(const id of ['staleRead','staleOwner','staleOrder']) assert.ok(!newest.includes(id),`stale ${id} leaked`);
 assert.ok(shadowChecks>=1 && shadowCandidates>=3,'unknown mirrors must be checked against canonical paths');
 sql.close();
});

test('message preview and unread updates do not rewrite unchanged membership indexes',()=>{
  const sql=new DatabaseSync(':memory:');sql.exec(appSchema);sql.exec(schema);
  for(const migration of ['0040_chat_participant_lookup.sql','0058_skip_unchanged_chat_members.sql'])
    sql.exec(readFileSync(new URL(`../migrations/${migration}`,import.meta.url),'utf8'));
  for(const [table,column,insert] of [
    ['friendbook_cf_firestore_documents','parent_path',['chats/r','chats','chats','r','alice','{"participants":["alice","bob"]}',1,1]],
    ['friendbook_cf_documents','collection',['chats/r','chats','r','alice','{"participants":["alice","bob"]}',1]],
  ]){
    sql.prepare(`INSERT INTO ${table} VALUES (${insert.map(()=>'?').join(',')})`).run(...insert);
    const before=sql.prepare('SELECT total_changes() AS n').get().n;
    sql.exec(`UPDATE ${table} SET document_json=json_set(document_json,'$.lastMessage','hi','$.unreadCount.bob',1) WHERE ${column}='chats'`);
    assert.equal(sql.prepare('SELECT total_changes() AS n').get().n-before,1);
    sql.exec(`UPDATE ${table} SET document_json=json_set(document_json,'$.participants',json('["alice","cara"]')) WHERE ${column}='chats'`);
    const source=table.includes('firestore')?1:0;
    assert.deepEqual(sql.prepare('SELECT participant_uid FROM friendbook_cf_chat_participants WHERE source=? ORDER BY 1').all(source).map(r=>r.participant_uid),['alice','cara']);
  }
  sql.close();
});

test('default people picker uses both ordered indexes and fills its page after privacy and mirror checks', async () => {
  const sql=new DatabaseSync(':memory:');sql.exec(resourcesSchema);sql.exec(appSchema);sql.exec(schema);
  sql.exec(readFileSync(new URL('../migrations/0059_people_default_page_indexes.sql',import.meta.url),'utf8'));
  const main=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
  const mirror=sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)');
  const add=(id,data,canonical=true)=>canonical
    ? main.run(`users/${id}`,'users','users',id,id,JSON.stringify(data),1,1)
    : mirror.run(`users/${id}`,'users',id,id,JSON.stringify(data),1);
  add('viewer',{uid:'viewer'});
  add('aaa-hidden',{uid:'aaa-hidden',deactivated:true});
  for(let i=0;i<12;i++) {
    const id='blocked'+i;
    add(id,{uid:id,banned:true});add(id,{uid:id,displayName:'Stale public mirror'},false);
  }
  for(let i=0;i<500;i++) {
    const id='person'+String(i).padStart(3,'0');
    add(id,{uid:id,displayName:id,email:'private@example.test'},i%2===0);
  }
  let plan, candidates=0;
  const fetchSizes=[];
  const db={prepare(q){const stmt=sql.prepare(q);return{bind(...args){return{
    all:async()=>{const results=stmt.all(...args);if(q.includes('WITH candidates')){
      fetchSizes.push(results.length);candidates+=results.length;plan=sql.prepare('EXPLAIN QUERY PLAN '+q).all(...args).map(row=>row.detail).join('\n');
    }return {results};},first:async()=>stmt.get(...args)||null
  };}};}};
  const app=createWorker(async t=>t),env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test'};
  const query=async spec=>{
    const r=await app.fetch(new Request('https://example.test/v1/firestore/query',{method:'POST',headers:{authorization:'Bearer viewer','content-type':'application/json'},body:JSON.stringify({parent:'users',...spec})}),env);
    assert.equal(r.status,200);return (await r.json()).documents;
  };
  const reference=(await query({})).slice(0,10);
  assert.equal(reference[0].id,'person000');
  assert.deepEqual(await query({limit:10}),reference);
  assert.equal(fetchSizes[0],11,'first fetch is limited to the requested page plus the mirror boundary row');
  assert.doesNotMatch(JSON.stringify(reference),/private@example|Stale public mirror/);
  const selected=await query({filters:[{field:'__name__',op:'in',value:['person003','person002','blocked0','person003']}]});
  assert.deepEqual(selected.map(row=>row.id),['person002','person003']);
  assert.doesNotMatch(JSON.stringify(selected),/private@example|Stale public mirror/);
  assert.ok(candidates<60);
  assert.match(plan,/SEARCH friendbook_cf_firestore_documents USING INDEX idx_cf_people_default_page/);
  assert.match(plan,/SEARCH friendbook_cf_documents USING INDEX idx_cf_legacy_people_default_page/);
  assert.doesNotMatch(plan,/USE TEMP B-TREE/);
  sql.close();
});

test('mirrored people heads reuse canonical shadow checks without losing hidden or legacy profiles', async () => {
  const sql=new DatabaseSync(':memory:');
  for(const ddl of [resourcesSchema,appSchema,schema,readFileSync(new URL('../migrations/0059_people_default_page_indexes.sql',import.meta.url),'utf8')])sql.exec(ddl);
  const f=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
  const a=sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)');
  for(let i=0;i<1000;i++) {
    const id=`p${String(i).padStart(4,'0')}`,data=JSON.stringify({uid:id,displayName:`Person ${i}`,email:'private'});
    f.run(`users/${id}`,'users','users',id,id,data,1,1);a.run(`users/${id}`,'users',id,id,data,1);
  }
  let force=false,probes=0,heads=0,oldQueries=0;
  sql.function('quota_probe',path=>{probes++;return path;});
  const db={prepare(q){
    if(force && q.includes('quota_people_head'))throw Error('no such index: idx_cf_people_default_page');
    const stmt=sql.prepare(q.replace('f.path=c.path','f.path=quota_probe(c.path)'));
    return{bind(...args){return{first:async()=>stmt.get(...args)||null,all:async()=>{
      const results=stmt.all(...args);if(q.includes('quota_people_head'))heads+=results.length;
      if(q.includes('WITH candidates'))oldQueries++;return{results};
    }};}};
  }};
  const worker=createWorker(async token=>token),env={DB:db,FIREBASE_PROJECT_ID:'test'};
  const query=async(extra={})=>{
    const r=await worker.fetch(new Request('https://example.test/v1/firestore/query',{method:'POST',headers:{authorization:'Bearer viewer','content-type':'application/json'},body:JSON.stringify({parent:'users',limit:30,...extra})}),env);
    assert.equal(r.status,200,await r.clone().text());return (await r.json()).documents;
  };
  const compare=async()=>{
    force=false;probes=0;heads=0;oldQueries=0;const actual=await query(),fast={probes,heads,oldQueries};
    force=true;probes=0;oldQueries=0;const expected=await query(),slow={probes,oldQueries};force=false;
    assert.deepEqual(actual,expected);assert.deepEqual(actual,await query({startAt:[]}));return{actual,fast,slow};
  };
  let result=await compare();
  assert.equal(result.actual.length,30);assert.equal(result.fast.heads,62);assert.equal(result.fast.probes,0);
  assert.equal(result.fast.oldQueries,0);assert.ok(result.slow.probes>=30);
  assert.ok(result.actual.every(doc=>doc.data.email===undefined));
  // The head cannot prove an exact page with many hidden early accounts.
  for(let i=0;i<30;i++)sql.prepare('UPDATE friendbook_cf_firestore_documents SET document_json=? WHERE path=?').run(JSON.stringify({banned:true}),`users/p${String(i).padStart(4,'0')}`);
  result=await compare();assert.ok(result.fast.oldQueries>0);assert.equal(result.actual[0].id,'p0030');
  // Legacy-only records and a canonical shadow outside the first window must
  // remain visible or hidden exactly as the complete previous implementation.
  sql.prepare("DELETE FROM friendbook_cf_firestore_documents WHERE path='users/p0031'").run();
  result=await compare();assert.ok(result.actual.some(doc=>doc.id==='p0031'));
  assert.ok(!result.actual.some(doc=>doc.id==='p0000'));
  sql.close();
});

test('Home story expiry uses indexed candidate seeks and preserves legacy and privacy behavior', async () => {
  const sql=new DatabaseSync(':memory:');sql.exec(resourcesSchema);sql.exec(appSchema);sql.exec(schema);
  sql.exec(readFileSync(new URL('../migrations/0062_story_timestamp_expiry_lookup.sql',import.meta.url),'utf8'));
  const legacy=sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)');
  const canonical=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
  const ts=millis=>({__type:'timestamp',millis});
  const story=(id,data,modern=true)=>{
    const payload=JSON.stringify(data);
    if(modern) canonical.run(`stories/${id}`,'stories','stories',id,'alice',payload,1,1);
    else legacy.run(`stories/${id}`,'stories',id,'alice',payload,1);
  };
  for(const uid of ['alice','bob'])legacy.run(`users/${uid}`,'users',uid,uid,JSON.stringify({uid}),1);
  const data={authorId:'alice',privacy:'Public'};
  for(let i=0;i<300;i++)story(`expired${i}`,{...data,expiresAt:ts(9)},i%2===0);
  story('current',{...data,expiresAt:ts(15),viewedBy:['alice']});
  story('private',{...data,privacy:'Only me',expiresAt:ts(15)});
  story('shadow',{...data,expiresAt:ts(9)});
  story('shadow',{...data,expiresAt:ts(20)},false);
  story('wrapped',{fields:{authorId:{stringValue:'alice'},privacy:{stringValue:'Public'},expiresAt:{timestampValue:'1970-01-01T00:00:00.020Z'}}},false);
  story('oldNumber',{...data,expiresAt:1000},false);
  const plans=[];
  let candidates=0;
  const db={prepare(q){const stmt=sql.prepare(q);return{bind(...args){return{
    first:async()=>stmt.get(...args)||null,
    all:async()=>{const results=stmt.all(...args);if(q.includes('INDEXED BY') && q.includes('story_timestamp_expiry_lookup')){
      candidates+=results.length;plans.push(...sql.prepare('EXPLAIN QUERY PLAN '+q).all(...args).map(row=>row.detail));
    }return {results};},
  };}};}};
  const app=createWorker(async token=>token), env={DB:db,FIREBASE_PROJECT_ID:'test'};
  const query=async millis=>{
    const response=await app.fetch(new Request('https://example.test/v1/firestore/query',{method:'POST',
      headers:{authorization:'Bearer bob','content-type':'application/json'},body:JSON.stringify({parent:'stories',
        filters:[{field:'expiresAt',op:'>',value:ts(millis)}],orderBy:[{field:'expiresAt',direction:'ASCENDING'}],limit:10})}),env);
    assert.equal(response.status,200);return (await response.json()).documents;
  };
  const indexed=await query(10);
  assert.deepEqual(indexed,await query(10.5)); // decoding truncates; fractional cursor uses compatibility path
  assert.deepEqual(indexed.map(row=>row.id),['current','wrapped']);
  assert.equal(indexed[0].data.viewedBy,undefined);
  assert.equal(candidates,3);
  assert.match(plans.join('\n'),/SEARCH.*idx_cf_story_timestamp_expiry_lookup/);
  assert.match(plans.join('\n'),/SEARCH.*idx_cf_legacy_story_timestamp_expiry_lookup/);
  sql.close();
});

test('recent friend suggestions seek indexed profiles and preserve privacy, historical timestamps and cursors', async () => {
  const sql=new DatabaseSync(':memory:');sql.exec(resourcesSchema);sql.exec(appSchema);sql.exec(schema);
  sql.exec(readFileSync(new URL('../migrations/0067_recent_people_pages.sql',import.meta.url),'utf8'));
  const canonical=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
  const legacy=sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)');
  const add=(id,data,modern=true)=>modern
    ? canonical.run(`users/${id}`,'users','users',id,id,JSON.stringify(data),1,1)
    : legacy.run(`users/${id}`,'users',id,id,JSON.stringify(data),1);
  const ts=millis=>({__type:'timestamp',millis});
  add('viewer',{uid:'viewer'});
  for(let i=0;i<250;i++) add(`person${String(i).padStart(3,'0')}`,{createdAt:ts(i),email:'private@example.test'},i%2===0);
  add('banned',{createdAt:ts(500),banned:true});
  add('banned',{createdAt:ts(600)},false);
  for(let i=0;i<45;i++) add(`hidden${i}`,{createdAt:ts(1000+i),...(i%2?{banned:true}:{deactivated:true})},i%3!==0);
  add('wrapped',{fields:{createdAt:{timestampValue:'1970-01-01T00:00:00.248Z'}}},false);
  add('fraction',{createdAt:ts(247.5)});
  // Production mirrors nearly all canonical profiles. Return-row counters alone
  // won't catch a scan of mirrors rejected by NOT EXISTS.
  for(let i=0;i<2000;i++) {
    const id=`mirror${i}`, data={createdAt:ts(-1-i)};
    add(id,data);add(id,data,false);
  }
  let legacyProbes=0;
  sql.function('quota_probe',path=>{legacyProbes++;return path;});
  let reads=0, scans=0, largestStandardRead=0;
  const plans=[];
  const db={prepare(q){if(q.includes('quota_root_users_recent')) scans++;const stmt=sql.prepare(q.includes('idx_cf_legacy_recent_people')?q.replace('f.path=a.path','f.path=quota_probe(a.path)'):q);return{bind(...args){return{
    first:async()=>stmt.get(...args)||null,
    all:async()=>{const results=stmt.all(...args);if(q.includes('INDEXED BY')&&q.includes('recent_people')){
      if(q.includes('ORDER BY')) largestStandardRead=Math.max(largestStandardRead,results.length);
      reads+=results.length;plans.push(...sql.prepare('EXPLAIN QUERY PLAN '+q).all(...args).map(row=>row.detail));
    }return {results};},
  };}};}};
  const app=createWorker(async token=>token),env={DB:db,FIREBASE_PROJECT_ID:'test'};
  const query=async spec=>{
    const r=await app.fetch(new Request('https://example.test/v1/firestore/query',{method:'POST',headers:{authorization:'Bearer viewer','content-type':'application/json'},
      body:JSON.stringify({parent:'users',orderBy:[{field:'createdAt',direction:'DESCENDING'}],limit:5,...spec})}),env);
    assert.equal(r.status,200);return (await r.json()).documents;
  };
  const page=await query({});
  assert.ok(legacyProbes<100,`old legacy mirrors must not be examined: ${legacyProbes}`);
  assert.equal(scans,0,'hidden profiles must not trigger a users collection scan');
  assert.deepEqual(page,await query({endBefore:[]}));
  assert.equal(page[1].id,'wrapped');
  assert.doesNotMatch(JSON.stringify(page),/private@example|banned/);
  const anchor=page.at(-1);
  const startAfter=[anchor.data.createdAt,anchor.id];
  assert.deepEqual(await query({startAfter}),await query({startAfter,endBefore:[]}));
  assert.ok(reads>0 && reads<=24);
  assert.equal(largestStandardRead,5);
  legacyProbes=0;
  const mirrorCursor={startAfter:[ts(0),'person000']};
  assert.deepEqual(await query(mirrorCursor),await query({...mirrorCursor,endBefore:[]}));
  assert.ok(legacyProbes<=8,`mirror-only people page must stop at the canonical floor: ${legacyProbes}`);
  assert.match(plans.join('\n'),/SEARCH.*idx_cf_recent_people/);
  assert.match(plans.join('\n'),/SEARCH.*idx_cf_legacy_recent_people/);
  assert.doesNotMatch(plans.join('\n'),/USE TEMP B-TREE/);
  sql.close();
});

test('healthy socket saves redundant profile writes without suppressing fallback or visibility changes', async t=>{
 const sql=new DatabaseSync(':memory:');t.after(()=>sql.close());
 for(const ddl of [resourcesSchema,appSchema,schema,socialGuardSchema])sql.exec(ddl);
 for(const file of ['0031_presence_sessions.sql','0064_presence_read_seeks.sql'])
   sql.exec(readFileSync(new URL(`../migrations/${file}`,import.meta.url),'utf8'));
 const now=Date.now(), prior=now-240_000;
 const profile={uid:'alice',isOnline:true,isActive:true,showActiveStatus:true,lastSeenMs:prior,lastSeen:{__type:'timestamp',millis:prior}};
 sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)').run('users/alice','users','users','alice','alice',JSON.stringify(profile),prior,prior);
 sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)').run('users/alice','users','alice','alice',JSON.stringify(profile),prior);
 sql.prepare('INSERT INTO friendbook_cf_presence_sessions VALUES (?,?,?,?,?)').run('alice','socket',1,1,now-1000);
 let batches=0, changes=0, profileReads=0;
 const db={prepare(q){const stmt=sql.prepare(q);return{bind(...args){return{run:async()=>{const result=stmt.run(...args);changes+=result.changes;return result;},all:async()=>({results:stmt.all(...args)}),first:async()=>{if(args[0]==='users/alice')profileReads++;return stmt.get(...args)||null;}};}};},async batch(stmts){batches++;sql.exec('BEGIN');try{for(const s of stmts)await s.run();sql.exec('COMMIT');}catch(e){sql.exec('ROLLBACK');throw e;}}};
 const app=createWorker(async token=>token),env={DB:db,FIREBASE_PROJECT_ID:'test'};
 const patch=(flags={},actor='alice')=>app.fetch(new Request('https://example.test/v1/firestore/document?path=users/alice',{method:'PATCH',headers:{authorization:`Bearer ${actor}`,'content-type':'application/json'},body:JSON.stringify({fields:{isOnline:true,isActive:true,showActiveStatus:true,lastSeenMs:now,lastSeen:{__fieldValue:'serverTimestamp'},...flags}})}),env);
 assert.equal((await patch({},'bob')).status,403);
 profileReads=0;
 assert.equal((await patch()).status,200);
 assert.equal(profileReads,1,'the account gate and heartbeat share one profile read');
 assert.equal(batches,0);assert.equal(changes,0);
 sql.prepare('UPDATE friendbook_cf_presence_sessions SET last_seen=?').run(now-220_000);
 const beforeFallback=sql.prepare('SELECT total_changes() n').get().n;
 assert.equal((await patch()).status,200);
 assert.equal(batches,0);assert.equal(changes,0);
 assert.equal(sql.prepare('SELECT total_changes() n').get().n-beforeFallback,1);
 sql.prepare('UPDATE friendbook_cf_presence_sessions SET last_seen=?').run(now-1000);
 const offline={isOnline:false,isActive:false,showActiveStatus:false};
 assert.equal((await patch(offline)).status,200);
 assert.equal(batches,1);assert.equal(changes,2,'canonical and mirror change without temporary guards');
 assert.equal((await patch(offline)).status,200);
 assert.equal(batches,1);assert.equal(changes,2);
 const row=sql.prepare("SELECT document_json FROM friendbook_cf_documents WHERE path='users/alice'").get();
 assert.equal(JSON.parse(row.document_json).showActiveStatus,false);
});

test('indexed friendship guards reject racing historical duplicates without partial changes', async () => {
  const sql=new DatabaseSync(':memory:');
  for(const n of ['0003_additive_interactions.sql','0011_additive_app_documents.sql','0014_additive_firestore_documents.sql','0018_social_guards.sql','0019_trusted_friendships.sql','0027_friend_participant_indexes.sql']) sql.exec(readFileSync(new URL(`../migrations/${n}`,import.meta.url),'utf8'));
  sql.exec(readFileSync(new URL('../migrations/0084_friend_guard_exceptions.sql',import.meta.url),'utf8'));
  let race=null;
  const db={prepare(q){const s=sql.prepare(q);return{bind(...a){return{run:async()=>s.run(...a),all:async()=>({results:s.all(...a)}),first:async()=>s.get(...a)||null};}};},async batch(stmts){if(race){const f=race;race=null;f();}sql.exec('BEGIN');try{for(const s of stmts)await s.run();sql.exec('COMMIT');}catch(e){sql.exec('ROLLBACK');throw e;}}};
  const insert=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
  const put=(path,data)=>{const [coll,id]=path.split('/');insert.run(path,coll,coll,id,'',JSON.stringify(data),1,1);};
  for(const uid of ['alice','bob']) put(`users/${uid}`,{uid,friendsCount:0});
  put('friend_requests/r',{fromUid:'alice',toUid:'bob',status:'pending'});
  const app=createWorker(async t=>t),env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test',FRIEND_GUARD_INDEXED:'true'};
  const call=ops=>app.fetch(new Request('https://example.test/v1/firestore/batch',{method:'POST',headers:{authorization:'Bearer bob','content-type':'application/json'},body:JSON.stringify({operations:ops})}),env);
  const accept=[{op:'update',path:'friend_requests/r',updates:{status:'accepted'}},{op:'set',path:'friends/new',data:{participants:['alice','bob'],createdAt:{__fieldValue:'serverTimestamp'}}},...['alice','bob'].map(uid=>({op:'update',path:`users/${uid}`,updates:{friendsCount:{__fieldValue:'increment',value:1}}}))];
  race=()=>put('friends/race',{participants:['eve','alice','bob']});
  assert.notEqual((await call(accept)).status,200);
  assert.equal(JSON.parse(sql.prepare("SELECT document_json FROM friendbook_cf_firestore_documents WHERE path='friend_requests/r'").get().document_json).status,'pending');
  assert.equal(sql.prepare("SELECT path FROM friendbook_cf_firestore_documents WHERE path='friends/new'").get(),undefined);
  sql.prepare("DELETE FROM friendbook_cf_firestore_documents WHERE path='friends/race'").run();
  // These invalid wrapped values arrive after the initial candidate probe. The
  // guard must detect them inside the transaction and retain the full scan's
  // malformed-JSON failure rather than relying on the earlier snapshot.
  for(const storage of ['canonical','legacy']) {
    const bad={fields:{participants:{arrayValue:{values:['not-json']}}}};
    race=()=>storage==='canonical' ? put('friends/race',bad)
      : sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)').run('friends/race','friends','race','',JSON.stringify(bad),1);
    assert.equal((await call(accept)).status,409,storage);
    assert.equal(sql.prepare("SELECT count(*) n FROM friendbook_cf_trusted_friendships").get().n,0);
    assert.equal(JSON.parse(sql.prepare("SELECT document_json FROM friendbook_cf_firestore_documents WHERE path='friend_requests/r'").get().document_json).status,'pending');
    sql.prepare("DELETE FROM friendbook_cf_firestore_documents WHERE path='friends/race'").run();
    sql.prepare("DELETE FROM friendbook_cf_documents WHERE path='friends/race'").run();
  }
  assert.equal((await call(accept)).status,200);
  const remove=[{op:'delete',path:'friends/new'},...['alice','bob'].map(uid=>({op:'update',path:`users/${uid}`,updates:{friendsCount:{__fieldValue:'increment',value:-1}}}))];
  race=()=>sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)').run('friends/race','friends','race','',JSON.stringify({fields:{participants:{arrayValue:{values:[{stringValue:'bob'},{stringValue:'alice'}]}}}}),1);
  assert.notEqual((await call(remove)).status,200);
  assert.ok(sql.prepare("SELECT path FROM friendbook_cf_firestore_documents WHERE path='friends/new'").get());
  assert.equal(JSON.parse(sql.prepare("SELECT document_json FROM friendbook_cf_firestore_documents WHERE path='users/alice'").get().document_json).friendsCount,1);
  sql.prepare("DELETE FROM friendbook_cf_documents WHERE path='friends/race'").run();
  assert.equal((await call(remove)).status,200);
  sql.close();
});
