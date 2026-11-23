const assert=require('node:assert/strict');
const fs=require('node:fs');
const {Miniflare}=require(process.env.MINIFLARE || 'miniflare');
(async()=>{
 const bundle=fs.readFileSync(process.argv[2],'utf8');
 const source=bundle.replace('var app = createWorker();','var app = createWorker(async token => token);');
 assert.notEqual(source,bundle);
 const mf=new Miniflare({modules:true,script:source,compatibilityDate:'2024-01-01',d1Databases:{DB:'db'},durableObjects:{CALL_RELAY:{className:'CallRelay',useSQLite:true}},bindings:{FIREBASE_PROJECT_ID:'test',ADS_ENABLED_V3:'true'}});
 try {
  const db=await mf.getD1Database('DB');
  for(const file of ['0011_additive_app_documents.sql','0014_additive_firestore_documents.sql','0018_social_guards.sql','0048_direct_chat_calls.sql']) {
   for(const sql of fs.readFileSync(`cloudflare/migrations/${file}`,'utf8').replace(/^--.*$/gm,'').split(';').map(s=>s.trim()).filter(Boolean)) await db.prepare(sql).run();
  }
  for(const uid of ['alice','bob','eve']) await db.prepare('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)').bind(`users/${uid}`,'users',uid,uid,'{}',1).run();
  const request=(path,uid,opts={})=>mf.dispatchFetch(`http://localhost${path}`,{...opts,headers:{...opts.headers,...(uid?{authorization:`Bearer ${uid}`}:{})}});
  const signal=(uid,callId,action,callType)=>request('/v1/chat/call',uid,{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({chatId:'alice_bob',callId,action,peerId:'bob',callType})});
  const open=(uid,call)=>request(`/v1/chat/call/alice_bob/${call}`,uid,{headers:{upgrade:'websocket'}});
  const wait=(socket,event)=>new Promise((resolve,reject)=>{
   const timeout=setTimeout(()=>reject(new Error(`Timed out waiting for ${event}`)),5000);
   socket.addEventListener(event,e=>{clearTimeout(timeout);resolve(e);},{once:true});
  });
  assert.deepEqual(await (await request('/v1/android-config')).json(),{ads_enabled_v3:true});
  assert.equal((await open(null,'video')).status,401);
  for (const type of ['video','voice']) {
   const started=await signal('alice',type,'start',type);assert.equal(started.status,200,await started.text());
   assert.equal((await open('alice',type)).status,403);
   assert.equal((await signal('bob',type,'accept')).status,200);
   assert.equal((await open('eve',type)).status,403);
   const a=await open('alice',type),b=await open('bob',type);
   assert.equal(a.status,101);assert.equal(b.status,101);
   a.webSocket.accept();b.webSocket.accept();
   for(const [sender,receiver,kind] of [[a,b,'audio'],[b,a,type==='video'?'video':'audio']]) {
    const pending=wait(receiver.webSocket,'message'), packet=JSON.stringify({kind,data:'YQ==',seq:1});
    sender.webSocket.send(packet);assert.equal((await pending).data,packet);
   }
   assert.equal((await signal('alice',type,'end')).status,200);
   assert.equal((await open('bob',type)).status,403);
   if(type==='video') {
    await new Promise(r=>setTimeout(r,3600));
    const closed=wait(b.webSocket,'close');b.webSocket.send('renew');assert.equal((await closed).code,1008);
   } else b.webSocket.close(1000);
   a.webSocket.close(1000);
  }
  console.log('Direct call relay smoke passed');
 } finally { await mf.dispose(); }
})().catch(e=>{console.error(e);process.exitCode=1;});
