import {before,after,test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {initializeTestEnvironment,assertFails,assertSucceeds} from '@firebase/rules-unit-testing';
import {doc,setDoc,getDoc,updateDoc,deleteDoc,writeBatch,increment} from 'firebase/firestore';
if(!/^(127\.0\.0\.1|localhost):[0-9]+$/.test(process.env.FIRESTORE_EMULATOR_HOST||''))throw Error('Loopback emulator required; production forbidden');
let env,db;
const control='friendbookMigrationControl/interactions';
async function gate(phase){await env.withSecurityRulesDisabled(async ctx=>{
 const ref=doc(ctx.firestore(),control);if(phase===null)await deleteDoc(ref);else await setDoc(ref,{phase});
});}
before(async()=>{
 env=await initializeTestEnvironment({projectId:'demo-friendbook-interaction-fence',firestore:{rules:fs.readFileSync(new URL('../../firebase/firestore.interaction-fence.review.rules',import.meta.url),'utf8')}});
 await env.clearFirestore();
 await env.withSecurityRulesDisabled(async ctx=>{
  const store=ctx.firestore();
  await setDoc(doc(store,'users/viewer'),{displayName:'Viewer',isAdmin:false,isModerator:false,banned:false});
  await setDoc(doc(store,'posts/p'),{authorId:'viewer',content:'original',likes:1,comments:1,reactionsMap:{viewer:{type:'like'}}});
  await setDoc(doc(store,'reels/r'),{authorId:'viewer',likes:0,comments:0});
  await setDoc(doc(store,'posts/p/comments/c'),{authorId:'viewer',content:'preserve canonical'});
  await setDoc(doc(store,'comments/c'),{authorId:'viewer',content:'preserve mirror'});
 });
 db=env.authenticatedContext('viewer',{email:'viewer@example.invalid'}).firestore();
});
after(async()=>{if(env)await env.cleanup();});
test('absent control retains existing OFF Firebase comment/reaction behavior',async()=>{
 await gate(null);
 await assertSucceeds(setDoc(doc(db,'posts/p/reactions/viewer'),{type:'like'}));
 await assertSucceeds(setDoc(doc(db,'posts/p/comments/off'),{authorId:'viewer',content:'OFF comment'}));
 await assertSucceeds(updateDoc(doc(db,'posts/p'),{comments:increment(1)}));
});
test('explicit firebase phase retains existing writes',async()=>{
 await gate('firebase');
 await assertSucceeds(updateDoc(doc(db,'posts/p'),{likes:increment(1)}));
});
test('fenced phase denies old-client interaction writes and atomic canonical/mirror batches',async()=>{
 await gate('fenced');
 await assertFails(setDoc(doc(db,'posts/p/reactions/old'),{type:'love'}));
 await assertFails(setDoc(doc(db,'posts/p/comments/old'),{authorId:'viewer',content:'old write'}));
 await assertFails(setDoc(doc(db,'reels/r/comments/old'),{authorId:'viewer',content:'old reel comment'}));
 await assertFails(setDoc(doc(db,'comments/old'),{authorId:'viewer',content:'old mirror'}));
 const batch=writeBatch(db);
 batch.set(doc(db,'posts/p/comments/batch'),{authorId:'viewer',content:'not committed'});
 batch.set(doc(db,'comments/batch'),{authorId:'viewer',content:'not committed'});
 batch.update(doc(db,'posts/p'),{comments:increment(1)});
 await assertFails(batch.commit());
 assert.equal((await getDoc(doc(db,'posts/p/comments/batch'))).exists(),false);
 assert.equal((await getDoc(doc(db,'comments/batch'))).exists(),false);
});
test('fence covers parent maps, legacy aliases, counts and pins but permits unrelated edits',async()=>{
 await gate('fenced');
 // Earlier OFF-mode tests increment likes to two; an unchanged value is not a write to interactions.
 const unchanged=(await getDoc(doc(db,'posts/p'))).data().likes;
 await assertSucceeds(updateDoc(doc(db,'posts/p'),{likes:unchanged}));
 for(const [field,value] of Object.entries({likes:999,reactionsMap:{u:{type:'like'}},reactions:{u:'love'},likedBy:['u'],comments:5,commentsMap:{c:{}},commentsList:[{}],lastComment:{id:'x'},pinnedCommentId:'x',likeCount:9})){
  await assertFails(updateDoc(doc(db,'posts/p'),{[field]:value}));
 }
 await assertFails(updateDoc(doc(db,'reels/r'),{likedBy:['viewer']}));
 await assertSucceeds(updateDoc(doc(db,'posts/p'),{content:'allowed content edit',shares:1}));
 await assertSucceeds(updateDoc(doc(db,'reels/r'),{caption:'allowed caption'}));
});
test('existing comments stay readable and cannot be deleted through root mirrors, even by client admin',async()=>{
 await gate('fenced');
 await assertFails(deleteDoc(doc(db,'comments/c')));
 await assertFails(deleteDoc(doc(db,'posts/p/comments/c')));
 const admin=env.authenticatedContext('client-admin',{email:'zulqarnaindiraj786@gmail.com'}).firestore();
 await assertFails(deleteDoc(doc(admin,'comments/c')));
 assert.equal((await getDoc(doc(db,'comments/c'))).data().content,'preserve mirror');
 assert.equal((await getDoc(doc(db,'posts/p/comments/c'))).data().content,'preserve canonical');
});
test('post creation accepts empty initializers, rejects seeded interactions during fence',async()=>{
 await gate('fenced');
 await assertSucceeds(setDoc(doc(db,'posts/new'),{authorId:'viewer',likes:0,comments:0,reactionsMap:{},likedBy:[]}));
 await assertSucceeds(setDoc(doc(db,'reels/new'),{authorId:'viewer',likes:0,comments:0,videoUrl:'https://res.cloudinary.com/example/video.mp4'}));
 await assertFails(setDoc(doc(db,'posts/seeded'),{authorId:'viewer',likes:1}));
 await assertFails(setDoc(doc(db,'posts/seeded-map'),{authorId:'viewer',commentsMap:{x:{content:'bypass'}}}));
});
test('profile/chat/message writes remain permitted; migration control is server-only',async()=>{
 await gate('fenced');
 await assertSucceeds(updateDoc(doc(db,'users/viewer'),{displayName:'Still usable',lastSeen:123}));
 await assertSucceeds(setDoc(doc(db,'chats/room'),{participants:['viewer','friend']}));
 await assertSucceeds(setDoc(doc(db,'chats/room/messages/new'),{senderId:'viewer',text:'chat unaffected'}));
 await assertFails(setDoc(doc(db,control),{phase:'firebase'}));
 await assertFails(getDoc(doc(db,control)));
});
test('unknown control phase fails closed and deliberate un-fence restores writes',async()=>{
 await gate('unexpected');
 await assertFails(updateDoc(doc(db,'posts/p'),{likes:1}));
 await gate('firebase');
 await assertSucceeds(setDoc(doc(db,'posts/p/comments/reopened'),{authorId:'viewer',content:'explicitly unfenced'}));
});
