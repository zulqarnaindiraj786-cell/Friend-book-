import test from 'node:test';
import assert from 'node:assert/strict';
import {fixture} from './fixtures/interaction-db.js';
import {canonicalSeed} from './fixtures/canonical.js';
import {readRows} from '../firestore-documents.js';

test('batch document pre-reads use one bounded IN query and preserve missing rows', async t=>{
  const f=fixture();t.after(f.close);
  const add=canonicalSeed(f.sql);
  add.run('users/alice','users','alice','alice',JSON.stringify({uid:'alice',displayName:'Alice'}),1);
  add.run('posts/p1','posts','p1','alice',JSON.stringify({authorId:'alice'}),1);
  let reads=0;
  const db={prepare(sql){
    assert.match(sql,/WHERE path IN \(/);
    reads++;
    return f.env.DB.prepare(sql);
  }};
  const rows=await readRows(db,['users/alice','posts/p1','users/missing','users/alice']);
  assert.equal(reads,1);
  assert.equal(rows.size,3);
  assert.equal(JSON.parse(rows.get('users/alice').document_json).displayName,'Alice');
  assert.equal(JSON.parse(rows.get('posts/p1').document_json).authorId,'alice');
  assert.equal(rows.get('users/missing'),null);
});

test('batch document pre-reads split at a safe D1 bind count', async t=>{
  const f=fixture();t.after(f.close);
  let reads=0;
  const db={prepare(sql){
    const count=(sql.match(/\?/g)||[]).length;
    assert.ok(count<=80);
    reads++;
    return f.env.DB.prepare(sql);
  }};
  const paths=Array.from({length:161},(_,i)=>`users/u${i}`);
  const rows=await readRows(db,paths);
  assert.equal(reads,3);
  assert.equal(rows.size,161);
  assert.ok([...rows.values()].every(row=>row===null));
});
