import { test } from 'node:test';
import assert from 'node:assert/strict';
import { authorizeLiveMedia } from '../live-access.js';

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
