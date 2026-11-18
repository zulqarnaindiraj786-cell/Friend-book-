import { readD1DocumentFields, hasD1Friendship } from './app-documents.js';
import { trustedGroupOwner } from './social-access.js';

// D1-only authorization. Firebase is used to verify the caller's Auth token in
// worker.js; no source-data request is made to Firestore from this module.
export async function authorizePost({ uid, db }, collection, id, _unused, context) {
  if (!db || typeof uid !== 'string' || !uid) return false;
  const fields = async path => await readD1DocumentFields(db, path) || {};
  const string = (f, key) => typeof f[key]?.stringValue === 'string' ? f[key].stringValue : '';
  const list = (f, key) => [...(f[key]?.arrayValue?.values || []), ...(key === 'blocked' ? (f.blockedUsers?.arrayValue?.values || []) : [])]
    .map(v => v.stringValue).filter(v => typeof v === 'string');

  const post = await fields(`${collection}/${id}`);
  const owner = string(post, 'authorId');
  if (!owner || post.archived?.booleanValue || Number(post.trashedAt?.integerValue || 0) > 0) return false;
  const me = await fields(`users/${uid}`);
  if (!Object.keys(me).length || me.banned?.booleanValue || me.deactivated?.booleanValue) return false;
  if (context) Object.assign(context, {
    owner, name: string(me, 'displayName'), photo: string(me, 'photoUrl'),
    blocked: list(me, 'blocked'), moderator: !!(me.isAdmin?.booleanValue || me.isModerator?.booleanValue),
  });

  const groupId = string(post, 'groupId');
  if (groupId) {
    const group = await fields(`groups/${groupId}`);
    if (!Object.keys(group).length || list(group, 'bannedMemberIds').includes(uid)) return false;
    let trustedOwner;
    try { trustedOwner = await trustedGroupOwner(db, `groups/${groupId}`); } catch { return false; }
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
