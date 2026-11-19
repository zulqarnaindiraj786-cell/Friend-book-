const assert = require('node:assert/strict');
const fs = require('node:fs');
// Uses disposable local D1 and Durable Objects; never targets a deployed Worker.
const {Miniflare} = require(process.env.MINIFLARE || 'miniflare');
(async()=>{
 const bundle=fs.readFileSync(process.argv[2], 'utf8');
 const source=bundle.replace('var app = createWorker();',"var app = createWorker(async token => token);");
 assert.notEqual(source,bundle,'Expected Wrangler bundle with injectable test identity verifier');
 const mf=new Miniflare({modules:true,script:source,compatibilityDate:'2024-01-01',d1Databases:{DB:'db'},durableObjects:{LIVE_RELAY:{className:'LiveRelay',useSQLite:true}},bindings:{FIREBASE_PROJECT_ID:'test'}});
 try {
  const db=await mf.getD1Database('DB');
  for(const file of ['0011_additive_app_documents.sql','0014_additive_firestore_documents.sql','0018_social_guards.sql','0019_trusted_friendships.sql']){
   for(const sql of fs.readFileSync(`cloudflare/migrations/${file}`,'utf8').replace(/^--.*$/gm,'').split(';').map(s=>s.trim()).filter(Boolean)) await db.prepare(sql).run();
  }
  for(const user of ['host','viewer','stranger']) await db.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)').bind(`users/${user}`,'users',user,user,'{}',1).run();
  const dispatch=(path,token,options={})=>mf.dispatchFetch(`http://localhost${path}`,{...options,headers:{...options.headers,...(token?{authorization:`Bearer ${token}`}:{})}});
  const doc='/v1/firestore/document?path=lives/testroom';
  const write=(method,body)=>dispatch(doc,'host',{method,headers:{'content-type':'application/json'},body:JSON.stringify(body)});
  assert.equal((await write('PUT',{data:{hostId:'host',status:'live',privacy:'friends'}})).status,200);
  const open=(uid,role)=>dispatch(`/v1/live/testroom/${role}`,uid,{headers:{upgrade:'websocket'}});
  assert.equal((await open(null,'watch')).status,401);
  assert.equal((await open('stranger','watch')).status,403);
  assert.equal((await open('viewer','publish')).status,403);
  assert.equal((await write('PATCH',{fields:{privacy:'public'}})).status,200);
  const p=await open('host','publish'),v=await open('viewer','watch');
  assert.equal(p.status,101);assert.equal(v.status,101);
  p.webSocket.accept();v.webSocket.accept();
  const received=[];
  v.webSocket.addEventListener('message',e=>received.push(e.data));
  const audio=JSON.stringify({kind:'audio',data:'dGVzdA==',seq:1}),video=JSON.stringify({kind:'video',data:'dGVzdA==',seq:1});
  p.webSocket.send(audio);await new Promise(r=>setTimeout(r,80));p.webSocket.send(video);
  for(let i=0;i<20 && received.length<2;i++) await new Promise(r=>setTimeout(r,50));
  assert.deepEqual(received,[audio,video]);
  assert.equal((await write('PATCH',{fields:{status:'ended'}})).status,200);
  assert.equal((await open('viewer','watch')).status,403);
  console.log('Local Cloudflare runtime: signed-in room creation, role/privacy enforcement, audio/video packet relay, ended-room denial passed.');
 } finally {await mf.dispose();}
})().catch(e=>{console.error(e);process.exitCode=1;});
