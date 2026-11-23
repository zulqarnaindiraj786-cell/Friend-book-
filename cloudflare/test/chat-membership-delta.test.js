import {test} from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {readFileSync} from 'node:fs';

const migration=name=>readFileSync(new URL(`../migrations/${name}`,import.meta.url),'utf8');
const indexRows=db=>db.prepare('SELECT participant_uid,chat_path,source FROM friendbook_cf_chat_participants ORDER BY participant_uid,chat_path,source').all();
function database(){
  const db=new DatabaseSync(':memory:');
  for(const file of ['0011_additive_app_documents.sql','0014_additive_firestore_documents.sql','0040_chat_participant_lookup.sql','0058_skip_unchanged_chat_members.sql','0073_chat_membership_source_seek.sql'])db.exec(migration(file));
  return db;
}
function add(db,id,current,legacy=current){
  db.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)').run(`chats/${id}`,'chats','chats',id,'alice',JSON.stringify(current),1,1);
  db.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)').run(`chats/${id}`,'chats',id,'alice',JSON.stringify(legacy),1);
}
function update(db,id,doc,source){
  db.prepare(`UPDATE ${source?'friendbook_cf_firestore_documents':'friendbook_cf_documents'} SET document_json=? WHERE path=?`).run(JSON.stringify(doc),`chats/${id}`);
}
const members=db=>indexRows(db).map(x=>`${x.chat_path}:${x.source}:${x.participant_uid}`);

test('chat membership delta preserves both stores, edge cases and stale-index repair',t=>{
  const baseline=database(),candidate=database();t.after(()=>{baseline.close();candidate.close();});
  for(const db of [baseline,candidate]){
    add(db,'direct',{participants:['alice','bob']});
    add(db,'wrapped',{participants:['root'],fields:{participants:{arrayValue:{values:[{stringValue:'alice'},{stringValue:'bob'}]}}}});
    add(db,'hybrid',{participants:['alice','bob'],fields:{participants:{arrayValue:{values:[{stringValue:'alice'},{stringValue:'bob'}]}}}});
    add(db,'large',{participants:Array.from({length:100},(_,i)=>`u${i}`)});
  }
  const oldIndex=indexRows(candidate);candidate.exec(migration('0083_chat_membership_delta.sql'));candidate.exec(migration('0083_chat_membership_delta.sql'));
  assert.deepEqual(indexRows(candidate),oldIndex,'replacing triggers is idempotent and never rebuilds rows');
  const changes=db=>db.prepare('SELECT total_changes() n').get().n;
  let before=changes(candidate);
  for(const db of [baseline,candidate])update(db,'direct',{participants:['bob','alice']},1);
  assert.equal(changes(candidate)-before,1,'reordering two members writes no derived edges');
  assert.deepEqual(members(candidate),members(baseline));
  before=changes(candidate);
  for(const db of [baseline,candidate])update(db,'large',{participants:[...Array.from({length:100},(_,i)=>`u${i}`),'new']},1);
  assert.equal(changes(candidate)-before,2,'adding one member writes just one derived edge');
  assert.deepEqual(members(candidate),members(baseline));
  // Reordered documents can still repair stale/missing rows, never suppressing revocation.
  candidate.prepare("DELETE FROM friendbook_cf_chat_participants WHERE chat_path='chats/direct' AND source=1 AND participant_uid='bob'").run();
  candidate.prepare("INSERT INTO friendbook_cf_chat_participants VALUES ('mallory','chats/direct',1)").run();
  update(candidate,'direct',{participants:['alice','bob']},1);
  update(baseline,'direct',{participants:['alice','bob']},1);
  assert.deepEqual(members(candidate),members(baseline));
  candidate.prepare("DELETE FROM friendbook_cf_chat_participants WHERE chat_path='chats/direct' AND source=0 AND participant_uid='alice'").run();
  candidate.prepare("INSERT INTO friendbook_cf_chat_participants VALUES ('mallory','chats/direct',0)").run();
  update(candidate,'direct',{participants:['bob','alice']},0);
  update(baseline,'direct',{participants:['bob','alice']},0);
  assert.deepEqual(members(candidate),members(baseline),'legacy reordering repairs missing and stale edges');
  // Wrapped legacy documents use fields, not a conflicting top-level list.
  const wrapped={participants:['root','not-authoritative'],fields:{participants:{arrayValue:{values:[{stringValue:'carol'},{stringValue:'alice'}]}}}};
  for(const db of [baseline,candidate])update(db,'wrapped',wrapped,0);
  assert.deepEqual(members(candidate),members(baseline));
  const hybrid={participants:['bob','alice'],fields:{participants:{arrayValue:{values:[{stringValue:'alice'},{stringValue:'carol'}]}}}};
  for(const db of [baseline,candidate])update(db,'hybrid',hybrid,0);
  assert.deepEqual(members(candidate),members(baseline),'root reordering must not mask wrapped membership changes');
  let seed=8731;const rnd=n=>{seed=(Math.imul(seed,1664525)+1013904223)>>>0;return seed%n;};
  const samples=[[],['alice'],['bob','alice'],['alice','alice','bob'],[null,'alice',false,1,''],null,'not-array',[{stringValue:'bob'},'alice']];
  for(let i=0;i<120;i++){
    const source=rnd(2),values=samples[rnd(samples.length)];
    const doc=source===0 && rnd(2) ? {fields:{participants:{arrayValue:{values:Array.isArray(values)?values.map(v=>({stringValue:v})):[]}}}} : {participants:values};
    for(const db of [baseline,candidate])update(db,'wrapped',doc,source);
    assert.deepEqual(members(candidate),members(baseline),`iteration ${i}`);
  }
  // Duplicate old participant values must not fail under an outer document UPSERT.
  candidate.prepare(`INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(path) DO UPDATE SET document_json=excluded.document_json`)
    .run('chats/direct','chats','chats','direct','alice',JSON.stringify({participants:['alice','alice','bob']}),1,2);
  assert.deepEqual(candidate.prepare("SELECT participant_uid FROM friendbook_cf_chat_participants WHERE chat_path='chats/direct' AND source=1 ORDER BY participant_uid").all().map(x=>x.participant_uid),['alice','bob']);
  candidate.prepare("UPDATE friendbook_cf_firestore_documents SET path='chats/new-direct',doc_id='new-direct' WHERE path='chats/direct'").run();
  assert.equal(candidate.prepare("SELECT count(*) n FROM friendbook_cf_chat_participants WHERE chat_path='chats/direct' AND source=1").get().n,0);
  assert.equal(candidate.prepare("SELECT count(*) n FROM friendbook_cf_chat_participants WHERE chat_path='chats/new-direct' AND source=1").get().n,2);
  candidate.prepare("DELETE FROM friendbook_cf_firestore_documents WHERE path='chats/new-direct'").run();
  assert.equal(candidate.prepare("SELECT count(*) n FROM friendbook_cf_chat_participants WHERE chat_path='chats/new-direct' AND source=1").get().n,0);
});
