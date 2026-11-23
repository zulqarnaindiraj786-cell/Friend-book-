import { canonicalSeed } from './fixtures/canonical.js';
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {readFileSync,readdirSync} from 'node:fs';
import {interactions} from '../interactions.js';

// Compare real engagement handlers, results and SQLite plans. SQLite plans and
// changed rows are not D1 billed-row totals.
test('unscoped comment author index removal preserves pages, counts and blocked-user privacy',async t=>{
  const sql=new DatabaseSync(':memory:');t.after(()=>sql.close());sql.exec('PRAGMA foreign_keys=ON');
  const dir=new URL('../migrations/',import.meta.url),removal='0080_remove_unused_comment_author_index.sql';
  for(const file of readdirSync(dir).filter(f=>f.endsWith('.sql')&&f!==removal).sort())sql.exec(readFileSync(new URL(file,dir),'utf8'));
  sql.exec("INSERT INTO friendbook_cf_interaction_resources VALUES ('posts/p1','verified')");
  const insert=sql.prepare('INSERT INTO friendbook_cf_comments VALUES (?,?,?,?,?,?)');
  for(let i=0;i<120;i++)insert.run('posts/p1',`c${String(i).padStart(3,'0')}`,i%5===0?'blocked':i%2?'alice':'bob','comment',i>0&&i%7===0?'c001':null,1000+i);
  sql.prepare('INSERT INTO friendbook_cf_comment_details(resource,id,removed) VALUES(?,?,1)').run('posts/p1','c002');
  const old=canonicalSeed(sql);
  for(const [id,author,time] of [['legacy','bob',1040],['legacyblocked','blocked',1042],['c003','bob',1030]])old.run(`comments/${id}`,'comments',id,author,JSON.stringify({postId:'p1',authorId:author,content:'legacy',createdAt:time}),1);
  sql.prepare("INSERT INTO friendbook_cf_comment_pins VALUES('posts/p1','c001')").run();
  let plans=[];
  const db={withSession(){return this;},prepare(q){const stmt=sql.prepare(q);return{bind(...args){
    if(/^\s*(SELECT|WITH)\b/i.test(q))plans.push({q,plan:sql.prepare('EXPLAIN QUERY PLAN '+q).all(...args).map(x=>x.detail)});
    return {async first(){return stmt.get(...args)||null;},async all(){return {results:stmt.all(...args)};},async run(){return /^\s*SELECT\b/i.test(q)?{results:stmt.all(...args)}:stmt.run(...args);}};
  }};},async batch(stmts){sql.exec('BEGIN');try{const out=[];for(const stmt of stmts)out.push(await stmt.run());sql.exec('COMMIT');return out;}catch(e){sql.exec('ROLLBACK');throw e;}}};
  const env={DB:db,D1_INTERACTIONS_ENABLED:'true'};
  const auth=async (_identity,_collection,_id,_db,context)=>{context.blocked=['blocked'];context.owner='owner';return true;};
  const beforeCursor=btoa(JSON.stringify([1085,'c085'])),afterCursor=btoa(JSON.stringify([1035,'c035']));
  async function suite(){plans=[];const requests=[
    ['comments','?limit=10&includeReactions=1'],['comments',`?before=${encodeURIComponent(beforeCursor)}`],
    ['comments',`?after=${encodeURIComponent(afterCursor)}`],['comments','?id=c001'],['comments','?id=c000'],
    ['comments','?id=legacy'],['comments','?id=legacyblocked'],['reactions',''],['reactors','?commentId=c001']];
    const responses=[];for(const [kind,query] of requests){
      const response=await interactions(new Request(`https://example.test/v1/engagement/posts/p1/${kind}${query}`),env,{uid:'alice'},[null,'posts','p1',kind],auth);
      responses.push({status:response.status,body:await response.json()});
    }return{responses,plans};
  }
  const before=await suite();assert.deepEqual(before.responses.map(r=>r.status),[200,200,200,200,404,200,404,200,200]);
  assert.ok(before.responses[0].body.comments.every(c=>c.uid!=='blocked'&&c.id!=='c002'));
  assert.equal(before.responses[7].body.comments,96); // 95 visible canonical plus one distinct visible legacy.
  assert.ok(before.plans.some(p=>p.plan.some(x=>x.includes('friendbook_cf_comments_page'))));
  assert.ok(before.plans.some(p=>p.plan.some(x=>x.includes('idx_cf_comment_resource_author'))));
  assert.ok(before.plans.every(p=>p.plan.every(x=>!x.includes('idx_cf_comment_author'))));
  const migration=readFileSync(new URL(removal,dir),'utf8');sql.exec(migration);sql.exec(migration);
  assert.equal(sql.prepare("SELECT name FROM sqlite_master WHERE type='index' AND name='idx_cf_comment_author'").get(),undefined);
  assert.deepEqual(await suite(),before);
  const replyPlan=sql.prepare('EXPLAIN QUERY PLAN SELECT id FROM friendbook_cf_comments WHERE resource=? AND parent_id=? ORDER BY created_at DESC,id DESC').all('posts/p1','c001').map(x=>x.detail);
  assert.ok(replyPlan.some(p=>p.includes('idx_cf_comment_reply_parent')));
  sql.exec('CREATE INDEX idx_cf_comment_author ON friendbook_cf_comments(uid,created_at DESC)');
  assert.deepEqual(await suite(),before);
  sql.exec(migration);
  async function write(method,data){
    const response=await interactions(new Request('https://example.test/v1/engagement/posts/p1/comments',{
      method,headers:{'content-type':'application/json'},body:JSON.stringify(data),
    }),env,{uid:'alice'},[null,'posts','p1','comments'],auth);
    return {status:response.status,body:await response.json()};
  }
  assert.equal((await write('POST',{id:'new',text:'hello'})).status,200);
  const changes=sql.prepare('SELECT total_changes() n').get().n;
  assert.equal((await write('POST',{id:'new',text:'hello'})).status,200);
  assert.equal(sql.prepare('SELECT total_changes() n').get().n,changes,'retry must not write');
  assert.equal((await write('POST',{id:'new',text:'different'})).status,409);
  assert.equal((await write('POST',{id:'reply',text:'reply',parentId:'new'})).status,200);
  assert.equal((await write('POST',{id:'denied',text:'reply',parentId:'c000'})).status,400,'blocked author cannot be replied to');
  assert.equal((await write('PUT',{op:'edit',id:'new',text:'edited'})).status,200);
  assert.equal((await write('PUT',{op:'remove',id:'new'})).status,200);
  assert.equal((await suite()).responses[7].body.comments,97,'only the new visible reply remains in the count');
});
