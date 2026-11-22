import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import { pruneExpiredRealtime, PRESENCE_CURSOR_KEY } from '../maintenance.js';

function setup(){
  const sql=new DatabaseSync(':memory:');
  for(const name of ['0031_presence_sessions.sql','0033_typing_sessions.sql','0034_realtime_maintenance_indexes.sql'])sql.exec(readFileSync(new URL(`../migrations/${name}`,import.meta.url),'utf8'));
  let failDelete=false, beforeDelete=null, pages=[], plans=[];
  const db={prepare(q){const stmt=sql.prepare(q);return {bind(...args){return {
    async all(){
      const results=stmt.all(...args);if(q.includes('SELECT last_seen,rowid')){
        pages.push(results.length);
        plans.push(...sql.prepare('EXPLAIN QUERY PLAN '+q).all(...args).map(r=>r.detail));
      }
      return {success:true,results};
    },
    async run(){
      if(q.startsWith('DELETE FROM friendbook_cf_presence_sessions WHERE rowid IN (SELECT value')) {
        if(beforeDelete){const action=beforeDelete;beforeDelete=null;action();}
        if(failDelete)throw Error('private database error');
      }
      return {success:true,meta:{changes:Number(stmt.run(...args).changes)}};
    }
  };}};}};
  let value=null, failGet=false,failPut=false;const writes=[];
  const bucket={async get(key){assert.equal(key,PRESENCE_CURSOR_KEY);if(failGet)throw Error('private storage error');return value===null?null:{size:value.length,async json(){return JSON.parse(value);}};},
    async put(key,body){assert.equal(key,PRESENCE_CURSOR_KEY);if(failPut)throw Error('private storage error');value=body;writes.push(JSON.parse(body));}};
  return {sql,db,bucket,pages,plans,writes,
    state:()=>value, setState:v=>{value=v;},setFailGet:v=>{failGet=v;},setFailPut:v=>{failPut=v;},
    setFailDelete:v=>{failDelete=v;},beforeDelete:f=>{beforeDelete=f;}};
}
const now=20*24*60*60*1000;

test('bounded sweep advances past retained sessions, wraps, preserves newest and checks ties',async()=>{
  const f=setup(),add=f.sql.prepare('INSERT INTO friendbook_cf_presence_sessions VALUES (?,?,?,?,?)');
  for(let i=0;i<350;i++)add.run(`user${i}`,'a',0,1,100);
  for(let i=0;i<200;i++)add.run(`user${i}`,'b',0,1,100);
  add.run('user199','fresh',1,1,now);
  for(let i=0;i<12;i++)await pruneExpiredRealtime(f.db,now,f.bucket);
  assert.equal(f.sql.prepare('SELECT COUNT(*) n FROM friendbook_cf_presence_sessions').get().n,350);
  assert.equal(f.sql.prepare("SELECT COUNT(*) n FROM friendbook_cf_presence_sessions WHERE uid='user0' AND session_id='b'").get().n,1);
  assert.equal(f.sql.prepare("SELECT COUNT(*) n FROM friendbook_cf_presence_sessions WHERE uid='user199' AND session_id='fresh'").get().n,1);
  assert.ok(f.writes.some(w=>w.after===null),'end of sweep wraps');
  assert.ok(f.pages.every(n=>n<=100));
  assert.ok(f.plans.some(p=>/idx_cf_presence_sessions_expiry \(last_seen=\? AND rowid>\?\)/.test(p)),f.plans.join('\n'));
  // A session inserted behind an already-advanced cursor is still reached on wrap.
  add.run('user0','older-late',0,1,1);
  for(let i=0;i<10;i++)await pruneExpiredRealtime(f.db,now,f.bucket);
  assert.equal(f.sql.prepare("SELECT COUNT(*) n FROM friendbook_cf_presence_sessions WHERE uid='user0'").get().n,1);
  assert.ok(f.writes.every(w=>!JSON.stringify(w).includes('user')),'checkpoint contains no identities');
  f.sql.close();
});

test('complete sweep matches prior retention rule on varied multi-device histories',async()=>{
  const f=setup(),add=f.sql.prepare('INSERT INTO friendbook_cf_presence_sessions VALUES (?,?,?,?,?)');
  let seed=7231;const random=()=>{seed=(seed*48271)%2147483647;return seed;};
  for(let i=0;i<220;i++){
    const count=1+random()%8;
    for(let j=0;j<count;j++)add.run(`u${i}`,`s${j}`,j%2,1,(random()%7===0)?now:1+random()%4);
  }
  const original=f.sql.prepare('SELECT uid,session_id,last_seen FROM friendbook_cf_presence_sessions').all();
  const groups=new Map();for(const row of original){if(!groups.has(row.uid))groups.set(row.uid,[]);groups.get(row.uid).push(row);}
  const expected=[];
  for(const rows of groups.values()){
    const fresh=rows.filter(row=>row.last_seen>=now-7*24*60*60*1000);
    if(fresh.length)expected.push(...fresh.map(row=>`${row.uid}/${row.session_id}`));
    else {
      rows.sort((a,b)=>b.last_seen-a.last_seen || (a.session_id<b.session_id?1:a.session_id>b.session_id?-1:0));
      expected.push(`${rows[0].uid}/${rows[0].session_id}`);
    }
  }
  for(let i=0;i<35;i++)await pruneExpiredRealtime(f.db,now,f.bucket);
  const actual=f.sql.prepare('SELECT uid,session_id FROM friendbook_cf_presence_sessions').all().map(row=>`${row.uid}/${row.session_id}`);
  assert.deepEqual(actual.sort(),expected.sort());
  f.sql.close();
});

