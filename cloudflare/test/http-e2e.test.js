// Real loopback HTTP + Worker router + RSA token verification + SQLite SQL.
// Test keys/source-provider fixtures ONLY. Never calls production Firebase or D1.
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {generateKeyPair,SignJWT} from 'jose';
import {createWorker,verifyFirebaseToken} from '../../worker.js';
import {fixture} from './fixtures/interaction-db.js';
const project='friendbook-17551';

async function scenario(t){
 const f=fixture(), originalFetch=globalThis.fetch;
 const keys=await generateKeyPair('RS256');
 const state={sourceReads:0,privacy:'Public',blocked:[],banned:false};
 globalThis.fetch=async(url,options)=>{
  const path=String(url);
  assert.ok(path.startsWith('https://firestore.googleapis.com/v1/'),'Unexpected network destination');
  state.sourceReads++;
  const name=decodeURIComponent(path.split('/documents/')[1]||'');
  if(name.startsWith('posts/')||name.startsWith('reels/'))return Response.json({fields:{authorId:{stringValue:'user1'},privacy:{stringValue:state.privacy}}});
  if(name.startsWith('users/'))return Response.json({fields:{displayName:{stringValue:'Fixture user'},banned:{booleanValue:state.banned},blocked:{arrayValue:{values:state.blocked.map(stringValue=>({stringValue}))}}}});
  throw Error('Unconfigured source fixture');
 };
 const worker=createWorker((token,p)=>verifyFirebaseToken(token,p,keys.publicKey));
 const server=createServer(async(req,res)=>{
  try{
   const chunks=[];for await(const chunk of req)chunks.push(chunk);
   const request=new Request('http://127.0.0.1'+req.url,{method:req.method,headers:req.headers,
    ...(['GET','HEAD'].includes(req.method)?{}:{body:Buffer.concat(chunks)})});
   const response=await worker.fetch(request,{...f.env,FIREBASE_PROJECT_ID:project});
   res.writeHead(response.status,Object.fromEntries(response.headers));res.end(Buffer.from(await response.arrayBuffer()));
  }catch{res.writeHead(500);res.end('fixture failure');}
 });
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
 t.after(async()=>{globalThis.fetch=originalFetch;await new Promise(resolve=>server.close(resolve));f.close();});
 const token=async(uid='user1',audience=project)=>new SignJWT({auth_time:Math.floor(Date.now()/1000)}).setProtectedHeader({alg:'RS256'}).setSubject(uid).setIssuer('https://securetoken.google.com/'+project).setAudience(audience).setIssuedAt().setExpirationTime('5m').sign(keys.privateKey);
 const owner=await token();
 const call=async(path,method='GET',body,auth=owner)=>{
  const result=await originalFetch('http://127.0.0.1:'+server.address().port+path,{method,headers:{...(auth?{Authorization:'Bearer '+auth}:{}),...(body===undefined?{}:{'Content-Type':'application/json'})},...(body===undefined?{}:{body:JSON.stringify(body)})});
  return {status:result.status,body:await result.json()};
 };
 return {...f,state,token,call};
}
const route=kind=>'/v1/engagement/posts/p1/'+kind;

test('HTTP: auth, global OFF and staging markers reject writes before source access',async t=>{
 const f=await scenario(t);
 assert.equal((await f.call('/health')).body.ok,true);
 assert.equal((await f.call(route('comments'),'POST',{id:'x',text:'denied'},null)).status,401);
 assert.equal((await f.call(route('comments'),'POST',{id:'x',text:'denied'},await f.token('user1','wrong-project'))).status,401);
 f.env.D1_INTERACTIONS_ENABLED='false';
 assert.equal((await f.call(route('comments'),'POST',{id:'x',text:'denied'})).status,503);
 f.env.D1_INTERACTIONS_ENABLED='true';
 assert.equal((await f.call('/v1/engagement/posts/p2/comments','POST',{id:'x',text:'denied'})).status,503);
 assert.equal(f.state.sourceReads,0);
 assert.equal(f.sql.prepare('SELECT COUNT(*) AS n FROM friendbook_cf_comments').get().n,0);
});

