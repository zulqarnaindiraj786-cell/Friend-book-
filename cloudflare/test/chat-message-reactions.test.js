import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import { createWorker } from '../../worker.js';

function fixture() {
  const sql=new DatabaseSync(':memory:');
  for (const file of ['0003_additive_interactions.sql','0011_additive_app_documents.sql','0014_additive_firestore_documents.sql','0017_sync_firestore_app_documents.sql','0018_social_guards.sql','0019_trusted_friendships.sql'])
    sql.exec(readFileSync(new URL(`../migrations/${file}`,import.meta.url),'utf8'));
  const insert=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES(?,?,?,?,?,?,?,?)');
  const room=(id,participants)=>insert.run(`chats/${id}`,'chats','chats',id,'alice',JSON.stringify({participants}),1,1);
  const message=(id)=>insert.run(`chats/${id}/messages/m` ,`chats/${id}/messages`,'messages','m','alice',JSON.stringify({senderId:'alice',text:'hello',reactions:{alice:'👍'},reactorNames:{alice:'Alice'}}),1,1);
  room('direct',['alice','bob']);message('direct');room('team_bob',['team','bob']);message('team_bob');
  let race;
  const db={prepare(q){const s=sql.prepare(q);return{bind(...args){return{run:async()=>s.run(...args),first:async()=>s.get(...args)||null,all:async()=>({results:s.all(...args)})};}}},
    async batch(stmts){if(race){const f=race;race=null;f();}sql.exec('BEGIN');try{for(const s of stmts)await s.run();sql.exec('COMMIT');}catch(e){sql.exec('ROLLBACK');throw e;}}};
  const app=createWorker(async token=>token), env={DB:db,FIREBASE_PROJECT_ID:'test'};
  const patch=(uid,id,fields)=>app.fetch(new Request(`https://example.test/v1/firestore/document?path=chats/${id}/messages/m`,{method:'PATCH',headers:{authorization:`Bearer ${uid}`,'content-type':'application/json'},body:JSON.stringify({fields})}),env);
  const batch=(uid,id,updates)=>app.fetch(new Request('https://example.test/v1/firestore/batch',{method:'POST',headers:{authorization:`Bearer ${uid}`,'content-type':'application/json'},body:JSON.stringify({operations:[{op:'update',path:`chats/${id}/messages/m`,updates}]})}),env);
  const doc=id=>JSON.parse(sql.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?').get(`chats/${id}/messages/m`).document_json);
  return{sql,patch,batch,doc,race:f=>{race=f}};
}

test('members add, change and remove only their own message reactions',async()=>{
  const f=fixture();
  assert.equal((await f.patch('bob','direct',{'reactions.bob':'❤️','reactorNames.bob':'Bob','reactorPhotos.bob':'https://example.test/bob'})).status,200);
  assert.deepEqual(f.doc('direct').reactions,{alice:'👍',bob:'❤️'});
  assert.equal((await f.batch('bob','direct',{'reactions.bob':'😂'})).status,200);
  assert.equal(f.doc('direct').reactions.bob,'😂');
  for(const [actor,fields] of [
    ['eve',{'reactions.eve':'👍'}],['bob',{'reactions.alice':'😡'}],['bob',{'reactions':{bob:'👍'}}],
    ['bob',{'reactions.bob':'👍',text:'tampered'}],['alice',{'reactions.bob':'😡'}],
    ['bob',{'reactorNames.alice':'Impostor'}],['bob',{'reactions.bob':{bad:true}}]
  ]) assert.equal((await f.patch(actor,'direct',fields)).status,403,JSON.stringify(fields));
  const del={__fieldValue:'delete'};
  assert.equal((await f.patch('bob','direct',{'reactions.bob':del,'reactorNames.bob':del,'reactorPhotos.bob':del})).status,200);
  assert.deepEqual(f.doc('direct').reactions,{alice:'👍'});
  assert.equal((await f.patch('bob','team_bob',{'reactions.bob':'👍'})).status,200);
  assert.equal((await f.patch('bob','team_bob',{text:'tamper'})).status,403);
  f.sql.close();
});

test('membership and message races roll back a reaction',async()=>{
  for(const kind of ['membership','message']){
    const f=fixture();f.race(()=>{
      if(kind==='membership')f.sql.prepare("UPDATE friendbook_cf_firestore_documents SET document_json=? WHERE path='chats/direct'").run(JSON.stringify({participants:['alice','eve']}));
      else f.sql.prepare("UPDATE friendbook_cf_firestore_documents SET document_json=json_set(document_json,'$.text','changed') WHERE path='chats/direct/messages/m'").run();
    });
    assert.notEqual((await f.patch('bob','direct',{'reactions.bob':'👍'})).status,200);
    assert.equal(f.doc('direct').reactions.bob,undefined);
    assert.equal(f.sql.prepare('SELECT count(*) AS n FROM friendbook_cf_social_guards').get().n,0);
    f.sql.close();
  }
});
