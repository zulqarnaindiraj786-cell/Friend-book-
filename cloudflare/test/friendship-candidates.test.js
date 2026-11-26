import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import { fullFriendCandidates, indexedFriendCandidates, canonicalFullFriendCandidates, canonicalIndexedFriendCandidates, friendshipCandidates, friendshipDuplicateGuard, malformedFriendProbe, malformedCanonicalFriendProbe } from '../friendship-candidates.js';

const migration = n => readFileSync(new URL(`../migrations/${n}`, import.meta.url), 'utf8');
const wrap = sql => ({prepare(q) { const stmt=sql.prepare(q); return {bind(...a) { return {all:async()=>({results:stmt.all(...a)})}; }}; }});
const duplicate=`(EXISTS (SELECT 1 FROM json_each(d.document_json,'$.participants') WHERE value=?) AND EXISTS (SELECT 1 FROM json_each(d.document_json,'$.participants') WHERE value=?)) OR (EXISTS (SELECT 1 FROM json_each(d.document_json,'$.fields.participants.arrayValue.values') WHERE json_extract(value,'$.stringValue')=?) AND EXISTS (SELECT 1 FROM json_each(d.document_json,'$.fields.participants.arrayValue.values') WHERE json_extract(value,'$.stringValue')=?))`;
const pair=`(json_array_length(d.document_json,'$.participants')=2 AND ((json_extract(d.document_json,'$.participants[0]')=? AND json_extract(d.document_json,'$.participants[1]')=?) OR (json_extract(d.document_json,'$.participants[0]')=? AND json_extract(d.document_json,'$.participants[1]')=?))) OR (json_array_length(d.document_json,'$.fields.participants.arrayValue.values')=2 AND ((json_extract(d.document_json,'$.fields.participants.arrayValue.values[0].stringValue')=? AND json_extract(d.document_json,'$.fields.participants.arrayValue.values[1].stringValue')=?) OR (json_extract(d.document_json,'$.fields.participants.arrayValue.values[0].stringValue')=? AND json_extract(d.document_json,'$.fields.participants.arrayValue.values[1].stringValue')=?)))`;

test('indexed candidates preserve both friendship guard predicates and shadowing', async () => {
  const sql=new DatabaseSync(':memory:');
  for(const n of ['0011_additive_app_documents.sql','0014_additive_firestore_documents.sql','0027_friend_participant_indexes.sql','0084_friend_guard_exceptions.sql','0105_canonical_wrapped_friend_indexes.sql']) sql.exec(migration(n));
  const c=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
  const l=sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)');
  const native=[[],['alice'],['alice','bob'],['bob','alice'],['eve','alice','bob'],['bob','eve','alice'],['eve','mallory'],{x:'alice',y:'bob'},'alice',null];
  const wrapped=arr=>({fields:{participants:{arrayValue:{values:arr.map(x=>({stringValue:x}))}}}});
  const docs=[...native.map(participants=>({participants})),wrapped(['alice','bob']),wrapped(['eve','alice','bob']),{participants:['alice','bob'],...wrapped(['eve','mallory'])},{fields:{},participants:['alice','bob']}];
  for(let i=0;i<docs.length;i++) {
    const json=JSON.stringify(docs[i]);
    c.run(`friends/c${i}`,'friends','friends',`c${i}`,'',json,0,0);
    l.run(`friends/l${i}`,'friends',`l${i}`,'',json,0);
  }
  // A stale matching legacy mirror must never escape a non-friend canonical row.
  c.run('friends/shadow','friends','friends','shadow','',JSON.stringify({participants:['eve','mallory']}),0,0);
  l.run('friends/shadow','friends','shadow','',JSON.stringify({participants:['alice','bob']}),0);
  // Legacy copies can also be shadowed by a canonical path in another collection.
  c.run('friends/elsewhere','not-friends','not-friends','elsewhere','',JSON.stringify({}),0,0);
  l.run('friends/elsewhere','friends','elsewhere','',JSON.stringify({participants:['alice','bob']}),0);
  const selection=await friendshipCandidates(wrap(sql),'alice',true);
  assert.equal(selection.sql,indexedFriendCandidates);
  for(const [predicate,args] of [[duplicate,['alice','bob','alice','bob']],[pair,['alice','bob','bob','alice','alice','bob','bob','alice']]]) {
    const read=(candidates,bind)=>sql.prepare(`SELECT d.path FROM (${candidates}) d WHERE ${predicate} ORDER BY d.path`).all(...bind,...args).map(r=>r.path);
    assert.deepEqual(read(indexedFriendCandidates,selection.args),read(fullFriendCandidates,[]));
    assert.ok(!read(indexedFriendCandidates,selection.args).includes('friends/shadow'));
  }
  // Updates and deletes maintain the indexes before a transactional guard runs.
  c.run('friends/race','friends','friends','race','',JSON.stringify({participants:['eve','mallory']}),0,0);
  sql.prepare('UPDATE friendbook_cf_firestore_documents SET document_json=? WHERE path=?').run(JSON.stringify({participants:['eve','alice','bob']}),'friends/race');
  assert.ok(sql.prepare(`SELECT path FROM (${indexedFriendCandidates}) WHERE path='friends/race'`).get(...selection.args));
  sql.prepare("DELETE FROM friendbook_cf_firestore_documents WHERE path='friends/race'").run();
  assert.equal(sql.prepare(`SELECT path FROM (${indexedFriendCandidates}) WHERE path='friends/race'`).get(...selection.args),undefined);
  const plan=sql.prepare(`EXPLAIN QUERY PLAN SELECT * FROM (${indexedFriendCandidates})`).all(...selection.args).map(r=>r.detail).join('\n');
  for(const name of ['idx_cf_friend_participant_0','idx_cf_friend_participant_1','idx_cf_friend_wrapped_participant_0','idx_cf_friend_wrapped_participant_1','idx_cf_friend_guard_exceptions_v2','idx_cf_legacy_friend_participant_0','idx_cf_legacy_friend_participant_1','idx_cf_legacy_friend_guard_exceptions']) assert.match(plan,new RegExp(name));
  sql.close();
});

