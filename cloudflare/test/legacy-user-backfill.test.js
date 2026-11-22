import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {readFileSync} from 'node:fs';
import {readPlan, promote, statement, planCandidate} from '../../scripts/loadtest/backfill_legacy_users.mjs';

function fixture(t) {
 const db=new DatabaseSync(':memory:');t.after(()=>db.close());
 for(const migration of ['0011_additive_app_documents.sql','0014_additive_firestore_documents.sql','0077_user_follow_membership.sql'])
   db.exec(readFileSync(new URL(`../migrations/${migration}`,import.meta.url),'utf8'));
 const add=(id,data={uid:id,displayName:id,followers:['x'],following:[]})=>db.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)').run(`users/${id}`,'users',id,id,JSON.stringify(data),1);
 const query=async(sql,params)=>({results:db.prepare(sql).all(...params)});
 return {db,add,query};
}

test('atomic promotion matches legacy semantics and maintains follow index',async t=>{
 const {db,add,query}=fixture(t);add('alice');add('bob');
 const plan=await readPlan(query);assert.equal(plan.legacy_only,2);
 assert.deepEqual(await promote(query,plan),{before:2,inserted:2,after:0});
 assert.equal(db.prepare('SELECT count(*) n FROM friendbook_cf_documents').get().n,2);
 assert.equal(db.prepare('SELECT count(*) n FROM friendbook_cf_firestore_documents').get().n,2);
 assert.equal(db.prepare("SELECT count(*) n FROM friendbook_cf_user_follow_membership WHERE source=1 AND member='x'").get().n,2);
 assert.deepEqual(await promote(query,await readPlan(query)),{before:0,inserted:0,after:0});
});

test('racing legacy edit or canonical create aborts the entire statement',async t=>{
 const {db,add,query}=fixture(t);add('alice');add('bob');const plan=await readPlan(query);
 db.prepare('UPDATE friendbook_cf_documents SET document_json=? WHERE path=?').run('{"uid":"bob","bio":"updated"}','users/bob');
 const action=statement(plan.planned);assert.equal((await query(action.sql,action.params)).results.length,0);
 assert.equal(db.prepare('SELECT count(*) n FROM friendbook_cf_firestore_documents').get().n,0);
 db.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)').run('users/alice','users','users','alice','alice','{"uid":"alice","bio":"new"}',1,1);
 assert.equal((await query(action.sql,action.params)).results.length,0);
 assert.equal(db.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?').get('users/alice').document_json,'{"uid":"alice","bio":"new"}');
});

test('preserves matching redundant id and rejects conflicting ids and unsafe records',()=>{
 const row={path:'users/alice',collection:'users',doc_id:'alice',owner_uid:'alice',updated_at:1,document_json:'{"uid":"alice","id":"alice"}'};
 assert.equal(JSON.parse(planCandidate(row).row.document_json).id,'alice');
 assert.throws(()=>planCandidate({...row,document_json:'{"id":"other"}'}),/projection differs/);
 assert.throws(()=>planCandidate({...row,path:'users/other'}),/unsafe source/);
 assert.throws(()=>planCandidate({...row,document_json:JSON.stringify({uid:'alice',followers:Array(21).fill('x')})}),/fanout/);
});

test('refuses more than two legacy users without any writes',async t=>{
 const {db,add,query}=fixture(t);add('a');add('b');add('c');
 await assert.rejects(readPlan(query),/inventory unsafe/);
 assert.equal(db.prepare('SELECT count(*) n FROM friendbook_cf_firestore_documents').get().n,0);
});
