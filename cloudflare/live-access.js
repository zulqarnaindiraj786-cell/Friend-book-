import { hasD1Friendship, readD1DocumentFields } from './app-documents.js';

const field = (doc, key) => doc?.[key]?.stringValue;
const contains = (doc, key, uid) => (doc?.[key]?.arrayValue?.values || []).some(value => value.stringValue === uid);
const blocks = (doc, uid) => contains(doc, 'blocked', uid) || contains(doc, 'blockedUsers', uid);
const active = doc => doc && doc.banned?.booleanValue !== true && doc.deactivated?.booleanValue !== true;
const id = value => typeof value === 'string' && /^[^/\\\x00-\x1f]{1,128}$/.test(value) && !['.','..'].includes(value);

// Use canonical room/account data, not claims from a WebSocket payload. Call
// this again when renewing access; a previous grant cannot outlive a kick or ban.
export async function authorizeLiveMedia(db, roomId, uid, role) {
  return authorize(db,roomId,uid,role,false);
}
export async function authorizeLiveRoom(db,roomId,uid) {
  return authorize(db,roomId,uid,'watch',true);
}
async function authorize(db, roomId, uid, role, metadata) {
  if (!id(roomId) || !id(uid) || !['publish','watch'].includes(role)) return false;
  const room = await readD1DocumentFields(db, `lives/${roomId}`);
  const host = field(room, 'hostId');
  const privacy = field(room, 'privacy');
  if (!id(host) || !(field(room,'status')==='live' || (metadata && field(room,'status')==='ended')) || !['public','friends'].includes(privacy)) return false;
  if (role === 'publish' && host !== uid) return false;
  if (uid !== host && contains(room, 'kickedUserIds', uid)) return false;
  const broadcaster = await readD1DocumentFields(db, `users/${host}`);
  if (!active(broadcaster)) return false;
  if (uid === host) return true;
  const viewer = await readD1DocumentFields(db, `users/${uid}`);
  if (!active(viewer) || blocks(viewer, host) || blocks(broadcaster, uid)) return false;
  return privacy === 'public' || await hasD1Friendship(db, uid, host) === true;
}
