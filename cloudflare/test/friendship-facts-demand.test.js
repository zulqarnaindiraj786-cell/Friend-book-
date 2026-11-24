import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {readFileSync} from 'node:fs';
import {createWorker} from '../../worker.js';

const migration=name=>readFileSync(new URL(`../migrations/${name}.sql`,import.meta.url),'utf8');

test('friendship facts use bounded indexed existence checks even with duplicate and malformed historical rows',async t=>{
 const sql=new DatabaseSync(':memory:');t.after(()=>sql.close());
 for(const name of ['0003_additive_interactions','0011_additive_app_documents','0014_additive_firestore_documents','0094_friend_membership_facts'])sql.exec(migration(name));
 const put=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
 put.run('users/actor','users','users','actor','actor','{}',1,1);
 const add=(id,participants)=>put.run(`friends/${id}`,'friends','friends',id,'actor',JSON.stringify({participants,secret:'not returned'}),1,1);
 for(let i=0;i<3000;i++)add(`duplicate${i}`,['actor','alice']);
 for(let i=0;i<2000;i++)add(`invalid${i}`,['actor','bob','extra']);
 add('reverse',['carol','actor']);add('unrelated',['eve','mallory']);
 let visited=0;const plans=[];sql.function('quota_probe',path=>{visited++;return 1;});
 const db={prepare(query){
  const instrumented=query.includes('idx_cf_friend_valid_pair')?query.replaceAll("AND json_array_length(f.document_json,'$.participants')=2", "AND json_array_length(f.document_json,'$.participants')=2 AND quota_probe(f.path)"):query;
  const stmt=sql.prepare(instrumented);return{bind(...args){return{
   first:async()=>stmt.get(...args)||null,
   all:async()=>{if(query.includes('idx_cf_friend_valid_pair'))plans.push(...sql.prepare('EXPLAIN QUERY PLAN '+instrumented).all(...args).map(row=>row.detail));return{results:stmt.all(...args)};}
  };}};
 }};
 const app=createWorker(async token=>token),env={DB:db,FIREBASE_PROJECT_ID:'test'};
 const call=(uid,body)=>app.fetch(new Request('https://example.test/v1/firestore/friendship-facts',{method:'POST',headers:{authorization:`Bearer ${uid}`,'content-type':'application/json'},body:JSON.stringify(body)}),env);
 const response=await call('actor',{candidates:['alice','bob','carol','mallory']});
 assert.equal(response.status,200,await response.clone().text());
 assert.deepEqual((await response.json()).facts,[{uid:'alice',friend:true},{uid:'bob',friend:false},{uid:'carol',friend:true},{uid:'mallory',friend:false}]);
 assert.ok(visited<=2,`duplicate rows were scanned: ${visited}`);
 assert.match(plans.join('\n'),/SEARCH f USING INDEX idx_cf_friend_valid_pair/);
 assert.equal((await call('actor',{candidates:['actor']})).status,400);
 assert.equal((await call('actor',{candidates:['alice','alice']})).status,400);
 assert.equal((await call('actor',{candidates:Array.from({length:11},(_,i)=>`u${i}`)})).status,400);
 assert.equal((await call('actor',{candidates:['alice'],actor:'eve'})).status,400);
 assert.equal((await call('actor',{candidates:['x/y']})).status,400);
 // A caller cannot inspect a friendship that does not include them.
 const thirdParty=await call('actor',{candidates:['eve']});assert.deepEqual((await thirdParty.json()).facts,[{uid:'eve',friend:false}]);
 sql.exec('DROP INDEX idx_cf_friend_valid_pair');
 assert.equal((await call('actor',{candidates:['alice']})).status,503,'missing migration must fail closed');
});
