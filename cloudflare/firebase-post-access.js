import { readD1DocumentFields, hasD1Friendship } from './app-documents.js';
import { trustedGroupOwner } from './social-access.js';

// D1-only authorization. Firebase is used to verify the caller's Auth token in
// worker.js; no source-data request is made to Firestore from this module.
export async function authorizePost({ uid, db }, collection, id, _unused, context, requestUserReads, includeArchivedOwner = false) {
  if (!db || typeof uid !== 'string' || !uid) return false;
  // Coalesce shared ACL dependencies only within this one query request.
  // Pending promises must be shared too: a feed authorizes rows concurrently.
  // Post rows themselves remain independently fetched and authorized.
  const requestRead = async (key, read) => {
    if (!requestUserReads) return read();
    if (!requestUserReads.has(key)) requestUserReads.set(key, read());
    return requestUserReads.get(key);
  };
  const fields = async path => {
    if (path.startsWith('users/') || path.startsWith('groups/'))
      return await requestRead(path, () => readD1DocumentFields(db, path)) || {};
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
  // A non-owner can never read an owner-only, unknown or malformed audience.
  // Reject from the freshly loaded canonical post before fetching profiles,
  // group authority and friendship dependencies which cannot grant access.
  // Owners retain the existing audience compatibility behavior.
  const privacy = (string(post, 'privacy') || 'Public').toLowerCase();
  if (owner !== uid && ((post.privacy && typeof post.privacy.stringValue !== 'string')
      || !['public','friends'].includes(privacy))) return false;
  const [me, author] = await Promise.all([
    fields(`users/${uid}`), owner===uid ? Promise.resolve(null) : fields(`users/${owner}`),
  ]);
  if (!Object.keys(me).length || me.banned?.booleanValue || me.deactivated?.booleanValue) return false;
  if (context) Object.assign(context, {
    owner, name: string(me, 'displayName'), photo: string(me, 'photoUrl'),
    // Historical profile flags were client-writable and cannot confer moderation authority.
    blocked: list(me, 'blocked'), moderator: false,
  });

  const groupId = string(post, 'groupId');
  if (groupId) {
    const groupPath = `groups/${groupId}`;
    const [group, trustedOwner] = await Promise.all([fields(groupPath), requestRead(`trustedGroupOwner:${groupPath}`, () => trustedGroupOwner(db, groupPath))]);
    if (!Object.keys(group).length || list(group, 'bannedMemberIds').includes(uid)) return false;
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
  if (!Object.keys(author).length || author.banned?.booleanValue || author.deactivated?.booleanValue
      || list(me, 'blocked').includes(owner) || list(author, 'blocked').includes(uid)) return false;
  if (privacy === 'public') return true;
  if (privacy !== 'friends') return false;
  return await requestRead(`trustedFriendship:${JSON.stringify([uid,owner].sort())}`, () => hasD1Friendship(db, uid, owner)) === true;
}
