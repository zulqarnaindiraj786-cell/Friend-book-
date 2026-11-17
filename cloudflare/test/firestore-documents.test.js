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
  const appRow = sql.prepare('SELECT path, owner_uid, document_json FROM friendbook_cf_documents WHERE path = ?').get(`users/${uid}`);
  assert.equal(appRow.owner_uid, uid);
  assert.match(appRow.document_json, /d1-only/);
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
  assert.match(sql.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?').get(`users/${uid}`).document_json,/D1 proof/);
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

test('D1 document misses never fall back to reading the Firebase Firestore API', async () => {
  const sql = new DatabaseSync(':memory:');
  sql.exec(schema);
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
