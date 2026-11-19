import { readD1DocumentFields, hasD1Friendship } from './app-documents.js';
import { trustedGroupOwner } from './social-access.js';

// D1-only authorization. Firebase is used to verify the caller's Auth token in
// worker.js; no source-data request is made to Firestore from this module.
export async function authorizePost({ uid, db }, collection, id, _unused, context, requestUserReads, includeArchivedOwner = false) {
  if (!db || typeof uid !== 'string' || !uid) return false;
  // Coalesce repeated user-profile checks only within this one query request.
  // Never share authorization state across requests or Worker isolates.
  const fields = async path => {
    if (requestUserReads && path.startsWith('users/')) {
      if (!requestUserReads.has(path)) requestUserReads.set(path, readD1DocumentFields(db, path));
      return await requestUserReads.get(path) || {};
    }
    return await readD1DocumentFields(db, path) || {};
  };
  const string = (f, key) => typeof f[key]?.stringValue === 'string' ? f[key].stringValue : '';
  const list = (f, key) => [...(f[key]?.arrayValue?.values || []), ...(key === 'blocked' ? (f.blockedUsers?.arrayValue?.values || []) : [])]
    .map(v => v.stringValue).filter(v => typeof v === 'string');

  const post = await fields(`${collection}/${id}`);
  const owner = string(post, 'authorId');
  if (!owner) return false;
  const hidden=Boolean(post.archived?.booleanValue) || Number(post.trashedAt?.integerValue || post.trashedAt?.doubleValue || post.trashedAt?.timestampValue && Date.parse(post.trashedAt.timestampValue) || 0) > 0;
  if (hidden && !(includeArchivedOwner && collection==='posts' && owner===uid)) return false;
  const me = await fields(`users/${uid}`);
  if (!Object.keys(me).length || me.banned?.booleanValue || me.deactivated?.booleanValue) return false;
  if (context) Object.assign(context, {
    owner, name: string(me, 'displayName'), photo: string(me, 'photoUrl'),
    // Historical profile flags were client-writable and cannot confer moderation authority.
    blocked: list(me, 'blocked'), moderator: false,
  });

  const groupId = string(post, 'groupId');
  if (groupId) {
    const group = await fields(`groups/${groupId}`);
    if (!Object.keys(group).length || list(group, 'bannedMemberIds').includes(uid)) return false;
    const trustedOwner = await trustedGroupOwner(db, `groups/${groupId}`);
    if (!trustedOwner || string(group, 'adminId') !== trustedOwner) return false;
    const member = list(group, 'members').includes(uid);
    const manager = member && (trustedOwner === uid || list(group, 'adminIds').includes(uid) || list(group, 'moderatorIds').includes(uid));
    if (context && manager) context.moderator = true;
    const approval = string(post, 'groupApprovalStatus');
    if (approval && approval !== 'approved' && uid !== owner && !manager) return false;
    const privacy = (string(group, 'privacy') || '').toLowerCase();
    if (privacy !== 'public' && !(privacy === 'private' && (manager || list(group, 'members').includes(uid)))) return false;
  }
  if (owner === uid) return true;
  const author = await fields(`users/${owner}`);
  if (!Object.keys(author).length || author.banned?.booleanValue || author.deactivated?.booleanValue
      || list(me, 'blocked').includes(owner) || list(author, 'blocked').includes(uid)) return false;
  if (post.privacy && typeof post.privacy.stringValue !== 'string') return false;
  const privacy = (string(post, 'privacy') || 'Public').toLowerCase();
  if (privacy === 'public') return true;
  if (privacy !== 'friends') return false;
  return await hasD1Friendship(db, uid, owner) === true;
}
