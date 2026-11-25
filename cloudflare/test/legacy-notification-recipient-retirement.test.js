import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {readFileSync,readdirSync} from 'node:fs';

test('legacy recipient prefix removal retains plain and wrapped seeks, rollback and notification data',()=>{
 const sql=new DatabaseSync(':memory:');
 try {
  const dir=new URL('../migrations/',import.meta.url),name='0102_remove_legacy_notification_recipient.sql';
  for(const f of readdirSync(dir).filter(f=>f.endsWith('.sql')&&f!==name&&f!=='0105_retire_legacy_document_mirror.sql').sort())sql.exec(readFileSync(new URL(f,dir),'utf8'));
  const insert='INSERT INTO friendbook_cf_documents VALUES(?,?,?,?,?,?)';
  const args=['notifications/new','notifications','new','author',JSON.stringify({toUserId:'viewer',isRead:false,createdAt:1}),1];
  const count=()=>sql.prepare('EXPLAIN '+insert).all(...args).filter(row=>row.opcode==='IdxInsert').length;
  const before=count(),seed=sql.prepare(insert);
  for(let i=0;i<5000;i++){
   const id=`n${i}`,recipient=i%17===0?'viewer':'other';
   const json=i%2?{toUserId:recipient,isRead:i%3===0,createdAt:i}:{fields:{toUserId:{stringValue:recipient},isRead:{booleanValue:i%3===0},createdAt:{integerValue:String(i)}}};
   seed.run(`notifications/${id}`,'notifications',id,'author',JSON.stringify(json),i);
  }
  const expr="CASE WHEN json_type(document_json,'$.fields')='object' THEN json_extract(document_json,'$.fields.toUserId.stringValue') ELSE json_extract(document_json,'$.toUserId') END";
  const old=`SELECT path FROM friendbook_cf_documents INDEXED BY idx_cf_legacy_inbox_recipient WHERE collection='notifications' AND (${expr})=? ORDER BY path`;
  const replacement=old.replace('idx_cf_legacy_inbox_recipient','idx_cf_legacy_notif_page');
  const expected=sql.prepare(old).all('viewer');assert.deepEqual(sql.prepare(replacement).all('viewer'),expected);
  assert.ok(expected.length>100);
  const migration=readFileSync(new URL(name,dir),'utf8');sql.exec(migration);sql.exec(migration);
  assert.equal(count(),before-1,'one fewer legacy index insert opcode, not a D1 billing measurement');
  assert.deepEqual(sql.prepare(replacement).all('viewer'),expected);
  const plan=sql.prepare('EXPLAIN QUERY PLAN '+replacement).all('viewer').map(row=>row.detail).join('\n');
  assert.match(plan,/SEARCH .*idx_cf_legacy_notif_page.*<expr>=\?/);assert.doesNotMatch(plan,/SCAN friendbook_cf_documents/);
  for(const n of ['idx_cf_legacy_notif_page','idx_cf_legacy_notif_unread','idx_cf_legacy_notif_exceptions','idx_cf_legacy_inbox_unread','idx_cf_notif_page'])
   assert.ok(sql.prepare("SELECT name FROM sqlite_master WHERE type='index' AND name=?").get(n));
  assert.equal(sql.prepare("SELECT count(*) n FROM friendbook_cf_documents WHERE collection='notifications'").get().n,5000);
  sql.exec(readFileSync(new URL('0025_inbox_recipient_indexes.sql',dir),'utf8'));
  assert.deepEqual(sql.prepare(old).all('viewer'),expected,'rollback recreation retains the same results');
  assert.equal(count(),before);
 } finally {sql.close();}
});
