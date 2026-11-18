import test from 'node:test';
import assert from 'node:assert/strict';
import { canReadFriendRequest, canCreateFriendRequest, canDeleteFriendRequest, canSelfManageGroup } from '../social-access.js';

test('friend requests are attributable, pending and visible only to their participants', () => {
  const request = {fromUid:'alice',toUid:'bob',status:'pending',fromUserName:'Alice'};
  assert.equal(canCreateFriendRequest('alice',request),true);
  assert.equal(canCreateFriendRequest('mallory',request),false);
  assert.equal(canCreateFriendRequest('alice',{...request,status:'accepted'}),false);
  assert.equal(canCreateFriendRequest('alice',{...request,toUid:'alice'}),false);
  assert.equal(canCreateFriendRequest('alice',{...request,admin:true}),false);
  assert.equal(canReadFriendRequest('alice',request),true);
  assert.equal(canReadFriendRequest('bob',request),true);
  assert.equal(canReadFriendRequest('mallory',request),false);
  assert.equal(canDeleteFriendRequest('bob',request),true);
  assert.equal(canDeleteFriendRequest('bob',{...request,status:'accepted'}),false);
});

test('group self-service cannot promote, invite, ban or alter another member', () => {
  const group={privacy:'Private',requireJoinApproval:true,adminId:'alice',adminIds:['alice'],moderatorIds:[],members:['alice'],pendingMemberIds:[],invitedUids:[],bannedMemberIds:[]};
  assert.equal(canSelfManageGroup('bob',group,{...group,pendingMemberIds:['bob']}),true);
  assert.equal(canSelfManageGroup('bob',group,{...group,members:['alice','bob']}),false);
  assert.equal(canSelfManageGroup('bob',group,{...group,adminIds:['alice','bob']}),false);
  assert.equal(canSelfManageGroup('bob',group,{...group,invitedUids:['bob']}),false);
  assert.equal(canSelfManageGroup('bob',group,{...group,members:['bob']}),false);
  assert.equal(canSelfManageGroup('bob',{...group,bannedMemberIds:['bob']},{...group,bannedMemberIds:['bob'],pendingMemberIds:['bob']}),false);
  const invited={...group,invitedUids:['bob']};
  assert.equal(canSelfManageGroup('bob',invited,{...invited,members:['alice','bob'],invitedUids:[]}),true);
  const publicGroup={...group,privacy:'Public',requireJoinApproval:false};
  assert.equal(canSelfManageGroup('bob',publicGroup,{...publicGroup,members:['alice','bob']}),true);
});
