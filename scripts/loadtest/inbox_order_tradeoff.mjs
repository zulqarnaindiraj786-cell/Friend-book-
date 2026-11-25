// OFFLINE EXPERIMENT ONLY. No production migration, network or credential access.
// Compares candidate visits and local changed rows, NOT billed D1 row counters.
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {readFileSync,readdirSync} from 'node:fs';
import {createWorker} from '../../worker.js';
globalThis.fetch=async()=>{throw Error('Network disabled in inbox tradeoff experiment');};

function fixture(roomCount,memberCount,indexed) {
  const sql=new DatabaseSync(':memory:');
  const dir=new URL('../../cloudflare/migrations/',import.meta.url);
  for(const file of readdirSync(dir).filter(f=>f.endsWith('.sql')&&f!=='0105_retire_legacy_document_mirror.sql'&&f!=='0106_enforce_document_mirror_retirement.sql').sort())sql.exec(readFileSync(new URL(file,dir),'utf8'));
  if(indexed)sql.exec(`CREATE TABLE experimental_inbox_order(
    uid TEXT NOT NULL,activity REAL NOT NULL,doc_id TEXT NOT NULL,path TEXT NOT NULL,
    PRIMARY KEY(uid,activity DESC,doc_id DESC)) WITHOUT ROWID;
    CREATE INDEX experimental_inbox_path ON experimental_inbox_order(path);
    CREATE TRIGGER experimental_inbox_insert AFTER INSERT ON friendbook_cf_firestore_documents
    WHEN NEW.parent_path='chats' BEGIN
      INSERT INTO experimental_inbox_order
        SELECT value,json_extract(NEW.document_json,'$.lastMessageTime.millis'),NEW.doc_id,NEW.path
        FROM json_each(NEW.document_json,'$.participants');
    END;
    CREATE TRIGGER experimental_inbox_update AFTER UPDATE ON friendbook_cf_firestore_documents
    WHEN NEW.parent_path='chats' AND
      (json_extract(OLD.document_json,'$.lastMessageTime.millis') IS NOT json_extract(NEW.document_json,'$.lastMessageTime.millis')
       OR json_extract(OLD.document_json,'$.participants') IS NOT json_extract(NEW.document_json,'$.participants')) BEGIN
      DELETE FROM experimental_inbox_order WHERE path=OLD.path;
      INSERT INTO experimental_inbox_order
        SELECT value,json_extract(NEW.document_json,'$.lastMessageTime.millis'),NEW.doc_id,NEW.path
        FROM json_each(NEW.document_json,'$.participants');
    END;`);
  const people=['alice','bob',...Array.from({length:memberCount-2},(_,i)=>`member${i}`)];
  const insert=sql.prepare('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
  for(let i=0;i<roomCount;i++){
    const id=`room${String(i).padStart(5,'0')}`;
    const data={participants:people,lastMessageTime:{__type:'timestamp',millis:100000-i},lastMessage:'old'};
    insert.run(`chats/${id}`,'chats','chats',id,'alice',JSON.stringify(data),1,1);
  }
  let visits=0;
  sql.function('quota_inbox_visit',_path=>{visits++;return 1;});
  const db={
    prepare(q){
      const query=q.replace('WHERE m.participant_uid=? AND m.source=1','WHERE m.participant_uid=? AND m.source=1 AND quota_inbox_visit(f.path)');
      const stmt=sql.prepare(query);
      return {bind(...a){return {
        async first(){return stmt.get(...a)||null;},
        async all(){return {results:stmt.all(...a)};},
      };}};
    },
  };
  const app=createWorker(async()=> 'bob');
  return {sql,async currentPage(){visits=0;
    const r=await app.fetch(new Request('https://offline.invalid/v1/firestore/query',{method:'POST',headers:{authorization:'Bearer bob','content-type':'application/json'},body:JSON.stringify({parent:'chats',filters:[{field:'participants',op:'array-contains',value:'bob'}],orderBy:[{field:'inboxActivityAt',direction:'DESCENDING'}],limit:10})}),{DB:db,FIREBASE_PROJECT_ID:'offline'});
    assert.equal(r.status,200);return {ids:(await r.json()).documents.map(d=>d.id),visits};
  },rankedPage(){visits=0;const query=`SELECT f.doc_id FROM experimental_inbox_order c
    JOIN friendbook_cf_firestore_documents f ON f.path=c.path
    WHERE c.uid=? AND quota_inbox_visit(f.path) ORDER BY c.activity DESC,c.doc_id DESC LIMIT ?`;
    const ids=sql.prepare(query).all('bob',10).map(r=>r.doc_id);
    const plan=sql.prepare('EXPLAIN QUERY PLAN '+query).all('bob',10).map(r=>r.detail);
    assert.ok(!plan.some(p=>p.includes('TEMP B-TREE')));
    return {ids,visits,plan};
  },writeTime(value){const before=sql.prepare('SELECT total_changes() n').get().n;
    sql.prepare("UPDATE friendbook_cf_firestore_documents SET document_json=json_set(document_json,'$.lastMessageTime.millis',?),update_time=? WHERE path='chats/room00000'").run(value,value);
    return sql.prepare('SELECT total_changes() n').get().n-before;
  }};
}

export async function runTradeoff(roomCount=1000) {
  assert.ok(Number.isInteger(roomCount)&&roomCount>=10&&roomCount<=10000);
  const results=[];
  for(const members of [2,50]){
    const base=fixture(roomCount,members,false),ordered=fixture(roomCount,members,true);
    try {
      const current=await base.currentPage(),candidate=ordered.rankedPage();
      assert.deepEqual(candidate.ids,current.ids);
      const baselineChanges=base.writeTime(200000),candidateChanges=ordered.writeTime(200000);
      assert.equal(candidateChanges-baselineChanges,2*members);
      // A receipt/metadata update with unchanged activity must not fan out.
      assert.equal(ordered.writeTime(200000)-base.writeTime(200000),0);
      results.push({members,rooms:roomCount,pageSize:10,currentCandidateVisits:current.visits,
        orderedCandidateVisits:candidate.visits,baselineLocalChangedRows:baselineChanges,
        orderedLocalChangedRows:candidateChanges,additionalLocalChangedRows:2*members,
        orderedPlan:candidate.plan});
    } finally {base.sql.close();ordered.sql.close();}
  }
  return {environment:'Offline SQLite experiment; candidate callbacks and total_changes are NOT D1 billing',
    warning:'Experimental triggers support only seeded modern timestamps, not production compatibility or ACL semantics. Never apply them as a migration.',
    decision:'DO_NOT_DEPLOY_UNCONDITIONAL_MEMBER_FANOUT',results};
}
if(process.argv[1] && new URL(import.meta.url).pathname===process.argv[1])console.log(JSON.stringify(await runTradeoff(Number(process.argv[2]||1000)),null,2));
