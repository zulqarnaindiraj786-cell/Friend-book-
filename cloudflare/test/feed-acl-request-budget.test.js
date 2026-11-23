import test from 'node:test';
import assert from 'node:assert/strict';
import {authorizePost} from '../firebase-post-access.js';

function fixture(){
  const docs=new Map([
    ['users/viewer',{uid:'viewer'}],['users/author',{uid:'author'}],
    ['groups/room',{adminId:'author',privacy:'private',members:['viewer','author']}],
  ]);
  for(let i=0;i<20;i++)docs.set('posts/p'+i,{authorId:'author',groupId:'room',privacy:'Friends',groupApprovalStatus:'approved'});
  const calls={documents:new Map(),owner:0,friendship:0};
  let owner='author',friends=true,fail=false;
  const db={prepare(q){return {bind(...args){return {first:async()=>{
    await Promise.resolve();
    if(q.includes('friendbook_cf_firestore_documents')){
      const key=args[0];calls.documents.set(key,(calls.documents.get(key)||0)+1);
      if(fail && key==='groups/room')throw Error('storage unavailable');
      return docs.has(key)?{document_json:JSON.stringify(docs.get(key))}:null;
    }
    if(q.includes('friendbook_cf_trusted_groups')){calls.owner++;return owner?{owner_uid:owner}:null;}
    if(q.includes('friendbook_cf_trusted_friendships')){calls.friendship++;return friends?{friend_path:'friends/pair'}:null;}
    throw Error('unexpected query');
  }};}};}};
  return {db,docs,calls,setOwner:value=>owner=value,setFriends:value=>friends=value,setFail:value=>fail=value};
}
const page=(f,cache)=>Promise.all(Array.from({length:20},(_,i)=>authorizePost({uid:'viewer',db:f.db},'posts','p'+i,undefined,{},cache)));

test('parallel feed ACL checks share group and friendship dependencies, not post rows',async()=>{
  const f=fixture();
  assert.ok((await page(f,new Map())).every(Boolean));
  assert.equal(f.calls.owner,1);assert.equal(f.calls.friendship,1);
  assert.equal(f.calls.documents.get('groups/room'),1);
  assert.equal(f.calls.documents.get('users/viewer'),1);
  assert.equal(f.calls.documents.get('users/author'),1);
  for(let i=0;i<20;i++)assert.equal(f.calls.documents.get('posts/p'+i),1);
  const reference=fixture();
  assert.deepEqual(await page(reference),Array(20).fill(true));
  assert.equal(reference.calls.owner,20);assert.equal(reference.calls.friendship,20);
  assert.equal(reference.calls.documents.get('groups/room'),20);
});

test('new request sees membership, trust, block, ban and friendship revocation',async()=>{
  for(const revoke of [f=>f.docs.get('groups/room').members=['author'],f=>f.setOwner(null),
    f=>f.setFriends(false),f=>f.docs.get('users/author').blocked=['viewer'],
    f=>f.docs.get('users/viewer').banned=true]){
    const f=fixture();assert.ok((await page(f,new Map())).every(Boolean));
    revoke(f);assert.ok((await page(f,new Map())).every(value=>value===false));
  }
});

test('request-local ACL storage errors never become permissions and next request retries',async()=>{
  const f=fixture();f.setFail(true);
  await assert.rejects(page(f,new Map()),/storage unavailable/);
  f.setFail(false);assert.ok((await page(f,new Map())).every(Boolean));
  assert.equal(f.calls.documents.get('groups/room'),2);
});

test('denied audiences stop at the canonical post without profile/group/friendship fan-out',async()=>{
  for (const privacy of ['Only me','Private','unknown',true,12,{},[],null]) {
    const f=fixture();
    for(let i=0;i<20;i++) {
      f.docs.set('users/author'+i,{uid:'author'+i});
      Object.assign(f.docs.get('posts/p'+i),{authorId:'author'+i,privacy});
    }
    assert.deepEqual(await page(f,new Map()),Array(20).fill(false));
    assert.equal(f.calls.documents.size,20,'only the twenty independent canonical post reads');
    assert.equal(f.calls.owner,0);assert.equal(f.calls.friendship,0);
    for(let i=0;i<20;i++)assert.equal(f.calls.documents.get('posts/p'+i),1);
  }
});

test('early audience rejection preserves owner compatibility and allowed audience dependencies',async()=>{
  for(const privacy of ['Only me','unknown',true,null]) {
    const f=fixture();
    f.docs.get('posts/p0').privacy=privacy;
    assert.equal(await authorizePost({uid:'author',db:f.db},'posts','p0',undefined,{},new Map()),true);
    assert.equal(f.calls.owner,1,'owner still needs trusted group authorization');
    f.docs.get('groups/room').bannedMemberIds=['author'];
    assert.equal(await authorizePost({uid:'author',db:f.db},'posts','p0',undefined,{},new Map()),false);
  }
  for(const privacy of [undefined,'','PUBLIC','Friends']) {
    const f=fixture();
    if(privacy===undefined)delete f.docs.get('posts/p0').privacy;
    else f.docs.get('posts/p0').privacy=privacy;
    assert.equal(await authorizePost({uid:'viewer',db:f.db},'posts','p0',undefined,{},new Map()),true);
    assert.equal(f.calls.documents.get('users/viewer'),1);
    assert.equal(f.calls.documents.get('users/author'),1);
    assert.equal(f.calls.owner,1);
    assert.equal(f.calls.friendship,privacy==='Friends'?1:0);
    f.docs.get('users/author').blocked=['viewer'];
    assert.equal(await authorizePost({uid:'viewer',db:f.db},'posts','p0',undefined,{},new Map()),false);
  }
});
