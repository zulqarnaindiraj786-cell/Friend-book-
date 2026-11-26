import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {readFileSync,readdirSync} from 'node:fs';

test('canonical recipient prefix is redundant for reads and adds one insert index operation',()=>{
 const sql=new DatabaseSync(':memory:');
 try {
  const dir=new URL('../migrations/',import.meta.url),name='0093_remove_redundant_notification_recipient.sql';
  for(const f of readdirSync(dir).filter(f=>f.endsWith('.sql')&&f!==name&&f!=='0102_remove_legacy_notification_recipient.sql'&&f!=='0105_retire_legacy_document_mirror.sql'&&f!=='0106_enforce_document_mirror_retirement.sql'&&f!=='0109_archive_reappeared_document_mirror.sql').sort())sql.exec(readFileSync(new URL(f,dir),'utf8'));
  const insert='INSERT INTO friendbook_cf_firestore_documents VALUES(?,?,?,?,?,?,?,?)';
  const args=['notifications/new','notifications','notifications','new','sender',JSON.stringify({toUserId:'owner',isRead:false,createdAt:1}),1,1];
  const count=()=>sql.prepare('EXPLAIN '+insert).all(...args).filter(x=>x.opcode==='IdxInsert').length;
  const before=count();
  const q="SELECT path FROM friendbook_cf_firestore_documents INDEXED BY idx_cf_notif_page WHERE parent_path='notifications' AND json_extract(document_json,'$.toUserId')=?";
  const seed=sql.prepare(insert);
  for(let i=0;i<5000;i++)seed.run('notifications/n'+i,'notifications','notifications','n'+i,'sender',JSON.stringify({toUserId:i%100?'other':'owner',isRead:false,createdAt:i}),1,1);
  const expected=sql.prepare(q).all('owner');
  const migration=readFileSync(new URL(name,dir),'utf8');sql.exec(migration);sql.exec(migration);
  assert.equal(count(),before-1,'one fewer index-maintenance opcode; not Cloudflare billing evidence');
  assert.deepEqual(sql.prepare(q).all('owner'),expected);
  const plan=sql.prepare('EXPLAIN QUERY PLAN '+q).all('owner').map(x=>x.detail).join();
  assert.match(plan,/SEARCH .*idx_cf_notif_page.*<expr>=\?/);assert.doesNotMatch(plan,/SCAN/);
  for(const name of ['idx_cf_notif_page','idx_cf_notif_unread','idx_cf_notif_exceptions','idx_cf_legacy_inbox_recipient'])
   assert.ok(sql.prepare("SELECT name FROM sqlite_master WHERE type='index' AND name=?").get(name));
 } finally {sql.close();}
});
