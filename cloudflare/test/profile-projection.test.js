import test from 'node:test';
import assert from 'node:assert/strict';
import {profileReadView,profileWriteAllowed} from '../profile-access.js';

test('locked profile exposes counts and only the viewer follow state, not membership lists',()=>{
  const profile={uid:'bob',profileLocked:true,followers:['alice','eve'],following:['sam'],bio:'private'};
  const alice=profileReadView('users/bob','alice',profile);
  assert.equal(alice.followerCount,2); assert.equal(alice.followingCount,1); assert.equal(alice.followedByMe,true);
  assert.equal(alice.followers,undefined); assert.equal(alice.following,undefined); assert.equal(alice.bio,undefined);
  assert.equal(profileReadView('users/bob','sam',profile).followedByMe,false);
  const unlocked=profileReadView('users/bob','alice',{...profile,profileLocked:false});
  assert.deepEqual(unlocked.followers,['alice','eve']);
  assert.equal(profileWriteAllowed('users/bob','bob',profile,{...profile,followerCount:900}),false);
  assert.equal(profileWriteAllowed('users/bob','bob',profile,{...profile,followedByMe:true}),false);
});
