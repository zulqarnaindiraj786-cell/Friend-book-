import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import { createWorker } from '../../worker.js';
import { authorizePost } from '../firebase-post-access.js';
import { mirrorAllLegacy } from './fixtures/canonical.js';

const MIGRATION_0001 = readFileSync(new URL('../migrations/0001_additive_profiles.sql', import.meta.url), 'utf8');
const MIGRATION_0002 = readFileSync(new URL('../migrations/0002_additive_copy_staging.sql', import.meta.url), 'utf8');
const MIGRATION_0003 = readFileSync(new URL('../migrations/0003_additive_interactions.sql', import.meta.url), 'utf8');
const MIGRATION_0011 = readFileSync(new URL('../migrations/0011_additive_app_documents.sql', import.meta.url), 'utf8');
const MIGRATION_0014 = readFileSync(new URL('../migrations/0014_additive_firestore_documents.sql', import.meta.url), 'utf8');
const MIGRATION_0018 = readFileSync(new URL('../migrations/0018_social_guards.sql', import.meta.url), 'utf8');
const MIGRATION_0019 = readFileSync(new URL('../migrations/0019_trusted_friendships.sql', import.meta.url), 'utf8');

function createSqliteD1() {
  const sql = new DatabaseSync(':memory:');
  for (const m of [MIGRATION_0001, MIGRATION_0002, MIGRATION_0003, MIGRATION_0011, MIGRATION_0014, MIGRATION_0018, MIGRATION_0019]) {
    sql.exec(m);
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
    async batch(stmts) {
      sql.exec('BEGIN');
      try { for (const s of stmts) await s.run(); sql.exec('COMMIT'); }
      catch (error) { sql.exec('ROLLBACK'); throw error; }
    },
  };
  return { sql, db };
}

test('/v1/docs preserves historical staging records and stores new Cloudflare documents with D1-first ACL', async () => {
  const { sql, db } = createSqliteD1();
  await db
    .prepare(
      `INSERT INTO friendbook_cf_documents (path, collection, doc_id, owner_uid, document_json, updated_at)
       VALUES ('posts/old_post_1', 'posts', 'old_post_1', 'uid_old', ?, 1700000000000)`,
    )
    .bind(
      JSON.stringify({
        fields: {
          authorId: { stringValue: 'uid_old' },
          authorName: { stringValue: 'Old User' },
          content: { stringValue: 'Preserved historical post' },
          privacy: { stringValue: 'Public' },
          likes: { integerValue: '5' },
        },
      }),
    )
    .run();

  await db.prepare(
    `INSERT INTO friendbook_cf_documents (path, collection, doc_id, owner_uid, document_json, updated_at)
     VALUES ('users/uid_old', 'users', 'uid_old', 'uid_old', ?, 1690000000000)`,
  ).bind(JSON.stringify({ fields: { displayName: { stringValue: 'Old User' }, banned: { booleanValue: false } } })).run();
  for (const [id, privacy] of [['private_post', 'Friends'], ['only_me_post', 'Only me']]) {
    await db.prepare(
      `INSERT INTO friendbook_cf_documents (path, collection, doc_id, owner_uid, document_json, updated_at)
       VALUES (?, 'posts', ?, 'uid_old', ?, 1800000000000)`,
    ).bind(`posts/${id}`, id, JSON.stringify({ fields: {
      authorId: { stringValue: 'uid_old' }, content: { stringValue: id }, privacy: { stringValue: privacy },
    } })).run();
  }

  mirrorAllLegacy(sql);
  const w = createWorker(async () => 'uid_new');
  const env = { DB: db, FIREBASE_PROJECT_ID: 'friendbook-test' };

  // 1. Write user profile + new post from an updated APK user to Cloudflare D1
  const userPutRes = await w.fetch(
    new Request('https://example.com/v1/docs/users/uid_new', {
      method: 'PUT',
      headers: {
        Authorization: 'Bearer test-token',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        displayName: 'Updated User',
        photoUrl: '',
        banned: false,
      }),
    }),
    env,
  );
  assert.equal(userPutRes.status, 200);
  const mirroredProfile = await w.fetch(
    new Request('https://example.com/v1/firestore/document?path=users/uid_new', {
      headers: { Authorization: 'Bearer test-token' },
    }), env,
  );
  assert.equal(mirroredProfile.status, 200);
  assert.equal((await mirroredProfile.json()).data.displayName, 'Updated User');

  const putRes = await w.fetch(
    new Request('https://example.com/v1/docs/posts/new_cf_post_1', {
      method: 'PUT',
      headers: {
        Authorization: 'Bearer test-token',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        authorId: 'uid_new',
        authorName: 'Updated User',
        content: 'Brand new Cloudflare D1 post',
        privacy: 'Public',
        likes: 0,
      }),
    }),
    env,
  );
  assert.equal(putRes.status, 200);

  // 2. List posts from /v1/docs?collection=posts — returns both new Cloudflare post + preserved historical post
  const listRes = await w.fetch(
    new Request('https://example.com/v1/docs?collection=posts&limit=20', {
      method: 'GET',
      headers: { Authorization: 'Bearer test-token' },
    }),
    env,
  );
  assert.equal(listRes.status, 200);
  const listData = await listRes.json();
  assert.equal(listData.collection, 'posts');
  assert.equal(listData.documents.length, 2);
  assert.equal(listData.documents[0].id, 'new_cf_post_1');
  assert.equal(listData.documents[0].content, 'Brand new Cloudflare D1 post');
  assert.equal(listData.documents[1].id, 'old_post_1');
  assert.equal(listData.documents[1].content, 'Preserved historical post');
  assert.equal(listData.documents[1].likes, 5);

  // 3. Verify D1-first authorizePost succeeds without calling Firestore REST fetcher
  const ctx = {};
  const allowed = await authorizePost(
    {
      project: 'friendbook-test',
      token: 'test-token',
      uid: 'uid_new',
      db,
    },
    'posts',
    'new_cf_post_1',
    async () => {
      throw new Error('Firestore REST should not be called when post and user are in Cloudflare D1');
    },
    ctx,
  );
  assert.equal(allowed, true);
  assert.equal(ctx.owner, 'uid_new');
});


