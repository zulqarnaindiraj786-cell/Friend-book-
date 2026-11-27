import { canonicalSeed } from './fixtures/canonical.js';
import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import { createWorker } from '../../worker.js';

const migration=n=>readFileSync(new URL(`../migrations/${n}`,import.meta.url),'utf8');
for(const root of ['posts','reels']) test(`${root} keyset seeks preserve both time ranks, ties, ACL and mirrors`,async()=>{
  const sql=new DatabaseSync(':memory:');
  for(const n of ['0003_additive_interactions.sql','0011_additive_app_documents.sql','0014_additive_firestore_documents.sql','0029_feed_page_indexes.sql','0051_feed_exception_indexes.sql',...(root==='reels'?['0063_reel_page_indexes.sql']:[])])sql.exec(migration(n));
  const canonical=sql.prepare('INSERT OR REPLACE INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?,?,?,?)');
  const legacy=canonicalSeed(sql);
  for(const uid of ['alice','bob'])canonical.run(`users/${uid}`,'users','users',uid,uid,JSON.stringify({uid}),1,1);
  const put=(id,data,where)=>{
    const path=`${root}/${id}`,json=JSON.stringify(data);
    if(where!=='legacy')canonical.run(path,root,root,id,'alice',json,1,1);
    if(where!=='canonical')legacy.run(path,root,id,'alice',json,1);
  };
  for(let i=0;i<160;i++){
    const id=`p${String(i).padStart(3,'0')}`,time=Math.floor((i%80)/4)-15;
    put(id,{authorId:'alice',privacy:i%13===0?'Only me':'Public',createdAt:i<80?time:{__type:'timestamp',millis:time}},i%3===0?'legacy':i%3===1?'canonical':'both');
  }
  // Changed canonical privacy and order shadow stale public legacy copies.
  canonical.run(`${root}/shadow`,root,root,'shadow','alice',JSON.stringify({authorId:'alice',privacy:'Only me',createdAt:{__type:'timestamp',millis:-9}}),1,1);
  legacy.run(`${root}/shadow`,root,'shadow','alice',JSON.stringify({authorId:'alice',privacy:'Public',createdAt:{__type:'timestamp',millis:50}}),1);
  // A canonical path stored under another parent must not shadow a root mirror.
  canonical.run(`${root}/wrongParent`,'other','other','wrongParent','alice',JSON.stringify({authorId:'alice',privacy:'Public',createdAt:{__type:'timestamp',millis:8}}),1,1);
  legacy.run(`${root}/wrongParent`,root,'wrongParent','alice',JSON.stringify({authorId:'alice',privacy:'Public',createdAt:{__type:'timestamp',millis:8}}),1);
  // Exceptional timestamps still merge through the compatibility exception path.
  put('odd',{authorId:'alice',privacy:'Public',createdAt:{__type:'timestamp',millis:2.5}},'canonical');
  const plans=[],shadowPlans=[];let contentRootLookups=0;
  const db={prepare(q){const stmt=sql.prepare(q);return{bind(...args){return{
    all:async()=>{
      if(q.includes('quota_feed_rank'))plans.push(sql.prepare('EXPLAIN QUERY PLAN '+q).all(...args).map(x=>x.detail));
      if(q.startsWith('SELECT path,parent_path FROM friendbook_cf_firestore_documents WHERE path IN'))shadowPlans.push(sql.prepare('EXPLAIN QUERY PLAN '+q).all(...args).map(x=>x.detail));
      return{results:stmt.all(...args)};
    },
    first:async()=>{
      if(q.includes('friendbook_cf_firestore_documents') && /WHERE path\s*=/.test(q) && typeof args[0]==='string' && args[0].startsWith(root+'/'))contentRootLookups++;
      return stmt.get(...args)||null;
    },
  };}};}};
  const app=createWorker(async t=>t),env={DB:db,FIREBASE_PROJECT_ID:'test'};
  const query=async(spec,reference)=>{
    const body={parent:root,orderBy:[{field:'createdAt',direction:'DESCENDING'}],...spec};
    // Force the existing generic evaluator, while retaining all fixture docs.
    if(reference)body.filters=[{field:'privacy',op:'!=',value:'Never'}];
    contentRootLookups=0;
    const res=await app.fetch(new Request('https://local.invalid/v1/firestore/query',{method:'POST',headers:{authorization:'Bearer bob','content-type':'application/json'},body:JSON.stringify(body)}),env);
    assert.equal(res.status,200,await res.clone().text());
    assert.equal(contentRootLookups,0,'canonical feed must authorize its loaded roots without N+1 SELECTs');
    return (await res.json()).documents.map(x=>x.id);
  };
  const cursors=[undefined,[{__type:'timestamp',millis:99},'z'],[{__type:'timestamp',millis:4},'p159'],[{__type:'timestamp',millis:0},'p100'],[{__type:'timestamp',millis:-15},'p080'],[99,'z'],[4,'p070'],[0,'p060'],[-15,'p003']];
  for(const cursor of cursors)for(const limit of [1,3,12,100]){
    const spec={limit,...(cursor?{startAfter:cursor}:{})};
    assert.deepEqual(await query(spec,false),await query(spec,true),JSON.stringify({root,spec}));
  }
  assert.ok(!(await query({limit:100},false)).includes('wrongParent'),'wrong-parent canonical rows never revive a mirror');
  assert.equal(shadowPlans.length,0,'canonical-only windows never probe mirrors');
  for(const plan of shadowPlans) assert.match(plan.join('\n'),/SEARCH .*sqlite_autoindex_friendbook_cf_firestore_documents_1 \(path=\?\)/);
  assert.ok(plans.length>0,'cursor queries use indexed candidates');
  for(const plan of plans){
    const text=plan.join('\n');
    assert.match(text,new RegExp(`idx_cf_(?:legacy_)?${root}_feed_page`));
    assert.match(text,/<expr>=\?/,'rank must constrain the expression index');
    assert.match(text,/<expr><\?/,'cursor time must seek in the expression index');
  }
  // A second request must observe changed author restrictions; the snapshot
  // reuse is request-local content only, never a cached author ACL.
  for(const restriction of [{blocked:['bob']},{banned:true},{deactivated:true}]) {
    canonical.run('users/alice','users','users','alice','alice',JSON.stringify({uid:'alice',...restriction}),1,2);
    assert.deepEqual(await query({limit:12},false),[]);
    assert.deepEqual(await query({limit:12},true),[]);
  }
  sql.close();
});
