import {before,after,test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {PGlite} from '@electric-sql/pglite';
let db;
before(async()=>{
  db=new PGlite();
  await db.exec('create role anon; create role authenticated; create role service_role;');
  await db.exec(readFileSync(new URL('../migrations/202611040001_counter_core.sql',import.meta.url),'utf8'));
});
after(async()=>{await db?.close();});
const apply=(uid,event,version,delta,arrival=true,kind='messages')=>db.query('select public.counter_v1_apply($1,$2,$3,$4,$5,$6)',[uid,kind,event,String(version),delta,arrival]);
const seed=(uid,count=0,cutoff=0,kind='messages')=>db.query('select public.counter_v1_seed($1,$2,$3,$4)',[uid,kind,String(cutoff),String(count)]);
const ack=(uid,observed,device='phone',kind='messages')=>db.query('select public.counter_v1_acknowledge($1,$2,$3,$4)',[uid,kind,device,observed]);
const snapshot=async(uid,device='phone')=>(await db.query('select * from public.counter_v1_snapshot($1,$2)',[uid,device])).rows[0];
test('uninitialized counter is unknown, not falsely zero; read-time baseline replays only newer events',async()=>{
  await apply('bootstrap','old',10,1);await apply('bootstrap','new',30,2);
  assert.equal((await snapshot('bootstrap')).unread_count,null);
  await seed('bootstrap',8,20);
  assert.equal(Number((await snapshot('bootstrap')).unread_count),10);
  await apply('bootstrap','late-old',15,5);
  assert.equal(Number((await snapshot('bootstrap')).unread_count),10);
  assert.equal((await seed('bootstrap',999,40)).rows[0].counter_v1_seed,false);
});
test('stable IDs deduplicate at-least-once deliveries; conflicting replay rolls back',async()=>{
  await seed('dedup');await Promise.all(Array.from({length:25},()=>apply('dedup','one',10,1)));
  assert.equal(Number((await snapshot('dedup')).unread_count),1);
  await assert.rejects(()=>apply('dedup','one',10,9),/Conflicting event replay/);
  assert.equal(Number((await snapshot('dedup')).unread_count),1);
});
test('out-of-order read before creates preserves signed balance, not a clamped accumulator',async()=>{
  await seed('reverse');await apply('reverse','read',20,-5,false);
  assert.equal(Number((await snapshot('reverse')).unread_count),0);
  for(let i=1;i<=5;i++)await apply('reverse','create'+i,i,1);
  assert.equal(Number((await snapshot('reverse')).unread_count),0);
  await apply('reverse','future',21,1);
  assert.equal(Number((await snapshot('reverse')).unread_count),1);
});
test('ack affects only observed badge, not unread count, other devices or later arrivals',async()=>{
  await seed('ack');await apply('ack','a',1,1);await apply('ack','b',2,1);
  const displayed=Number((await snapshot('ack')).arrival_total);
  await apply('ack','c',3,1);await ack('ack',displayed);
  let row=await snapshot('ack');assert.equal(Number(row.unread_count),3);assert.equal(Number(row.badge_count),1);
  assert.equal(Number((await snapshot('ack','tablet')).badge_count),3);
  await ack('ack',1);assert.equal(Number((await snapshot('ack')).badge_count),1);
  await ack('ack',999);await apply('ack','d',4,1);
  assert.equal(Number((await snapshot('ack')).badge_count),1);
});
test('kinds/accounts are independent; raw read/delete deltas do not acknowledge badge arrivals',async()=>{
  for(const kind of ['messages','notifications','requests']){await seed('kinds',0,0,kind);await apply('kinds',kind,1,1,true,kind);}
  await apply('kinds','read',2,-1,false,'notifications');await ack('kinds',1,'phone','requests');
  const rows=(await db.query("select * from public.counter_v1_snapshot('kinds','phone') order by kind")).rows;
  assert.deepEqual(rows.map(r=>[r.kind,Number(r.unread_count),Number(r.badge_count)]),[['messages',1,1],['notifications',0,1],['requests',1,0]]);
  assert.equal((await db.query("select * from public.counter_v1_snapshot('someone-else','phone')")).rows.length,0);
});
test('integer nanosecond fence is exact beyond JS safe integers',async()=>{
  const fence='1793750000000000000';await seed('precision',7,fence);
  await apply('precision','included',fence,5);await apply('precision','next','1793750000000000001',1);
  assert.equal(Number((await snapshot('precision')).unread_count),8);
});
test('failed arithmetic rolls back dedup ledger too, allowing a safe retry after resolution',async()=>{
  await seed('overflow','9223372036854775807');
  await assert.rejects(()=>apply('overflow','overflow-event',1,1),/out of range/);
  assert.equal((await db.query("select count(*)::int n from public.counter_v1_events where uid='overflow'")).rows[0].n,0);
});
test('bad seeds/events are rejected and unready counters cannot be acknowledged',async()=>{
  await assert.rejects(()=>seed('negative',-1),/Invalid baseline/);
  await assert.rejects(()=>apply('invalid','x','1.5',1),/Invalid canonical event/);
  await apply('unready','x',1,1);await assert.rejects(()=>ack('unready',1),/Counter not ready/);
  await assert.rejects(()=>seed('kind',0,0,'unknown'),/check constraint/);
});
test('mobile/anonymous roles cannot read tables or call server RPCs',async()=>{
  for(const role of ['anon','authenticated']){
    await db.exec('set role '+role);
    try{
      await assert.rejects(()=>snapshot('ack'),/permission denied/);
      await assert.rejects(()=>apply('ack','forged',99,999),/permission denied/);
      await assert.rejects(()=>seed('forged'),/permission denied/);
      await assert.rejects(()=>ack('ack',999),/permission denied/);
      await assert.rejects(()=>db.query('select * from public.counter_v1_state'),/permission denied/);
    }finally{await db.exec('reset role');}
  }
  await db.exec('set role service_role');
  try{assert.equal((await snapshot('ack')).ready,true);}finally{await db.exec('reset role');}
});