test('canonical list rows do not incur duplicate canonical-root reads and still enforce post privacy', async t => {
  const {appDocuments}=await import('../app-documents.js');
  const {sql,db}=createSqliteD1();t.after(()=>sql.close());
  const add=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES(?,?,?,?,?,?,?,?)');
  for(const uid of ['viewer','author'])add.run('users/'+uid,'users','users',uid,uid,JSON.stringify({uid}),1,1);
  for(let i=0;i<20;i++){
    const id='p'+i;add.run('posts/'+id,'posts','posts',id,'author',JSON.stringify({authorId:'author',privacy:i%2?'Only me':'Public',content:id}),1,i+1);
  }
  let duplicateReads=0,postAclReads=0;
  const counted={prepare(q){
    if(q.startsWith('SELECT owner_uid, document_json'))duplicateReads++;
    const prepared=db.prepare(q);return {bind(...args){
      if(q.includes('SELECT document_json') && String(args[0]).startsWith('posts/'))postAclReads++;
      return prepared.bind(...args);
    }};
  }};
  const request=()=>new Request('https://local.invalid/v1/docs?collection=posts&limit=20');
  const response=await appDocuments(request(),{DB:counted},{uid:'viewer'});
  assert.equal(response.status,200);
  const result=await response.json();
  assert.equal(result.documents.length,10);
  assert.ok(result.documents.every(doc=>doc.privacy==='Public'));
  assert.equal(duplicateReads,0);
  assert.equal(postAclReads,20,'each post still gets the original authoritative ACL check');
  sql.prepare("UPDATE friendbook_cf_firestore_documents SET document_json=json_set(document_json,'$.blocked',json(?)) WHERE path='users/author'").run(JSON.stringify(['viewer']));
  assert.deepEqual((await (await appDocuments(request(),{DB:counted},{uid:'viewer'})).json()).documents,[]);
});


test('identical legacy profile PUTs do not generate timestamp-only dual writes', async t => {
  const {appDocuments}=await import('../app-documents.js');
  const {sql,db}=createSqliteD1();t.after(()=>sql.close());
  const oldNow=Date.now;let clock=1800000000000;Date.now=()=>clock++;
  t.after(()=>{Date.now=oldNow;});
  const save=()=>appDocuments(new Request('https://local.invalid/v1/docs/users/viewer',{
    method:'PUT',headers:{'content-type':'application/json'},body:JSON.stringify({displayName:'Viewer',photoUrl:''})
  }),{DB:db},{uid:'viewer'});
  const first=await save();assert.equal(first.status,200,await first.clone().text());
  const stored=sql.prepare("SELECT document_json,update_time FROM friendbook_cf_firestore_documents WHERE path='users/viewer'").get();
  const before=sql.prepare('SELECT total_changes() n').get().n;
  for(let i=0;i<5;i++){const response=await save();assert.equal(response.status,200,await response.clone().text());}
  assert.equal(sql.prepare('SELECT total_changes() n').get().n,before,'no base, mirror, guard or projection writes for identical saves');
  assert.deepEqual(sql.prepare("SELECT document_json,update_time FROM friendbook_cf_firestore_documents WHERE path='users/viewer'").get(),stored);
  sql.prepare("DELETE FROM friendbook_cf_documents WHERE path='users/viewer'").run();
  assert.equal((await save()).status,200);
  assert.ok(sql.prepare("SELECT 1 FROM friendbook_cf_documents WHERE path='users/viewer'").get(),'an identical save still repairs a missing mirror');
  const changed=await appDocuments(new Request('https://local.invalid/v1/docs/users/viewer',{
    method:'PUT',headers:{'content-type':'application/json'},body:JSON.stringify({displayName:'Renamed'})
  }),{DB:db},{uid:'viewer'});
  assert.equal(changed.status,200,await changed.clone().text());
  const updated=sql.prepare("SELECT document_json,update_time FROM friendbook_cf_firestore_documents WHERE path='users/viewer'").get();
  assert.equal(JSON.parse(updated.document_json).displayName,'Renamed');
  assert.ok(updated.update_time>stored.update_time,'genuine edits still advance canonical metadata');
  const beforeDenied=sql.prepare('SELECT total_changes() n').get().n;
  const denied=await appDocuments(new Request('https://local.invalid/v1/docs/users/viewer',{
    method:'PUT',headers:{'content-type':'application/json'},body:JSON.stringify({displayName:'Renamed'})
  }),{DB:db},{uid:'attacker'});
  assert.equal(denied.status,403,'same-value payload never bypasses authorization');
  assert.equal(sql.prepare('SELECT total_changes() n').get().n,beforeDenied);
});
