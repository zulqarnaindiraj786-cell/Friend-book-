import { hasD1Friendship, readD1DocumentFields } from './app-documents.js';

const field = (doc, key) => doc?.[key]?.stringValue;
const contains = (doc, key, uid) => (doc?.[key]?.arrayValue?.values || []).some(value => value.stringValue === uid);
const blocks = (doc, uid) => contains(doc, 'blocked', uid) || contains(doc, 'blockedUsers', uid);
const active = doc => doc && doc.banned?.booleanValue !== true && doc.deactivated?.booleanValue !== true;
const suspended = doc => doc?.suspendedLifetime?.booleanValue===true
  || Number(doc?.suspendedUntil?.integerValue ?? doc?.suspendedUntil?.doubleValue)>Date.now();
const id = value => typeof value === 'string' && /^[^/\\\x00-\x1f]{1,128}$/.test(value) && !['.','..'].includes(value);

// Cache only inside a single authorization operation, never across renewals.
// Concurrent privacy, kick and account changes are re-read on the next check.
function readForCheck(db,path,reads) {
  if (!reads) return readD1DocumentFields(db,path);
  if (!reads.has(path)) reads.set(path,readD1DocumentFields(db,path));
  return reads.get(path);
}

// Use canonical room/account data, not claims from a WebSocket payload. Call
// this again when renewing access; a previous grant cannot outlive a kick or ban.
export async function authorizeLiveMedia(db, roomId, uid, role, reads) {
  return authorize(db,roomId,uid,role,false,reads,Boolean(reads));
}
export async function pkGuestGrant(db,room,uid) {
  const battle=await db.prepare(`SELECT id,host_a FROM friendbook_cf_live_pk
    WHERE room_a=? AND room_b=? AND host_b=? AND status='active' AND expires_at>? LIMIT 1`).bind(room,room,uid,Date.now()).first();
  const reads=new Map();
  if (!battle || suspended(await readForCheck(db,`users/${uid}`,reads)) || !await authorizeLiveMedia(db,room,uid,'watch',reads)
      || !await authorizeLiveMedia(db,room,battle.host_a,'publish',reads)) return null;
  return battle.id;
}
export async function coHostGrant(db,roomId,uid) {
  const reads=new Map();
  if (suspended(await readForCheck(db,`users/${uid}`,reads)) || !await authorizeLiveMedia(db,roomId,uid,'watch',reads)) return null;
  const room=await readForCheck(db,`lives/${roomId}`,reads);
  return field(room,'status')==='live' && field(room,'coHostUid')===uid && field(room,'hostId')!==uid
    && room.coHostAllowed?.booleanValue!==false && id(field(room,'coHostGrant')) ? field(room,'coHostGrant') : null;
}
export async function authorizeLiveRoom(db,roomId,uid,accountReads) {
  return authorize(db,roomId,uid,'watch',true,accountReads);
}
async function authorize(db, roomId, uid, role, metadata, accountReads, cacheRoom = false) {
  if (!id(roomId) || !id(uid) || !['publish','watch'].includes(role)) return false;
  const room = cacheRoom ? await readForCheck(db, `lives/${roomId}`, accountReads) : await readD1DocumentFields(db, `lives/${roomId}`);
  const host = field(room, 'hostId');
  const privacy = field(room, 'privacy');
  if (!id(host) || !(field(room,'status')==='live' || (metadata && field(room,'status')==='ended')) || !['public','friends'].includes(privacy)) return false;
  if (role === 'publish' && host !== uid) return false;
  if (uid !== host && contains(room, 'kickedUserIds', uid)) return false;
  const account = user => {
    const key=`users/${user}`;
    return readForCheck(db,key,accountReads);
  };
  const broadcaster = await account(host);
  if (!active(broadcaster) || (role==='publish' && suspended(broadcaster))) return false;
  if (uid === host) return true;
  const viewer = await account(uid);
  if (!active(viewer) || blocks(viewer, host) || blocks(broadcaster, uid)) return false;
  return privacy === 'public' || await hasD1Friendship(db, uid, host) === true;
}