test('HTTP: enabled comments/media/replies/idempotency/react/pin/edit and retained soft removal',async t=>{
 const f=await scenario(t), media='https://res.cloudinary.com/demo/image/upload/fixture.jpg';
 let response=await f.call(route('comments'),'POST',{id:'parent',text:'hello',mediaUrl:media});
 assert.equal(response.status,200);assert.equal(response.body.comment.imageUrl,media);
 assert.equal((await f.call(route('comments'),'POST',{id:'parent',text:'hello',mediaUrl:media})).status,200);
 assert.equal(f.sql.prepare('SELECT COUNT(*) AS n FROM friendbook_cf_comments').get().n,1);
 assert.equal((await f.call(route('comments'),'POST',{id:'reply',text:'reply',parentId:'parent'})).status,200);
 assert.equal((await f.call(route('comments'),'PUT',{id:'parent',op:'react',type:'love'})).body.comment.likes,1);
 assert.equal((await f.call(route('comments'),'PUT',{id:'parent',op:'pin',pin:true})).body.pinned,'parent');
 assert.equal((await f.call(route('comments'),'PUT',{id:'parent',op:'edit',text:'edited'})).body.comment.content,'edited');
 assert.equal((await f.call(route('comments'),'PUT',{id:'parent',op:'edit',text:'forged'},await f.token('user2'))).status,403);
 assert.equal((await f.call(route('comments'),'POST',{id:'forged',text:'x',authorId:'user2'})).status,400);
 assert.equal((await f.call(route('comments'),'PUT',{id:'parent',op:'remove'})).status,200);
 assert.equal((await f.call(route('comments')+'?id=parent')).status,404);
 assert.equal(f.sql.prepare("SELECT text FROM friendbook_cf_comments WHERE id='parent'").get().text,'edited');
 assert.equal(f.sql.prepare('SELECT COUNT(*) AS n FROM friendbook_cf_comments').get().n,2);
});

test('HTTP: cursor paging returns 50 + 11 unique comments and 30 + 1 reactors',async t=>{
 const f=await scenario(t);
 for(let i=0;i<61;i++)assert.equal((await f.call(route('comments'),'POST',{id:'c'+String(i).padStart(3,'0'),text:'fixture'})).status,200);
 const first=await f.call(route('comments')),second=await f.call(route('comments')+'?before='+encodeURIComponent(first.body.next));
 assert.equal(first.body.comments.length,50);assert.equal(second.body.comments.length,11);
 assert.equal(new Set([...first.body.comments,...second.body.comments].map(c=>c.id)).size,61);
 for(let i=0;i<31;i++)f.sql.prepare('INSERT INTO friendbook_cf_reactions VALUES (?,?,?,?)').run('posts/p1','actor'+String(i).padStart(3,'0'),'like',123);
 const people=await f.call(route('reactors')),more=await f.call(route('reactors')+'?after='+encodeURIComponent(people.body.next));
 assert.equal(people.body.reactors.length,30);assert.equal(more.body.reactors.length,1);
});

test('HTTP: source privacy, banned actor and blocked comment/actor lists remain private',async t=>{
 const f=await scenario(t), other=await f.token('user2');
 f.state.privacy='Only me';assert.equal((await f.call(route('comments'),'GET',undefined,other)).status,403);
 f.state.privacy='Public';f.state.banned=true;assert.equal((await f.call(route('comments'))).status,403);f.state.banned=false;
 f.sql.exec("INSERT INTO friendbook_cf_comments VALUES ('posts/p1','blocked-comment','blocked','private',NULL,123)");
 f.sql.exec("INSERT INTO friendbook_cf_reactions VALUES ('posts/p1','blocked','like',123)");
 f.sql.exec("INSERT INTO friendbook_cf_comments VALUES ('posts/p1','visible-comment','user1','visible',NULL,124)");
 f.sql.exec("INSERT INTO friendbook_cf_comment_reactions VALUES ('posts/p1','visible-comment','blocked','love')");
 f.state.blocked=['blocked'];
 assert.equal((await f.call(route('comments')+'?id=blocked-comment')).status,404);
 assert.equal((await f.call(route('reactors')+'?commentId=blocked-comment')).status,404);
 assert.deepEqual((await f.call(route('reactors'))).body.reactors,[]);
 assert.deepEqual((await f.call(route('reactors')+'?commentId=visible-comment')).body.reactors,[]);
 f.state.blocked=[];
 assert.equal((await f.call(route('reactors'))).body.reactors[0].uid,'blocked');
 assert.equal((await f.call(route('reactors')+'?commentId=visible-comment')).body.reactors[0].uid,'blocked');
});
