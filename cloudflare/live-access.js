import { hasD1Friendship, readD1DocumentFields } from './app-documents.js';

const field = (doc, key) => doc?.[key]?.stringValue;
const contains = (doc, key, uid) => (doc?.[key]?.arrayValue?.values || []).some(value => value.stringValue === uid);
const blocks = (doc, uid) => contains(doc, 'blocked', uid) || contains(doc, 'blockedUsers', uid);
const active = doc => doc && doc.banned?.booleanValue !== true && doc.deactivated?.booleanValue !== true;
const suspended = doc => doc?.suspendedLifetime?.booleanValue===true
  || Number(doc?.suspendedUntil?.integerValue ?? doc?.suspendedUntil?.doubleValue)>Date.now();
const id = value => typeof value === 'string' && /^[^/\\\x00-\x1f]{1,128}$/.test(value) && !['.','..'].includes(value);

// Use canonical room/account data, not claims from a WebSocket payload. Call
// this again when renewing access; a previous grant cannot outlive a kick or ban.
export async function authorizeLiveMedia(db, roomId, uid, role) {
  return authorize(db,roomId,uid,role,false);
}
export async function pkGuestGrant(db,room,uid) {
  const battle=await db.prepare(`SELECT id,host_a FROM friendbook_cf_live_pk
    WHERE room_a=? AND room_b=? AND host_b=? AND status='active' AND expires_at>? LIMIT 1`).bind(room,room,uid,Date.now()).first();
  if (!battle || suspended(await readD1DocumentFields(db,`users/${uid}`)) || !await authorizeLiveMedia(db,room,uid,'watch')
      || !await authorizeLiveMedia(db,room,battle.host_a,'publish')) return null;
  return battle.id;
}
export async function coHostGrant(db,roomId,uid) {
  if (suspended(await readD1DocumentFields(db,`users/${uid}`)) || !await authorizeLiveMedia(db,roomId,uid,'watch')) return null;
  const room=await readD1DocumentFields(db,`lives/${roomId}`);
  return field(room,'status')==='live' && field(room,'coHostUid')===uid && field(room,'hostId')!==uid
    && room.coHostAllowed?.booleanValue!==false && id(field(room,'coHostGrant')) ? field(room,'coHostGrant') : null;
}
export async function authorizeLiveRoom(db,roomId,uid,accountReads) {
  return authorize(db,roomId,uid,'watch',true,accountReads);
}
async function authorize(db, roomId, uid, role, metadata, accountReads) {
  if (!id(roomId) || !id(uid) || !['publish','watch'].includes(role)) return false;
  const room = await readD1DocumentFields(db, `lives/${roomId}`);
  const host = field(room, 'hostId');
  const privacy = field(room, 'privacy');
  if (!id(host) || !(field(room,'status')==='live' || (metadata && field(room,'status')==='ended')) || !['public','friends'].includes(privacy)) return false;
  if (role === 'publish' && host !== uid) return false;
  if (uid !== host && contains(room, 'kickedUserIds', uid)) return false;
  const account = user => {
    const key=`users/${user}`;
    if (!accountReads) return readD1DocumentFields(db,key);
    if (!accountReads.has(key)) accountReads.set(key,readD1DocumentFields(db,key));
    return accountReads.get(key);
  };
  const broadcaster = await account(host);
  if (!active(broadcaster) || (role==='publish' && suspended(broadcaster))) return false;
  if (uid === host) return true;
  const viewer = await account(uid);
  if (!active(viewer) || blocks(viewer, host) || blocks(broadcaster, uid)) return false;
  return privacy === 'public' || await hasD1Friendship(db, uid, host) === true;
}
