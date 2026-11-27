import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {readFileSync} from 'node:fs';
import {drainBroadcastPush} from '../fcm-push.js';

for(const failure of ['claim','discard','expired'])test(`broadcast ${failure} failure cannot abandon other due recipients`,async t=>{
 const sql=new DatabaseSync(':memory:');t.after(()=>sql.close());
 for(const name of ['0014_additive_firestore_documents.sql','0042_broadcast_push_outbox.sql'])
   sql.exec(readFileSync(new URL(`../migrations/${name}`,import.meta.url),'utf8'));
 const now=Date.now(),ids=Array.from({length:12},(_,i)=>`job${String(i).padStart(2,'0')}`);
 for(const id of ids){
   sql.prepare('INSERT INTO friendbook_cf_broadcast_push VALUES (?,?,?,?,?)').run(id,id,failure==='expired'&&id==='job00'?now-86400001:now,now,0);
   sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)').run(`notifications/${id}`,'notifications','notifications',id,'friendbook_team',JSON.stringify({fromUserId:'friendbook_team',toUserId:id,type:'announcement',text:'Hello'}),now,now);
 }
 sql.exec(`CREATE TRIGGER poison_job BEFORE ${failure==='claim'?'UPDATE':'DELETE'} ON friendbook_cf_broadcast_push
   WHEN OLD.notification_id='job00' BEGIN SELECT RAISE(ABORT,'isolated failure'); END`);
 const db={prepare(q){const stmt=sql.prepare(q);return{bind(...args){return{
   async first(){return stmt.get(...args)||null;},async all(){return{results:stmt.all(...args)};},async run(){return stmt.run(...args);}
 };}};}};
 const sent=[];
 await assert.doesNotReject(drainBroadcastPush({DB:db},now,async(_env,_db,_actor,target)=>{
   sent.push(target);return{status:200};
 }));
 assert.deepEqual(sent.filter(id=>id!=='job00').sort(),ids.slice(1),'all healthy recipients finish before drain resolves');
 assert.deepEqual(sql.prepare('SELECT notification_id FROM friendbook_cf_broadcast_push').all().map(r=>r.notification_id),['job00'],'failed job is retained, not silently dropped');
 sql.exec('DROP TRIGGER poison_job');
 await drainBroadcastPush({DB:db},now+300000,async(_env,_db,_actor,target)=>{sent.push(target);return{status:200};});
 assert.equal(sql.prepare('SELECT COUNT(*) n FROM friendbook_cf_broadcast_push').get().n,0,'failed job remains recoverable or expires normally');
});
