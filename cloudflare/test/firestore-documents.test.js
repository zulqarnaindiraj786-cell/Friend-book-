import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import { createWorker } from '../../worker.js';

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
