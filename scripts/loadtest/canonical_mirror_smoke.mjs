#!/usr/bin/env node
// End-to-end canonical-mode smoke using disposable Firebase Auth identities.
// Creates synthetic Worker documents only, then removes every exact test path
// and Auth identity in finally. Never logs emails, UIDs, tokens, or document data.
import {randomUUID,randomBytes} from 'node:crypto';

const WORKER=(process.env.WORKER_URL||'https://rough-firefly-dd7b.zulqarnaindiraj786.workers.dev').replace(/\/$/,'');
const PROJECT=process.env.FIREBASE_PROJECT_ID||'friendbook-17551';
const API_KEY=process.env.FIREBASE_API_KEY;
const CF_TOKEN=process.env.CLOUDFLARE_API_TOKEN;
const D1_URL='https://api.cloudflare.com/client/v4/accounts/92f3d04598822de2e49e14561218d090/d1/database/e1c605ca-a50b-46c6-9deb-56dd1f70d163/query';

async function firebase(path,body){
  const r=await fetch(`https://identitytoolkit.googleapis.com/v1/${path}?key=${API_KEY}`,{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify(body),signal:AbortSignal.timeout(30000)});
  const data=await r.json().catch(()=>({}));
  if(!r.ok)throw new Error(`Firebase ${path} HTTP ${r.status}`);
  return data;
}
async function worker(path,token,method='GET',body){
  const r=await fetch(`${WORKER}${path}`,{method,headers:{authorization:`Bearer ${token}`,'content-type':'application/json'},...(body===undefined?{}:{body:JSON.stringify(body)}),signal:AbortSignal.timeout(30000)});
  const data=await r.json().catch(()=>({}));
  return {status:r.status,data};
}
async function d1(sql,params=[]){
  const r=await fetch(D1_URL,{method:'POST',headers:{authorization:`Bearer ${CF_TOKEN}`,'content-type':'application/json'},body:JSON.stringify({sql,params}),signal:AbortSignal.timeout(30000)});
  const data=await r.json().catch(()=>({}));
  const item=data?.result?.[0];
  if(!r.ok||!data.success||!item?.success||!Array.isArray(item.results))throw new Error('D1 cleanup/verification failed');
  return item;
}
function ensure(ok,phase){if(!ok)throw new Error(`Smoke assertion failed: ${phase}`);}
function docPath(path){return `/v1/firestore/document?path=${encodeURIComponent(path)}`;}
async function createAccount(){
  const email=`cf-canonical-smoke-${randomUUID()}@example.com`;
  const password=randomBytes(32).toString('base64url');
  const auth=await firebase('accounts:signUp',{email,password,returnSecureToken:true});
  if(!auth.localId||!auth.idToken)throw new Error('Firebase disposable signup incomplete');
  return {uid:auth.localId,token:auth.idToken};
}
async function main(){
  if(!API_KEY||!CF_TOKEN)throw new Error('Smoke credentials unavailable');
  const marker=randomUUID().replaceAll('-','');
  let a=null,b=null,requestPath=null,friendPath=`friends/cfsmoke_${marker}`,chatPath=null,messagePath=null;
  let cleaned=false;
  try{
    a=await createAccount();b=await createAccount();
    ensure(a.uid!==b.uid,'distinct disposable identities');
    for(const account of [a,b]){
      const p=docPath(`users/${account.uid}`);
      const created=await worker(p,account.token,'PUT',{data:{uid:account.uid,displayName:'Canonical smoke user',friendsCount:0,createdAt:Date.now()}});
      ensure(created.status>=200&&created.status<300&&created.data?.path===`users/${account.uid}`,'profile write/read store');
      const read=await worker(p,account.token);
      ensure(read.status===200&&read.data?.data?.uid===account.uid,'profile read-back');
    }
    console.log('SMOKE_PHASE: two disposable canonical profiles passed write/read.');

    const createReq=await worker('/v1/firestore/document?parent=friend_requests',a.token,'POST',{data:{
      fromUid:a.uid,toUid:b.uid,fromUserName:'Canonical smoke',status:'pending',createdAt:{__fieldValue:'serverTimestamp'}
    }});
    ensure(createReq.status===201&&typeof createReq.data?.path==='string'&&createReq.data.path.startsWith('friend_requests/'),'friend request create');
    requestPath=createReq.data.path;
    const retry=await worker('/v1/firestore/document?parent=friend_requests',a.token,'POST',{data:{
      fromUid:a.uid,toUid:b.uid,fromUserName:'Canonical smoke',status:'pending',createdAt:{__fieldValue:'serverTimestamp'}
    }});
    ensure(retry.status===409,'duplicate request retry guard');
    const accept=await worker('/v1/firestore/batch',b.token,'POST',{operations:[
      {path:requestPath,op:'update',fields:{status:'accepted'}},
      {path:friendPath,op:'set',data:{participants:[a.uid,b.uid],createdAt:{__fieldValue:'serverTimestamp'}}},
      {path:`users/${a.uid}`,op:'update',fields:{friendsCount:{__fieldValue:'increment',value:1}}},
      {path:`users/${b.uid}`,op:'update',fields:{friendsCount:{__fieldValue:'increment',value:1}}},
    ]});
    ensure(accept.status===200&&accept.data?.ok===true,'friend request acceptance');
    const friend=await worker(docPath(friendPath),a.token);
    ensure(friend.status===200&&friend.data?.data?.participants?.includes(b.uid),'canonical friendship read');
    const unfriend=await worker('/v1/firestore/batch',b.token,'POST',{operations:[
      {path:friendPath,op:'delete'},
      {path:`users/${a.uid}`,op:'update',fields:{friendsCount:{__fieldValue:'increment',value:-1}}},
      {path:`users/${b.uid}`,op:'update',fields:{friendsCount:{__fieldValue:'increment',value:-1}}},
    ]});
    ensure(unfriend.status===200,'unfriend cleanup transition');
    console.log('SMOKE_PHASE: friend request create, duplicate retry, accept, and unfriend passed.');

    const chat=await worker('/v1/firestore/document?parent=chats',a.token,'POST',{data:{participants:[a.uid,b.uid],isGroup:false}});
    ensure((chat.status===200||chat.status===201)&&typeof chat.data?.path==='string','direct chat create');
    chatPath=chat.data.path;
    const message=await worker(`/v1/firestore/document?parent=${encodeURIComponent(`${chatPath}/messages`)}`,a.token,'POST',{data:{
      chatId:chat.data.id,senderId:a.uid,text:'canonical mirror smoke',createdAt:{__fieldValue:'serverTimestamp'}
    }});
    ensure((message.status===200||message.status===201)&&typeof message.data?.path==='string','chat message create');
    messagePath=message.data.path;
    const messageRead=await worker(docPath(messagePath),b.token);
    ensure(messageRead.status===200&&messageRead.data?.data?.senderId===a.uid,'chat member message read');
    console.log('SMOKE_PHASE: canonical direct chat and member message read passed.');
  }finally{
    const paths=[a?.uid&&`users/${a.uid}`,b?.uid&&`users/${b.uid}`,requestPath,friendPath,chatPath,messagePath].filter(Boolean);
    let d1CleanupOk=true;
    if(paths.length){
      const marks=paths.map(()=>'?').join(',');
      // Exact random test paths only. This is the emergency cleanup after route tests.
      try{
        await d1(`DELETE FROM friendbook_cf_firestore_documents WHERE path IN (${marks})`,paths);
        const mirror=await d1("SELECT name FROM sqlite_master WHERE type='table' AND name='friendbook_cf_documents'");
        if(mirror.results.length)await d1(`DELETE FROM friendbook_cf_documents WHERE path IN (${marks})`,paths);
        if(a&&b){const [low,high]=[a.uid,b.uid].sort();await d1('DELETE FROM friendbook_cf_trusted_friendships WHERE uid_low=? AND uid_high=?',[low,high]);}
        const remaining=await d1(`SELECT COUNT(*) AS n FROM friendbook_cf_firestore_documents WHERE path IN (${marks})`,paths);
        d1CleanupOk=remaining.results?.[0]?.n===0;
      }catch{d1CleanupOk=false;}
    }
    let authCleanupOk=true;
    for(const account of [a,b].filter(Boolean)){
      try{await firebase('accounts:delete',{idToken:account.token});}catch{authCleanupOk=false;}
    }
    if(!authCleanupOk||!d1CleanupOk)throw new Error('Disposable smoke cleanup failed; inspect the smoke run immediately.');
    cleaned=true;
    console.log('SMOKE_CLEANUP: synthetic D1 paths and Firebase Auth identities removed and verified.');
  }
  if(!cleaned)throw new Error('Smoke cleanup incomplete');
  console.log('Canonical-mode production smoke passed.');
}
main().catch(()=>{console.error('::error::Canonical-mode smoke failed or cleanup needs attention; no user records were intentionally targeted.');process.exitCode=1;});
