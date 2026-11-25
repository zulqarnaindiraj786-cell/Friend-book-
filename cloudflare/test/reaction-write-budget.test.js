import { test } from 'node:test';
import assert from 'node:assert/strict';
import { fixture } from './fixtures/interaction-db.js';
import { interactions } from '../interactions.js';

const identity={uid:'user1',project:'friendbook-17551',token:'test'};
const authorize=async(_identity,_collection,_id,_unused,context)=>{
  Object.assign(context,{owner:'owner',name:'Writer',photo:'writer.jpg',blocked:[]});
  return true;
};

test('reaction write budget reports changed rows separately from SQL statement count',async t=>{
  const f=fixture();t.after(f.close);
  const raw=f.env.DB;
  const totals={statements:0,returnedRows:0};
  const db={
    withSession(){return db;},
    prepare(query){
      const statement=raw.prepare(query);
      return {bind(...args){
        const bound=statement.bind(...args);
        return {
          async first(){totals.statements++;const row=await bound.first();if(row)totals.returnedRows++;return row;},
          async all(){totals.statements++;const result=await bound.all();totals.returnedRows+=result.results?.length||0;return result;},
          async run(){totals.statements++;return bound.run();},
        };
      }};
    },
    async batch(statements){return raw.batch(statements);},
  };
  f.env.DB=db;
  const changes=()=>f.sql.prepare('SELECT total_changes() AS n').get().n;
  const measure=async(name,resource,type,query='?response=reaction')=>{
    const beforeChanges=changes(),beforeStatements=totals.statements,beforeRows=totals.returnedRows;
    const request=new Request(`https://example.invalid/v1/engagement/posts/${resource}/reactions${query}`,{
      method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({type}),
    });
    const response=await interactions(request,f.env,identity,[null,'posts',resource,'reactions'],authorize);
    assert.equal(response.status,200,await response.clone().text());
    const payload=await response.json();
    return {name,sqliteStatements:totals.statements-beforeStatements,
      sqliteReturnedRows:totals.returnedRows-beforeRows,changedRowsIncludingTriggers:changes()-beforeChanges,payload};
  };

  const first=await measure('first_like','p1','like');
  const repeat=await measure('repeat_same_like','p1','like');
  const change=await measure('change_like_to_love','p1','love');
  const clear=await measure('clear_reaction','p1',null);

  // Model the explicit legacy-only unknown timestamp marker transition.
  f.sql.prepare(`INSERT INTO friendbook_cf_reaction_history(resource,uid,timestamp_known)
    VALUES('posts/p1','user1',0)
    ON CONFLICT(resource,uid) DO UPDATE SET timestamp_known=0`).run();
  const legacy=await measure('legacy_unknown_history_transition','p1','like');

  f.sql.prepare("INSERT INTO friendbook_cf_interaction_resources(resource,state) VALUES('posts/pnotify','verified')").run();
  const notified=await measure('first_like_with_notification','pnotify','like','?response=reaction&notify=1');
  const notificationRetry=await measure('duplicate_like_notification','pnotify','like','?response=reaction&notify=1');

  assert.ok(first.changedRowsIncludingTriggers>0,'first reaction must persist');
  assert.equal(repeat.changedRowsIncludingTriggers,0,'same desired state must be a no-op');
  assert.ok(change.changedRowsIncludingTriggers>0,'changing reaction must persist');
  assert.ok(clear.changedRowsIncludingTriggers>0,'clearing an existing reaction must persist');
  assert.ok(legacy.changedRowsIncludingTriggers>0,'legacy timestamp marker must be repaired');
  assert.equal(notified.payload.notificationCreated,true);
  assert.equal(notificationRetry.payload.notificationCreated,false);
  assert.equal(notificationRetry.changedRowsIncludingTriggers,0,'duplicate reaction + inbox event retry must be a no-op');
  assert.equal(f.sql.prepare("SELECT COUNT(*) AS n FROM friendbook_cf_firestore_documents WHERE parent_path='notifications'").get().n,1);

  t.diagnostic(JSON.stringify({environment:'local SQLite fixture; not Cloudflare billing',workloads:[
    first,repeat,change,clear,legacy,notified,notificationRetry,
  ].map(({payload,...metrics})=>metrics)}));
});
