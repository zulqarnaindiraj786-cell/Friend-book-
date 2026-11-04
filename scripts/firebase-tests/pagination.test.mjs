import {before,after,test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {initializeTestEnvironment} from '@firebase/rules-unit-testing';
import {collection,doc,query,where,orderBy,limit,limitToLast,startAfter,getDocs,getDoc,writeBatch,setDoc,increment} from 'firebase/firestore';
if(!process.env.FIRESTORE_EMULATOR_HOST)throw Error('Refusing to run without local Firestore emulator');
let env,db;
const id=(n)=>String(n).padStart(3,'0');
before(async()=>{
 env=await initializeTestEnvironment({projectId:'demo-friendbook-pagination',firestore:{rules:fs.readFileSync(new URL('../../firebase/firestore.rules',import.meta.url),'utf8')}});
 await env.clearFirestore();
 await env.withSecurityRulesDisabled(async ctx=>{
  const store=ctx.firestore(),batch=writeBatch(store);
  for(let i=0;i<65;i++){
   batch.set(doc(store,'users','person'+id(i)),{displayName:'Person '+id(i),createdAt:i,following:['viewer'],followers:[]});
   batch.set(doc(store,'notifications','notice'+id(i)),{toUserId:'viewer',createdAt:i,isRead:false});
  }
  for(let i=0;i<45;i++)batch.set(doc(store,'friend_requests','request'+id(i)),{fromUid:'person'+id(i),toUid:'viewer',status:'pending',createdAt:i});
  batch.set(doc(store,'friend_requests','sent'),{fromUid:'viewer',toUid:'person050',status:'pending'});
  batch.set(doc(store,'friends','forward'),{participants:['viewer','person048']});
  batch.set(doc(store,'friends','reverse'),{participants:['person049','viewer']});
  batch.set(doc(store,'posts','post'),{authorId:'viewer',comments:1,commentsMap:{legacy:{id:'legacy',content:'Keep me'}}});
  for(let i=0;i<120;i++)batch.set(doc(store,'posts','post','comments','comment'+id(i)),{createdAt:i,authorId:'viewer',content:'Comment '+i});
  for(let i=0;i<80;i++)batch.set(doc(store,'chats','room','messages','message'+id(i)),{createdAt:i,senderId:'viewer'});
  for(let i=0;i<12;i++){
   batch.set(doc(store,'reels','reel'+id(i)),{createdAt:i});
   batch.set(doc(store,'posts','video'+id(i)),{authorId:'viewer',mediaType:'video',createdAt:i});
  }
  await batch.commit();
 });
 db=env.authenticatedContext('viewer',{email:'viewer@example.invalid'}).firestore();
});
after(async()=>{if(env)await env.cleanup();});
async function allPages(base,size){
 const ids=[];let cursor=null,reads=0;
 for(;;){let q=cursor?query(base,startAfter(cursor),limit(size)):query(base,limit(size));const page=await getDocs(q);reads++;
  assert.ok(page.size<=size);ids.push(...page.docs.map(d=>d.id));if(page.size<size)break;cursor=page.docs.at(-1);assert.ok(reads<30,'Unexpected pagination loop');}
 assert.equal(ids.length,new Set(ids).size);return ids;
}
test('incoming requests:20 +20 +5, no skipped/duplicate IDs',async()=>{
 const ids=await allPages(query(collection(db,'friend_requests'),where('toUid','==','viewer'),where('status','==','pending')),20);assert.equal(ids.length,45);
});
test('suggestion probes match both legacy participant orders without the full friend graph',async()=>{
 const pairs=[['viewer','person048'],['person048','viewer'],['viewer','person049'],['person049','viewer'],['viewer','person050'],['person050','viewer']];
 const friends=await getDocs(query(collection(db,'friends'),where('participants','in',pairs),limit(20)));assert.equal(friends.size,2);
 const sent=await getDocs(query(collection(db,'friend_requests'),where('fromUid','==','viewer'),where('toUid','in',['person048','person049','person050']),where('status','==','pending'),limit(20)));assert.equal(sent.size,1);
 assert.equal(sent.docs[0].data().toUid,'person050');
});
test('notifications and reciprocal followers paginate20 records at a time',async()=>{
 assert.equal((await allPages(query(collection(db,'notifications'),where('toUserId','==','viewer')),20)).length,65);
 assert.equal((await allPages(query(collection(db,'users'),where('following','array-contains','viewer')),20)).length,65);
});
test('comments latest50 and older50/20 use snapshot cursors',async()=>{
 const base=query(collection(db,'posts','post','comments'),orderBy('createdAt','desc'));
 const ids=await allPages(base,50);assert.equal(ids.length,120);assert.equal(ids[0],'comment119');assert.equal(ids.at(-1),'comment000');
});
test('open-chat latest15 plus older15 with reversed query direction',async()=>{
 const messages=collection(db,'chats','room','messages');
 const head=await getDocs(query(messages,orderBy('createdAt','asc'),limitToLast(15)));
 assert.equal(head.size,15);assert.equal(head.docs[0].id,'message065');
 const older=await getDocs(query(messages,orderBy('createdAt','desc'),startAfter(head.docs[0]),limit(15)));
 assert.equal(older.size,15);assert.equal(older.docs[0].id,'message064');assert.equal(older.docs.at(-1).id,'message050');
});
test('Reels metadata first batch reads5 native +5 video posts',async()=>{
 const a=await getDocs(query(collection(db,'reels'),orderBy('createdAt','desc'),limit(5)));
 const b=await getDocs(query(collection(db,'posts'),where('mediaType','==','video'),limit(5)));
 assert.equal(a.size+b.size,10);
});
test('new canonical comment batch preserves old parent maps',async()=>{
 const before=await getDoc(doc(db,'posts','post')),legacy=before.data().commentsMap;
 const fresh={id:'new',createdAt:999,authorId:'viewer',content:'New format'};const batch=writeBatch(db);
 batch.set(doc(db,'posts','post','comments','new'),fresh);batch.set(doc(db,'comments','new'),fresh);
 batch.update(doc(db,'posts','post'),{lastComment:fresh,comments:increment(1),commentsStorageVersion:2});await batch.commit();
 const after=await getDoc(doc(db,'posts','post'));assert.deepEqual(after.data().commentsMap,legacy);assert.equal(after.data().comments,2);
 assert.equal((await getDoc(doc(db,'posts','post','comments','new'))).data().content,'New format');
});