test('malformed-row short-circuit mismatch uses transactional full-scan fallback', () => {
  // The real duplicate guard is EXISTS, not a full materialized SELECT. A
  // matching pair can hide an invalid wrapped element when the indexed UNION
  // visits rows in a different order. Do not treat this prototype as equivalent.
  const sql=new DatabaseSync(':memory:');
  for(const n of ['0011_additive_app_documents.sql','0014_additive_firestore_documents.sql','0027_friend_participant_indexes.sql','0084_friend_guard_exceptions.sql']) sql.exec(migration(n));
  const c=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
  c.run('friends/z','friends','friends','z','',JSON.stringify({fields:{participants:{arrayValue:{values:['non-json']}}}}),0,0);
  c.run('friends/a','friends','friends','a','',JSON.stringify({participants:['alice','bob']}),0,0);
  const guard=candidates=>`SELECT EXISTS(SELECT 1 FROM (${candidates}) d WHERE ${duplicate}) AS found`;
  assert.throws(()=>sql.prepare(guard(fullFriendCandidates)).get('alice','bob','alice','bob'),/malformed JSON/);
  assert.equal(sql.prepare(guard(indexedFriendCandidates)).get('alice','alice','alice','alice','alice','alice','alice','bob','alice','bob').found,1);
  // The transactional guard must fall back to the original scan, including its
  // historical error behavior, rather than silently changing the outcome.
  const safe=friendshipDuplicateGuard({sql:indexedFriendCandidates,args:['alice','alice','alice','alice','alice','alice']},'alice','bob');
  assert.equal(sql.prepare(`SELECT ${malformedFriendProbe} AS dangerous`).get().dangerous,1);
  assert.throws(()=>sql.prepare(`SELECT ${safe.sql} AS allowed`).get(...safe.args),/malformed JSON/);
  sql.close();
});

test('transactional guard matches original across mixed rows, malformed envelopes and shadows', () => {
  const sql=new DatabaseSync(':memory:');
  for(const n of ['0011_additive_app_documents.sql','0014_additive_firestore_documents.sql','0027_friend_participant_indexes.sql','0084_friend_guard_exceptions.sql']) sql.exec(migration(n));
  const c=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
  const l=sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)');
  const full=friendshipDuplicateGuard({sql:fullFriendCandidates,args:[]},'alice','bob');
  const fast=friendshipDuplicateGuard({sql:indexedFriendCandidates,args:['alice','alice','alice','alice']},'alice','bob');
  const run=g=>{try{return {value:sql.prepare(`SELECT ${g.sql} AS allowed`).get(...g.args).allowed};}catch(e){return {error:/malformed JSON/i.test(String(e))?'malformed JSON':String(e)};}};
  const runPair=(candidates,args,aggregate)=>{try{
    const projection=aggregate?'count(*) AS n':'path';
    const order=aggregate?'':' ORDER BY path LIMIT 1';
    return {value:sql.prepare(`SELECT ${projection} FROM (${candidates}) d WHERE ${pair}${order}`).get(...args,'alice','bob','bob','alice','alice','bob','bob','alice')||null};
  }catch(e){return {error:/malformed JSON/i.test(String(e))?'malformed JSON':String(e)};}};
  let state=0xa51c49d;const rand=n=>{state^=state<<13;state^=state>>>17;state^=state<<5;return (state>>>0)%n};
  const values=['alice','bob','eve','non-json','"valid-json"','123','null',null,0,true,{},['bob','alice']];
  const sample=()=>values[rand(values.length)];
  const arr=()=>Array.from({length:rand(6)},sample);
  const doc=()=>{const out={};if(rand(2))out.participants=rand(3)?arr():{a:sample(),b:sample()};
    if(rand(2))out.fields={participants:{arrayValue:{values:rand(5)?arr():sample()}}};return out;};
  for(let round=0;round<1800;round++){
    sql.exec('DELETE FROM friendbook_cf_firestore_documents;DELETE FROM friendbook_cf_documents');
    const docs=[];
    for(let i=0;i<1+rand(8);i++){
      const id=`r${i}`,path=`friends/${id}`,a=doc(),b=doc(),mode=rand(4);
      if(mode!==1)c.run(path,mode===3?'other':'friends','friends',id,'',JSON.stringify(a),0,0);
      if(mode!==0)l.run(path,'friends',id,'',JSON.stringify(b),0);
      docs.push({mode,a,b});
    }
    assert.deepEqual(run(fast),run(full),`duplicate case ${round}: ${JSON.stringify(docs)}`);
    for(const aggregate of [false,true]) assert.deepEqual(runPair(indexedFriendCandidates,fast.args.slice(4,10),aggregate),
      runPair(fullFriendCandidates,[],aggregate),`pair case ${round}: ${JSON.stringify(docs)}`);
  }
  const plan=sql.prepare(`EXPLAIN QUERY PLAN SELECT ${malformedFriendProbe}`).all().map(x=>x.detail).join('\n');
  assert.match(plan,/idx_cf_friend_guard_exceptions_v2/);
  assert.match(plan,/idx_cf_legacy_friend_guard_exceptions/);
  // Invalid wrapped text behind a canonical shadow must not force a legacy leak.
  sql.exec('DELETE FROM friendbook_cf_firestore_documents;DELETE FROM friendbook_cf_documents');
  c.run('friends/s','friends','friends','s','',JSON.stringify({participants:['eve','mallory']}),0,0);
  l.run('friends/s','friends','s','',JSON.stringify({fields:{participants:{arrayValue:{values:['not-json']}}}}),0);
  assert.equal(sql.prepare(`SELECT ${malformedFriendProbe} AS dangerous`).get().dangerous,0);
  assert.deepEqual(run(fast),run(full));
  sql.close();
});

