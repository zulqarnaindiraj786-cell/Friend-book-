import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import { prepare, batchStatement, promoteCollection, legacyOnly } from '../../scripts/loadtest/promote_legacy_roots.mjs';

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
  const action = batchStatement(planned);
  const done = (await query(action.sql, action.params)).results;
  assert.equal(done.length, 3);
  assert.ok(!done.some(hit => hit.path === 'posts/p2' || hit.path === 'posts/p3'));
  assert.equal(db.prepare("SELECT document_json FROM friendbook_cf_firestore_documents WHERE path='posts/p3'").get().document_json,
    '{"authorId":"alice","createdAt":3,"id":"p3"}');
});

test('unsafe shapes and stale metadata never get promoted', () => {
  const base = { path: 'posts/p1', collection: 'posts', doc_id: 'p1', owner_uid: 'a', updated_at: 1, document_json: '{"a":1}' };
  assert.equal(prepare({ ...base, collection: 'sessions' }).reason, 'unsafe_shape');
  assert.equal(prepare({ ...base, path: 'posts/other' }).reason, 'unsafe_shape');
  assert.equal(prepare({ ...base, updated_at: 1.5 }).reason, 'unsafe_shape');
  assert.equal(prepare({ ...base, document_json: 'x'.repeat(100_001) }).reason, 'unsafe_shape');
  assert.throws(() => batchStatement([]), /unsafe plan length/);
  assert.equal(batchStatement(Array.from({ length: 7 }, () => prepare(base))).params.length, 91);
  assert.throws(() => batchStatement(Array.from({ length: 8 }, () => prepare(base))), /unsafe plan length/);
});
