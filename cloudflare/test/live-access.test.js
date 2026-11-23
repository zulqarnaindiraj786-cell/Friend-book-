import { test } from 'node:test';
import assert from 'node:assert/strict';
import { authorizeLiveMedia, coHostGrant, pkGuestGrant } from '../live-access.js';

function fixture() {
  const room = {hostId:'host',status:'live',privacy:'public'};
  const host = {displayName:'Host'}, viewer = {displayName:'Viewer'};
  const records = {'lives/room':room,'users/host':host,'users/viewer':viewer};
  let friends = false;
  const db = {prepare(sql){return {bind(...keys){return {first:async()=>{
    if (sql.includes('trusted_friendships')) return friends && keys.join(',')==='host,viewer' ? {friend_path:'friends/pair'} : null;
    const data = records[keys[0]];
    return data ? {document_json:JSON.stringify(data)} : null;
  }};}};}};
  return {room,host,viewer,trust:()=>{friends=true;},check:(uid,role)=>authorizeLiveMedia(db,'room',uid,role)};
}

test('cohost and PK grants deduplicate only within each check, then recheck privacy', async () => {
 const room={hostId:'host',status:'live',privacy:'public',coHostUid:'guest',coHostGrant:'grant1',coHostAllowed:true};
 const host={displayName:'Host'},guest={displayName:'Guest'};
 const records={'lives/room':room,'users/host':host,'users/guest':guest};
 let reads=[];
 const db={prepare(sql){return {bind(...args){return {first:async()=>{
   if(sql.includes('friendbook_cf_live_pk')) {reads.push('pk');return {id:'match',host_a:'host'};}
   reads.push(args[0]);const doc=records[args[0]];return doc?{document_json:JSON.stringify(doc)}:null;
 }};}};}};
 assert.equal(await coHostGrant(db,'room','guest'),'grant1');
 assert.deepEqual(reads.sort(),['lives/room','users/guest','users/host'].sort());
 reads=[];assert.equal(await pkGuestGrant(db,'room','guest'),'match');
 assert.deepEqual(reads.sort(),['pk','lives/room','users/guest','users/host'].sort());
 guest.suspendedLifetime=true;
 assert.equal(await coHostGrant(db,'room','guest'),null);
 assert.equal(await pkGuestGrant(db,'room','guest'),null);
 delete guest.suspendedLifetime;guest.blocked=['host'];
 assert.equal(await coHostGrant(db,'room','guest'),null);
 assert.equal(await pkGuestGrant(db,'room','guest'),null);
 delete guest.blocked;room.kickedUserIds=['guest'];
 assert.equal(await coHostGrant(db,'room','guest'),null);
 assert.equal(await pkGuestGrant(db,'room','guest'),null);
 delete room.kickedUserIds;room.coHostGrant='grant2';
 assert.equal(await coHostGrant(db,'room','guest'),'grant2');
 host.banned=true;assert.equal(await coHostGrant(db,'room','guest'),null);
 delete host.banned;room.status='ended';assert.equal(await pkGuestGrant(db,'room','guest'),null);
});

test('live publisher must be the active host; public viewers must have an active account', async () => {
  const f=fixture();
  assert.equal(await f.check('host','publish'),true);
  assert.equal(await f.check('viewer','publish'),false);
  assert.equal(await f.check('viewer','watch'),true);
  assert.equal(await f.check('unknown','watch'),false);
  f.host.banned=true;
  assert.equal(await f.check('host','publish'),false);
  assert.equal(await f.check('viewer','watch'),false);
});

test('friends-only access uses trusted friendship and is revoked on block, kick, or end', async () => {
  const f=fixture();
  f.room.privacy='friends';
  assert.equal(await f.check('viewer','watch'),false);
  f.trust();
  assert.equal(await f.check('viewer','watch'),true);
  f.viewer.blockedUsers=['host'];
  assert.equal(await f.check('viewer','watch'),false);
  delete f.viewer.blockedUsers;
  f.host.blocked=['viewer'];
  assert.equal(await f.check('viewer','watch'),false);
  delete f.host.blocked;
  f.room.kickedUserIds=['viewer'];
  assert.equal(await f.check('viewer','watch'),false);
  f.room.status='ended';
  assert.equal(await f.check('host','publish'),false);
});
