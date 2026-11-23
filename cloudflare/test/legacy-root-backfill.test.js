import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import { prepare, rowStatement, promoteCollection, legacyOnly } from '../../scripts/loadtest/promote_legacy_roots.mjs';

function fixture(t) {
  const db = new DatabaseSync(':memory:');
  t.after(() => db.close());
  for (const migration of ['0011_additive_app_documents.sql', '0014_additive_firestore_documents.sql'])
    db.exec(readFileSync(new URL(`../migrations/${migration}`, import.meta.url), 'utf8'));
  const legacy = (collection, id, data) => db.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)')
    .run(`${collection}/${id}`, collection, id, 'alice', JSON.stringify(data), Number(id.replace(/\D/g, '') || 1));
  const query = async (sql, params) => ({ results: db.prepare(sql).all(...params) });
  return { db, legacy, query };
}

test('bulk promotion writes only provably equivalent rows and leaves the rest in fallback', async t => {
  const { db, legacy, query } = fixture(t);
  for (let i = 0; i < 240; i++) {
    const id = `c${String(i).padStart(3, '0')}`;
    const safe = i % 40 !== 0;
    legacy('comments', id, safe
      ? { postId: 'p1', authorId: 'alice', text: `t${i}`, createdAt: i, id }
      // A conflicting legacy id would change the document value itself.
      : { postId: 'p1', authorId: 'alice', text: `t${i}`, createdAt: i, id: 'someone-else' });
  }
  const before = await legacyOnly(query, 'comments');
  assert.equal(before, 240);
  const result = await promoteCollection(query, 'comments', { batch: 100 });
  assert.equal(result.before, 240);
  assert.equal(result.inserted, 234);
  assert.equal(result.after, 6);
  assert.match(Object.keys(result.skipped).join(','), /plain_projection_diff/);
  assert.equal(db.prepare('SELECT count(*) n FROM friendbook_cf_firestore_documents').get().n, 234);
  // Idempotent: a second pass moves nothing and reports the six blocked rows.
  const again = await promoteCollection(query, 'comments', { batch: 100 });
  assert.equal(again.inserted, 0);
  assert.equal(again.after, 6);
});

test('a concurrent legacy edit or canonical insert skips exactly that row', async t => {
  const { db, legacy, query } = fixture(t);
  for (let i = 0; i < 5; i++) legacy('posts', `p${i}`, { authorId: 'alice', createdAt: i, id: `p${i}` });
  const rows = db.prepare('SELECT * FROM friendbook_cf_documents ORDER BY path').all();
  const planned = rows.map(prepare);
  assert.ok(planned.every(entry => entry.status === 'promote'));
  db.prepare('UPDATE friendbook_cf_documents SET document_json=? WHERE path=?').run('{"authorId":"alice","createdAt":9,"id":"p2"}', 'posts/p2');
  db.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)')
    .run('posts/p3', 'posts', 'posts', 'p3', 'alice', '{"authorId":"alice","createdAt":3,"id":"p3"}', 1, 1);
  const results = [];
  for (const entry of planned) {
    const action = rowStatement(entry);
    results.push(...(await query(action.sql, action.params)).results);
  }
  assert.equal(results.length, 3);
  assert.ok(!results.some(hit => hit.path === 'posts/p2' || hit.path === 'posts/p3'));
  assert.equal(db.prepare("SELECT document_json FROM friendbook_cf_firestore_documents WHERE path='posts/p3'").get().document_json,
    '{"authorId":"alice","createdAt":3,"id":"p3"}');
});

test('a timestamp encoding change is promoted only with an explicit, value-equal opt-in', () => {
  const wrapped = {
    path: 'comments/c1', collection: 'comments', doc_id: 'c1', owner_uid: 'a', updated_at: 1,
    document_json: '{"fields":{"createdAt":{"timestampValue":"2024-01-01T00:00:00Z"},"text":{"stringValue":"x"}}}',
  };
  assert.equal(prepare(wrapped).reason, 'plain_projection_diff');
  assert.equal(prepare(wrapped, { representationFields: new Set(['createdAt']) }).reason, 'auth_projection_diff');
  const allowed = prepare(wrapped, { representationFields: new Set(['createdAt']), allowEncodingOnly: true });
  assert.equal(allowed.status, 'promote');
  assert.equal(allowed.representation, true);
  const shifted = { ...wrapped, document_json: '{"fields":{"createdAt":{"timestampValue":"2024-01-01T00:00:01Z"},"text":{"stringValue":"x"}}}' };
  assert.equal(prepare(shifted, { representationFields: new Set(['createdAt']), allowEncodingOnly: true }).status, 'promote');
  const other = { ...wrapped, document_json: '{"fields":{"createdAt":{"integerValue":"5"},"text":{"stringValue":"x"}}}' };
  assert.equal(prepare(other, { representationFields: new Set(['createdAt']), allowEncodingOnly: true }).status, 'promote');
});

test('unsafe shapes and stale metadata never get promoted', () => {
  const base = { path: 'posts/p1', collection: 'posts', doc_id: 'p1', owner_uid: 'a', updated_at: 1, document_json: '{"a":1}' };
  assert.equal(prepare({ ...base, collection: 'sessions' }).reason, 'unsafe_shape');
  assert.equal(prepare({ ...base, path: 'posts/other' }).reason, 'unsafe_shape');
  assert.equal(prepare({ ...base, updated_at: 1.5 }).reason, 'unsafe_shape');
  assert.equal(prepare({ ...base, document_json: 'x'.repeat(100_001) }).reason, 'unsafe_shape');
  const statement = rowStatement(prepare(base));
  assert.equal(statement.params.length, 10);
  assert.match(statement.sql, /NOT EXISTS/);
  assert.throws(() => rowStatement({}), /unsafe plan length/);
});

test('complete zero-work inventory exits after one query without page, reinventory or writes', async t => {
  const {db,legacy,query}=fixture(t);
  legacy('posts','p1',{authorId:'alice',createdAt:1,id:'p1'});
  await promoteCollection(query,'posts');
  const before=db.prepare('SELECT total_changes() AS n').get().n;
  for (const write of [false,true]) {
    const calls=[];
    const result=await promoteCollection(async(sql,args)=>{
      calls.push(sql);return query(sql,args);
    },'posts',{write});
    assert.equal(calls.length,1);
    assert.match(calls[0],/COUNT\(\*\) AS sampled/);
    assert.deepEqual(result,{collection:'posts',before:0,inserted:0,after:0,scanned:0,
      skipped:{},facades:0,representations:0,reads:0,truncated:false});
  }
  assert.equal(db.prepare('SELECT total_changes() AS n').get().n,before);
});

test('sentinel-sized or inconsistent inventory cannot falsely report complete promotion', async()=>{
  for (const row of [
    {sampled:25001,legacy_only:0},{sampled:25002,legacy_only:0},
    {sampled:-1,legacy_only:0},{sampled:1,legacy_only:-1},
    {sampled:1,legacy_only:2},{sampled:1,legacy_only:0.5},
  ]) {
    let calls=0;
    await assert.rejects(()=>promoteCollection(async()=>{calls++;return {results:[row]};},'posts'),/inventory unavailable/);
    assert.equal(calls,1,'invalid inventory must stop before any page/write');
  }
});

test('largest complete inventory remains accepted but cap sentinel is rejected', async()=>{
  assert.equal(await legacyOnly(async()=>({results:[{sampled:25000,legacy_only:0}]}),'posts'),0);
  assert.equal(await legacyOnly(async()=>({results:[{sampled:0,legacy_only:0}]}),'posts'),0);
});