test('racing renewals and concurrent runs cannot delete a last or fresh session',async()=>{
  const f=setup(),add=f.sql.prepare('INSERT INTO friendbook_cf_presence_sessions VALUES (?,?,?,?,?)');
  add.run('alice','old',1,1,1);add.run('alice','new',1,1,2);add.run('bob','only',1,1,1);
  f.beforeDelete(()=>f.sql.prepare("UPDATE friendbook_cf_presence_sessions SET last_seen=? WHERE uid='alice' AND session_id='old'").run(now));
  await pruneExpiredRealtime(f.db,now,f.bucket);
  assert.equal(f.sql.prepare("SELECT COUNT(*) n FROM friendbook_cf_presence_sessions WHERE uid='alice' AND session_id='old'").get().n,1,'renewed candidate remains');
  assert.equal(f.sql.prepare("SELECT COUNT(*) n FROM friendbook_cf_presence_sessions WHERE uid='bob'").get().n,1,'only session remains');
  await Promise.all([pruneExpiredRealtime(f.db,now,f.bucket),pruneExpiredRealtime(f.db,now,f.bucket)]);
  assert.equal(f.sql.prepare("SELECT COUNT(*) n FROM friendbook_cf_presence_sessions WHERE uid='alice' AND last_seen=?").get(now).n,1);
  assert.equal(f.sql.prepare("SELECT COUNT(*) n FROM friendbook_cf_presence_sessions WHERE uid='bob'").get().n,1);
  f.sql.close();
});

test('rowid reuse between selection and deletion still protects the replacement last session',async()=>{
  const f=setup(),add=f.sql.prepare('INSERT INTO friendbook_cf_presence_sessions VALUES (?,?,?,?,?)');
  add.run('old','a',1,1,1);add.run('old','b',1,1,2);
  f.beforeDelete(()=>{
    f.sql.prepare("DELETE FROM friendbook_cf_presence_sessions WHERE uid='old' AND session_id='a'").run();
    f.sql.prepare("INSERT INTO friendbook_cf_presence_sessions(rowid,uid,session_id,online,visible,last_seen) VALUES (1,'replacement','only',0,0,1)").run();
  });
  await pruneExpiredRealtime(f.db,now,f.bucket);
  assert.equal(f.sql.prepare("SELECT COUNT(*) n FROM friendbook_cf_presence_sessions WHERE uid='replacement' AND visible=0").get().n,1);
  f.sql.close();
});

test('disabled online status skips presence cleanup and R2 but retains typing cleanup',async()=>{
  const f=setup();
  f.sql.prepare('INSERT INTO friendbook_cf_presence_sessions VALUES (?,?,?,?,?)').run('alice','a',0,0,1);
  f.sql.prepare('INSERT INTO friendbook_cf_typing_sessions VALUES (?,?,?,?,?)').run('chat','alice','phone',0,1);
  f.setFailGet(true);
  await pruneExpiredRealtime(f.db,now,f.bucket,true);
  assert.equal(f.sql.prepare('SELECT COUNT(*) n FROM friendbook_cf_presence_sessions').get().n,1);
  assert.equal(f.sql.prepare('SELECT COUNT(*) n FROM friendbook_cf_typing_sessions').get().n,0);
  assert.equal(f.pages.length,0);assert.equal(f.writes.length,0);
  f.sql.close();
});

test('storage failures never advance cursor; typing cleanup still runs without checkpoint',async()=>{
  const f=setup(),add=f.sql.prepare('INSERT INTO friendbook_cf_presence_sessions VALUES (?,?,?,?,?)');
  add.run('alice','old',1,1,1);add.run('alice','new',1,1,2);
  f.sql.prepare('INSERT INTO friendbook_cf_typing_sessions VALUES (?,?,?,?,?)').run('chat','alice','phone',0,1);
  f.setFailGet(true);await assert.rejects(pruneExpiredRealtime(f.db,now,f.bucket));
  assert.equal(f.sql.prepare('SELECT COUNT(*) n FROM friendbook_cf_typing_sessions').get().n,0,'typing cleanup independent');
  assert.equal(f.sql.prepare('SELECT COUNT(*) n FROM friendbook_cf_presence_sessions').get().n,2);
  f.setFailGet(false);f.setFailDelete(true);await assert.rejects(pruneExpiredRealtime(f.db,now,f.bucket));
  assert.equal(f.state(),null,'D1 failure cannot advance cursor');
  f.setFailDelete(false);f.setFailPut(true);await assert.rejects(pruneExpiredRealtime(f.db,now,f.bucket));
  assert.equal(f.state(),null,'failed checkpoint causes safe retry');
  f.setFailPut(false);await pruneExpiredRealtime(f.db,now,f.bucket);
  assert.equal(f.sql.prepare('SELECT COUNT(*) n FROM friendbook_cf_presence_sessions').get().n,1);
  f.setState('{"v":1,"after":["broken",7]}');await assert.rejects(pruneExpiredRealtime(f.db,now,f.bucket));
  await assert.rejects(pruneExpiredRealtime(f.db,now,null));
  assert.equal(f.sql.prepare('SELECT COUNT(*) n FROM friendbook_cf_presence_sessions').get().n,1);
  f.sql.close();
});
