import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {readFileSync} from 'node:fs';
import {createWorker} from '../../worker.js';

test('read notification exceptions do not force an unread inbox scan; unread exceptions remain visible',async t=>{
  const sql=new DatabaseSync(':memory:');t.after(()=>sql.close());
  for(const name of ['0003_additive_interactions.sql','0011_additive_app_documents.sql','0014_additive_firestore_documents.sql','0055_notification_page_indexes.sql'])
    sql.exec(readFileSync(new URL('../migrations/'+name,import.meta.url),'utf8'));
  const add=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES(?,?,?,?,?,?,?,?)');
  add.run('users/bob','users','users','bob','bob',JSON.stringify({uid:'bob'}),1,1);
  for(let i=0;i<600;i++){
    const id='n'+String(i).padStart(4,'0');
    add.run('notifications/'+id,'notifications','notifications',id,'alice',JSON.stringify({fromUserId:'alice',toUserId:'bob',isRead:false,createdAt:i+1}),1,1);
  }
  add.run('notifications/old','notifications','notifications','old','alice',JSON.stringify({fromUserId:'alice',toUserId:'bob',isRead:true,createdAt:'2020-01-01'}),1,1);
  let inboxRows=0;
  const db={prepare(q){const stmt=sql.prepare(q);return {bind(...args){return{
    first:async()=>stmt.get(...args)||null,
    all:async()=>{const results=stmt.all(...args);if(q.includes("parent_path='notifications'"))inboxRows+=results.length;return {results};}
  };}};}};
  const worker=createWorker(async()=> 'bob'),env={DB:db,FIREBASE_PROJECT_ID:'test'};
  const query=async(reference=false)=>{
    const filters=[{field:'toUserId',op:'==',value:'bob'},{field:'isRead',op:'==',value:false}];
    if(reference)filters.push({field:'fromUserId',op:'==',value:'alice'});
    const response=await worker.fetch(new Request('https://local.invalid/v1/firestore/query',{
      method:'POST',headers:{authorization:'Bearer bob','content-type':'application/json'},
      body:JSON.stringify({parent:'notifications',filters,orderBy:[{field:'createdAt',direction:'DESCENDING'}],limit:20})}),env);
    assert.equal(response.status,200);return (await response.json()).documents.map(d=>d.id);
  };
  const fast=await query();
  assert.equal(inboxRows,20,'only requested unread page is loaded, not all 600 rows');
  assert.deepEqual(fast,await query(true),'same result as complete compatibility path');
  sql.prepare("UPDATE friendbook_cf_firestore_documents SET document_json=json_set(document_json,'$.isRead',json('false')) WHERE path='notifications/old'").run();
  inboxRows=0;
  const withUnreadException=await query();
  assert.ok(inboxRows>20,'an actual unread exception retains the complete compatibility path');
  assert.deepEqual(withUnreadException,await query(true));
  assert.ok(withUnreadException.includes('old'),'unread historical timestamp must not disappear');
});