test('missing additive indexes fall back, unrelated errors propagate', async () => {
  const sql=new DatabaseSync(':memory:');
  for(const n of ['0011_additive_app_documents.sql','0014_additive_firestore_documents.sql']) sql.exec(migration(n));
  assert.deepEqual(await friendshipCandidates({prepare(){throw new Error('must not probe');}},'alice'),{sql:fullFriendCandidates,args:[]});
  assert.deepEqual(await friendshipCandidates(wrap(sql),'alice',true),{sql:fullFriendCandidates,args:[]});
  await assert.rejects(friendshipCandidates({prepare(){throw new Error('database unavailable');}},'alice',true),/database unavailable/);
  sql.close();
});

test('candidate predicate matches historical shape permutations and shadow races', async () => {
  const sql=new DatabaseSync(':memory:');
  for(const n of ['0011_additive_app_documents.sql','0014_additive_firestore_documents.sql','0027_friend_participant_indexes.sql','0084_friend_guard_exceptions.sql']) sql.exec(migration(n));
  const canonical=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
  const legacy=sql.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)');
  const clear=()=>sql.exec('DELETE FROM friendbook_cf_firestore_documents; DELETE FROM friendbook_cf_documents');
  let seed=1234567; const rand=n=>{seed=(Math.imul(seed,1664525)+1013904223)>>>0;return seed%n;};
  const values=['alice','bob','eve',null,0,true,{},['alice','bob']];
  const sample=()=>values[rand(values.length)];
  const array=()=>Array.from({length:rand(6)},sample);
  const fields=()=>({participants:{arrayValue:{values:Array.from({length:rand(6)},()=>rand(4)?{stringValue:sample()}:sample())}}});
  const document=()=>{const d={};switch(rand(5)){
    case 0:d.participants=array();break;
    case 1:d.participants={first:sample(),second:sample(),third:sample()};break;
    case 2:d.participants=sample();break;
    case 3:d.fields=fields();break;
    case 4:d.participants=array();d.fields=fields();break;
  }return d;};
  const capture=(query,args)=>{try{return {rows:sql.prepare(query).all(...args).map(r=>r.path)};}catch(e){return {error:String(e).includes('malformed JSON')?'malformed JSON':String(e)};}};
  for(let i=0;i<1200;i++){
    clear();
    const d=document(), shadow=document(), path='friends/test';
    const mode=i%4;
    if(mode!==1)canonical.run(path,mode===3?'other':'friends','friends','test','',JSON.stringify(d),0,0);
    if(mode!==0)legacy.run(path,'friends','test','',JSON.stringify(mode===1?d:shadow),0);
    for(const [predicate,args] of [[duplicate,['alice','bob','alice','bob']],[pair,['alice','bob','bob','alice','alice','bob','bob','alice']]]){
      const query=s=>`SELECT d.path FROM (${s}) d WHERE ${predicate} ORDER BY d.path`;
      const expected=capture(query(fullFriendCandidates),args);
      const actual=capture(query(indexedFriendCandidates),['alice','alice','alice','alice','alice','alice',...args]);
      assert.deepEqual(actual,expected,`case ${i}: ${JSON.stringify({mode,d,shadow})}`);
    }
  }
  sql.close();
});
