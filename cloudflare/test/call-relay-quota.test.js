import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {readFileSync} from 'node:fs';
import {CallRelay} from '../call-relay.js';

function fixture(){
 const sql=new DatabaseSync(':memory:');
 for(const n of ['0011_additive_app_documents.sql','0014_additive_firestore_documents.sql','0048_direct_chat_calls.sql'])sql.exec(readFileSync(new URL(`../migrations/${n}`,import.meta.url),'utf8'));
 const insert=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
 const put=(path,data)=>{const [coll,id]=path.split('/');insert.run(path,coll,coll,id,id,JSON.stringify(data),1,1);};
 put('users/alice',{displayName:'Alice'});put('users/bob',{displayName:'Bob'});
 put('chats/room',{participants:['alice','bob'],activeCall:{callId:'call',status:'connected',callType:'video'}});
 sql.prepare('INSERT INTO friendbook_cf_chat_calls(chat_id,call_id,status,expires_at) VALUES (?,?,?,?)').run('room','call','connected',Date.now()+60_000);
 const reads=[];
 const db={prepare(q){const s=sql.prepare(q);return {bind(...a){return {first:async()=>{reads.push(a[0]);return s.get(...a)||null;}};}};}};
 return {sql,reads,relay:new CallRelay({}, {DB:db})};
}

test('call rechecks each account once per renewal, without weakening revocation',async t=>{
 const f=fixture();t.after(()=>f.sql.close());const allow=uid=>f.relay.allowed('room','call',uid);
 assert.equal(await allow('alice'),'video');
 assert.equal(f.reads.filter(p=>p==='users/alice').length,1);
 assert.equal(f.reads.filter(p=>p==='users/bob').length,1);
 assert.equal(f.reads.length,4,'call ledger + room + both profiles');
 assert.equal(await allow('mallory'),null);
 const update=f.sql.prepare('UPDATE friendbook_cf_firestore_documents SET document_json=? WHERE path=?');
 for(const field of [{banned:true},{deactivated:true},{suspendedLifetime:true},{suspendedUntil:Date.now()+60000},{blocked:['bob']}]) {
   update.run(JSON.stringify(field),'users/alice');assert.equal(await allow('alice'),null,JSON.stringify(field));
 }
 update.run('{}','users/alice');update.run(JSON.stringify({blockedUsers:['alice']}),'users/bob');
 assert.equal(await allow('alice'),null,'peer block takes effect on next recheck');
 update.run('{}','users/bob');assert.equal(await allow('alice'),'video','authorization recovers after block removal');
 update.run(JSON.stringify({fields:{suspendedUntil:{integerValue:String(Date.now()+60000)}}}),'users/alice');
 assert.equal(await allow('alice'),null,'legacy wrapped suspension is enforced');
 update.run('{}','users/alice');
 f.sql.prepare("DELETE FROM friendbook_cf_firestore_documents WHERE path='users/bob'").run();
 assert.equal(await allow('alice'),null,'missing peer profile fails closed');
 f.sql.prepare("INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)").run('users/bob','users','bob','bob',JSON.stringify({fields:{banned:{booleanValue:true}}}),1);
 assert.equal(await allow('alice'),null,'legacy blocked account is rejected');
 f.sql.prepare("UPDATE friendbook_cf_documents SET document_json='{}' WHERE path='users/bob'").run();
 assert.equal(await allow('alice'),null,'a mirror-only peer stays invisible even when active');
 f.sql.prepare("INSERT INTO friendbook_cf_firestore_documents VALUES ('users/bob','users','users','bob','bob','{}',1,1)").run();
 assert.equal(await allow('alice'),'video','canonical peer keeps working');
 f.sql.prepare("UPDATE friendbook_cf_chat_calls SET status='ended' WHERE call_id='call'").run();
 assert.equal(await allow('alice'),null,'ended call rejected immediately on recheck');
});
