import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {readFileSync,readdirSync} from 'node:fs';

const dir=new URL('../migrations/',import.meta.url);
const removal='0089_remove_redundant_global_owner_index.sql';
test('global owner index removal preserves path authorization and scoped live throttles',()=>{
  const db=new DatabaseSync(':memory:');
  try {
    for(const name of readdirSync(dir).filter(n=>n.endsWith('.sql')&&n!==removal).sort()) db.exec(readFileSync(new URL(name,dir),'utf8'));
    const insert=db.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
    for(let i=0;i<300;i++) insert.run(`lives/${i%3}/chat/m${i}`,`lives/${i%3}/chat`,'chat',`m${i}`,i%2?'alice':'bob',JSON.stringify({type:i%2?'chat':'gift'}),i,i);
    const queries=[
      ["SELECT path FROM friendbook_cf_firestore_documents WHERE collection_id='chat' AND parent_path=? AND owner_uid=? AND create_time>? AND json_extract(document_json,'$.type')='gift'",['lives/0/chat','bob',100]],
      ["SELECT path FROM friendbook_cf_firestore_documents WHERE collection_id='chat' AND parent_path=? AND owner_uid=? AND create_time>? AND json_extract(document_json,'$.type') IN ('chat','qa')",['lives/0/chat','alice',100]],
      ['SELECT owner_uid,document_json FROM friendbook_cf_firestore_documents WHERE path=?',['lives/0/chat/m0']],
      ["SELECT path FROM friendbook_cf_firestore_documents WHERE collection_id='chats' AND parent_path='chats' ORDER BY update_time DESC LIMIT ?",[20]],
    ];
    const suite=()=>queries.map(([q,args])=>({rows:db.prepare(q).all(...args),plan:db.prepare('EXPLAIN QUERY PLAN '+q).all(...args).map(r=>r.detail)}));
    const before=suite();
    for(const check of before.slice(0,2)) assert.ok(check.plan.some(p=>p.includes('idx_cf_live_chat_sender_time')&&p.includes('owner_uid=?')&&p.includes('create_time>?')));
    assert.ok(before[3].plan.some(p=>p.includes('idx_friendbook_cf_firestore_collection_updated')));
    const q="INSERT INTO friendbook_cf_firestore_documents VALUES ('notifications/probe','notifications','notifications','probe','alice','{}',1,1)";
    const inserts=()=>db.prepare('EXPLAIN '+q).all().filter(r=>r.opcode==='IdxInsert').length;
    const oldInserts=inserts();
    db.exec(readFileSync(new URL(removal,dir),'utf8'));
    db.exec(readFileSync(new URL(removal,dir),'utf8'));
    assert.equal(db.prepare("SELECT name FROM sqlite_schema WHERE name='idx_friendbook_cf_firestore_owner'").get(),undefined);
    assert.deepEqual(suite(),before);
    assert.equal(inserts(),oldInserts-1,'one fewer index insert opcode; not a D1 billing measurement');
    assert.equal(db.prepare('SELECT count(*) AS n FROM friendbook_cf_firestore_documents').get().n,300);
    db.exec('CREATE INDEX idx_friendbook_cf_firestore_owner ON friendbook_cf_firestore_documents(owner_uid)');
    assert.deepEqual(suite(),before);
  } finally { db.close(); }
});
