import {test} from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {readFileSync} from 'node:fs';
import {createWorker} from '../../worker.js';

const migration=name=>readFileSync(new URL(`../migrations/${name}`,import.meta.url),'utf8');
const initial=readFileSync(new URL('../migrations/0077_user_follow_membership.sql',import.meta.url),'utf8');
const candidate=readFileSync(new URL('../migrations/0078_follow_trigger_cost.sql',import.meta.url),'utf8');

test('follow membership candidate matches full scan and maintains changes without rewriting on heartbeat',async t=>{
  const sql=new DatabaseSync(':memory:');t.after(()=>sql.close());
  for(const name of ['0003_additive_interactions.sql','0011_additive_app_documents.sql','0014_additive_firestore_documents.sql','0018_social_guards.sql'])sql.exec(migration(name));
  const cur=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
  const old=sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)');
  const addCurrent=(id,doc)=>cur.run(`users/${id}`,'users','users',id,id,JSON.stringify(doc),1,1);
  const addOld=(id,doc)=>old.run(`users/${id}`,'users',id,id,JSON.stringify(doc),1);
  addCurrent('viewer',{uid:'viewer',followers:[],following:[]});
  for(let i=0;i<1000;i++) {
    const id=`user${String(i).padStart(4,'0')}`,doc={uid:id,displayName:id,followers:[],following:[]};
    if(i%123===0)doc.followers=['viewer','other','viewer'];
    if(i%177===0)doc.following=['viewer'];
    if(i%246===0)doc.profileLocked=true;
    if(i%369===0)doc.banned=true;
    addCurrent(id,doc);addOld(id,{...doc,id});
  }
  addOld('onlylegacy',{uid:'onlylegacy',followers:['viewer'],following:['other']});
  addOld('wrapped',{fields:{uid:{stringValue:'wrapped'},followers:{arrayValue:{values:[{stringValue:'viewer'}]}},following:{arrayValue:{values:[{stringValue:'viewer'}]}}}});
  addCurrent('shadow',{uid:'shadow',followers:['other']});
  addOld('shadow',{uid:'shadow',followers:['viewer']});
  addCurrent('odd',{uid:'odd',followers:[{'stringValue':'viewer'},true,0,null,'viewer'],following:[]});
  // SQLite extracts the first duplicate JSON key while JSON.parse uses the last.
  cur.run('users/dup','users','users','dup','dup','{"uid":"dup","followers":[],"followers":["viewer"]}',1,1);
  old.run('users/dupOld','users','dupOld','dupOld','{"uid":"dupOld","followers":[],"followers":["viewer"]}',1);
  old.run('users/dupFields','users','dupFields','dupFields','{"fields":null,"fields":{"uid":{"stringValue":"dupFields"},"followers":{"arrayValue":{"values":[{"stringValue":"viewer"}]}}}}',1);
  addCurrent('escaped',{uid:'escaped',followers:['viewer'],bio:'line one\nline two'});
  cur.run('users/escapedKey','users','users','escapedKey','escapedKey','{"uid":"escapedKey","follo\\u0077ers":["viewer"]}',1,1);
  sql.exec(initial);sql.exec(candidate);
  const original=sql.prepare('SELECT * FROM friendbook_cf_user_follow_membership ORDER BY member,kind,source,path').all();
  sql.exec(candidate);
  assert.deepEqual(sql.prepare('SELECT * FROM friendbook_cf_user_follow_membership ORDER BY member,kind,source,path').all(),original,'migration rebackfills exact edges');
  const conflictFix=migration('0082_follow_trigger_conflict.sql');
  sql.exec(conflictFix);sql.exec(conflictFix);
  assert.deepEqual(sql.prepare('SELECT * FROM friendbook_cf_user_follow_membership ORDER BY member,kind,source,path').all(),original,'trigger replacement never rebuilds or loses membership');
  assert.equal(sql.prepare('SELECT ready FROM friendbook_cf_user_follow_index_state WHERE id=1').get().ready,1);
  let force=false,scanned=0,indexed=0,plans=[];
  const db={prepare(q){
    if(force && q.includes('friendbook_cf_user_follow_'))throw Error('no such table: friendbook_cf_user_follow_membership');
    const stmt=sql.prepare(q);return{bind(...args){return{run:async()=>{
      if(/^\s*SELECT/i.test(q))return {success:true,results:stmt.all(...args)};
      return stmt.run(...args);
    },first:async()=>stmt.get(...args)||null,all:async()=>{
      const rows=stmt.all(...args);
      if(q.includes('quota_root_users_membership'))scanned+=rows.length;
      return {results:rows};
    },_query:q,_args:args};}};
  },async batch(stmts){sql.exec('BEGIN');try{
    const rows=[];for(const stmt of stmts){const r=await stmt.run();if(stmt._query.includes('friendbook_cf_user_follow_membership')) {
      indexed+=(r.results||[]).length;plans.push(...sql.prepare('EXPLAIN QUERY PLAN '+stmt._query).all(...stmt._args).map(x=>x.detail));
    }rows.push(r);}sql.exec('COMMIT');return rows;
  }catch(e){sql.exec('ROLLBACK');throw e;}}};
  const app=createWorker(async token=>token),env={DB:db,FIREBASE_PROJECT_ID:'friendbook-test',PAYMENT_ADMIN_UIDS:'admin'};
  async function run(actor,body,count=false){
    const target=count?`/v1/firestore/count?parent=users&filters=${encodeURIComponent(JSON.stringify(body.filters))}`:'/v1/firestore/query';
    const response=await app.fetch(new Request('https://example.test'+target,{method:count?'GET':'POST',headers:{authorization:`Bearer ${actor}`,'content-type':'application/json'},...(count?{}:{body:JSON.stringify({parent:'users',...body})})}),env);
    assert.equal(response.status,200,await response.clone().text());return response.json();
  }
  async function compare(actor,body,count=false){
    force=false;scanned=0;indexed=0;const actual=await run(actor,body,count), fast={scanned,indexed};
    force=true;const expected=await run(actor,body,count);force=false;
    assert.deepEqual(actual,expected);return fast;
  }
  for(const field of ['followers','following'])for(const target of ['viewer','other','']) {
    const body={filters:[{field,op:'array-contains',value:target}],limit:5};
    const fast=await compare('viewer',body);assert.equal(fast.scanned,0);assert.ok(fast.indexed<50);
    await compare('viewer',body,true);
    await compare('viewer',{...body,startAfter:['user0200']});
  }
  await compare('admin',{filters:[{field:'followers',op:'array-contains',value:'viewer'}],orderBy:[{field:'displayName',direction:'DESCENDING'}],limit:10});
  await compare('viewer',{filters:[{field:'followers',op:'array-contains',value:'viewer'},{field:'following',op:'array-contains',value:'viewer'}],limit:20});
  assert.ok(plans.some(p=>p.includes('PRIMARY KEY') && p.includes('member=?') && p.includes('kind=?')));
  // A non-relationship profile edit must not alter the derived index.
  let before=sql.prepare('SELECT total_changes() n').get().n;
  sql.prepare("UPDATE friendbook_cf_firestore_documents SET document_json=json_set(document_json,'$.lastSeen',123) WHERE path='users/user0001'").run();
  assert.equal(sql.prepare('SELECT total_changes() n').get().n-before,1);
  // Even a larger list adds only its new edge, not all existing index rows.
  const members=Array.from({length:300},(_,i)=>`member${i}`);
  sql.prepare('UPDATE friendbook_cf_firestore_documents SET document_json=? WHERE path=?').run(JSON.stringify({uid:'user0002',followers:members}),'users/user0002');
  before=sql.prepare('SELECT total_changes() n').get().n;
  sql.prepare('UPDATE friendbook_cf_firestore_documents SET document_json=? WHERE path=?').run(JSON.stringify({uid:'user0002',followers:[...members,'viewer']}),'users/user0002');
  assert.equal(sql.prepare('SELECT total_changes() n').get().n-before,2,'one document plus one index edge');
  // Outer upserts override INSERT OR IGNORE inside triggers in SQLite. The
  // explicit trigger UPSERT must preserve existing edges and duplicate values.
  const withDuplicates=JSON.stringify({uid:'user0002',followers:[...members,'viewer','viewer','new']});
  sql.prepare(`INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(path) DO UPDATE SET document_json=excluded.document_json`)
    .run('users/user0002','users','users','user0002','user0002',withDuplicates,1,2);
  sql.prepare(`INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?) ON CONFLICT(path) DO UPDATE SET document_json=excluded.document_json`)
    .run('users/user0002','users','user0002','user0002',withDuplicates,2);
  for(const source of [0,1]) {
    assert.equal(sql.prepare("SELECT count(*) n FROM friendbook_cf_user_follow_membership WHERE path='users/user0002' AND source=? AND kind='followers'").get(source).n,302);
  }
  await compare('viewer',{filters:[{field:'followers',op:'array-contains',value:'viewer'}]});
  // A real relationship change updates only affected memberships and is immediately visible.
  sql.prepare("UPDATE friendbook_cf_firestore_documents SET document_json=json_set(document_json,'$.followers',json('[\"viewer\"]')) WHERE path='users/user0001'").run();
  await compare('viewer',{filters:[{field:'followers',op:'array-contains',value:'viewer'}]});
  // A stale mirror must never resurrect a removed canonical relationship.
  sql.prepare("UPDATE friendbook_cf_firestore_documents SET document_json=json_set(document_json,'$.followers',json('[]')) WHERE path='users/user0000'").run();
  await compare('viewer',{filters:[{field:'followers',op:'array-contains',value:'viewer'}]});
  // Converting between plain and Firestore envelopes cannot lose old accounts.
  sql.prepare('UPDATE friendbook_cf_documents SET document_json=? WHERE path=?').run(JSON.stringify({fields:{uid:{stringValue:'onlylegacy'},followers:{arrayValue:{values:[{stringValue:'viewer'}]}}}}),'users/onlylegacy');
  await compare('viewer',{filters:[{field:'followers',op:'array-contains',value:'viewer'}]});
  sql.prepare('UPDATE friendbook_cf_documents SET document_json=? WHERE path=?').run(JSON.stringify({uid:'onlylegacy',followers:['viewer']}),'users/onlylegacy');
  await compare('viewer',{filters:[{field:'followers',op:'array-contains',value:'viewer'}]});
  sql.prepare("DELETE FROM friendbook_cf_firestore_documents WHERE path='users/user0000'").run();
  await compare('viewer',{filters:[{field:'followers',op:'array-contains',value:'viewer'}]});
  // Seeded updates stress source precedence, array order and both membership fields.
  let seed=9813;const random=n=>{seed=(Math.imul(seed,1664525)+1013904223)>>>0;return seed%n;};
  for(let i=0;i<35;i++) {
    const id=`user${String(random(1000)).padStart(4,'0')}`,field=random(2)?'followers':'following';
    const values=random(2)?['viewer','other']:['other'];
    sql.prepare(`UPDATE friendbook_cf_firestore_documents SET document_json=json_set(document_json,'$.${field}',json(?)) WHERE path=?`).run(JSON.stringify(values),`users/${id}`);
    await compare('viewer',{filters:[{field,op:'array-contains',value:'viewer'}],limit:20});
    await compare('viewer',{filters:[{field,op:'array-contains',value:'other'}],limit:10});
  }
  // Unusual paths can share an ID; retain original full-scan tie ordering.
  cur.run('users/noncanonical','users','users','dup','dup',JSON.stringify({uid:'dup',followers:['viewer']}),1,1);
  let slow=await compare('viewer',{filters:[{field:'followers',op:'array-contains',value:'viewer'}]});assert.ok(slow.scanned>1000);
  sql.prepare("DELETE FROM friendbook_cf_firestore_documents WHERE path='users/noncanonical'").run();
  // Unusual Unicode input and too many exceptional records use the complete scan.
  slow=await compare('viewer',{filters:[{field:'followers',op:'array-contains',value:'\u0639'}]});assert.ok(slow.scanned>1000);
  for(let i=0;i<65;i++)addOld(`wrapped${i}`,{fields:{uid:{stringValue:`wrapped${i}`},followers:{arrayValue:{values:[{stringValue:'viewer'}]}}}});
  slow=await compare('viewer',{filters:[{field:'followers',op:'array-contains',value:'viewer'}]});assert.ok(slow.scanned>1000);
  // Empty/incomplete migration state must use compatibility scans.
  sql.exec('DELETE FROM friendbook_cf_user_follow_index_state');
  const result=await compare('viewer',{filters:[{field:'followers',op:'array-contains',value:'viewer'}]});assert.ok(result.scanned>1000);
});
