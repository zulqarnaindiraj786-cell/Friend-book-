import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {readFileSync,readdirSync} from 'node:fs';
import {firestoreDocuments} from '../firestore-documents.js';

test('notification page merges a bounded exceptional timestamp without loading the full inbox',async t=>{
  const sql=new DatabaseSync(':memory:');t.after(()=>sql.close());
  const dir=new URL('../migrations/',import.meta.url);
  for(const file of readdirSync(dir).filter(f=>f.endsWith('.sql')&&!['0105_retire_legacy_document_mirror.sql','0106_enforce_document_mirror_retirement.sql','0109_archive_reappeared_document_mirror.sql'].includes(f)).sort())
    sql.exec(readFileSync(new URL(file,dir),'utf8'));
  const insert=sql.prepare('INSERT INTO friendbook_cf_firestore_documents(path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time) VALUES(?,?,?,?,?,?,?,?)');
  for(let i=0;i<500;i++){
    const id=`n${String(i).padStart(4,'0')}`;
    const recipient=i%2?'alice':'bob';
    insert.run(`notifications/${id}`,'notifications','notifications',id,'author',JSON.stringify({toUserId:recipient,fromUserId:'author',type:'like',createdAt:i+1,isRead:false}),i+1,i+1);
  }
  insert.run('notifications/string-time','notifications','notifications','string-time','author',JSON.stringify({toUserId:'bob',fromUserId:'author',type:'like',createdAt:'zz-latest',isRead:false}),1000,1000);
  const calls=[];
  const db={prepare(query){const stmt=sql.prepare(query);return{bind(...args){return{
    first:async()=>{calls.push(query);return stmt.get(...args)||null;},
    all:async()=>{calls.push(query);return{results:stmt.all(...args)};},
    run:async()=>stmt.run(...args),
  };}};}};
  const response=await firestoreDocuments(new Request('https://example.test/v1/firestore/query',{
    method:'POST',headers:{'content-type':'application/json'},
    body:JSON.stringify({parent:'notifications',filters:[{field:'toUserId',op:'==',value:'bob'}],orderBy:[{field:'createdAt',direction:'DESCENDING'}],limit:20})
  }),{DB:db},{uid:'bob'});
  assert.equal(response.status,200);
  const page=await response.json();
  assert.equal(page.documents.length,20);
  assert.equal(page.documents[0].id,'string-time');
  assert.ok(calls.some(query=>query.includes('INDEXED BY idx_cf_notif_exceptions')));
  assert.ok(calls.some(query=>query.includes("json_extract(a.document_json,'$.toUserId')=?")));
  assert.ok(!calls.some(query=>query.includes('FROM friendbook_cf_firestore_documents AS quota_root_notifications_page')),
    'exceptional rows do not force the generic full-inbox scan');
});
