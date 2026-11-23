// Social ACLs shared by the document endpoints. Server-owned ledgers determine
// trusted friendship and group ownership; profile flags do not grant authority.
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
const sameExcept = (a, b, keys) => [...new Set([...Object.keys(a || {}), ...Object.keys(b || {})])]
  .every(key => keys.has(key) || same(a?.[key], b?.[key]));
const validUid = uid => typeof uid === 'string' && uid.length > 0 && uid.length <= 128 && !/[\/\x00-\x1f]/.test(uid);

export function canReadFriendRequest(uid, request) {
  return validUid(uid) && !!request && (request.fromUid === uid || request.toUid === uid);
}

export function canCreateFriendRequest(uid, request) {
  if (!validUid(uid) || !request || request.fromUid !== uid || !validUid(request.toUid) || request.toUid === uid || request.status !== 'pending') return false;
  const allowed = new Set(['fromUid', 'toUid', 'fromUserName', 'fromUserPhotoUrl', 'status', 'createdAt']);
  if (!Object.keys(request).every(key => allowed.has(key))) return false;
  return (request.fromUserName === undefined || (typeof request.fromUserName === 'string' && request.fromUserName.length <= 200))
    && (request.fromUserPhotoUrl === undefined || (typeof request.fromUserPhotoUrl === 'string' && request.fromUserPhotoUrl.length <= 2048));
}

// Status acceptance is intentionally not authorized here: it must be committed
// together with a verified friendship, counter updates and replay protection.
export function canDeleteFriendRequest(uid, before) {
  return canReadFriendRequest(uid, before) && before.status === 'pending';
}

export function isSelfArrayChange(before, after, uid) {
  if (!validUid(uid) || !Array.isArray(before) || !Array.isArray(after)) return false;
  if (!before.every(validUid) || !after.every(validUid)) return false;
  if (before.filter(v => v === uid).length > 1 || after.filter(v => v === uid).length > 1) return false;
  return same(before.filter(v => v !== uid), after.filter(v => v !== uid));
}

// Group members may only request admission, accept an existing invitation, or
// leave; they cannot edit somebody else's membership or grant themselves roles.
export function canSelfManageGroup(uid, before, after) {
  if (!validUid(uid) || !before || !after || !Array.isArray(before.bannedMemberIds || []) || (before.bannedMemberIds || []).includes(uid)) return false;
  const fields = ['members', 'pendingMemberIds', 'invitedUids'];
  if (!sameExcept(before, after, new Set(fields))) return false;
  for (const key of fields) {
    if ((key in before && !Array.isArray(before[key])) || (key in after && !Array.isArray(after[key]))) return false;
    if (!isSelfArrayChange(before[key] || [], after[key] || [], uid)) return false;
  }
  const contains = (doc, key) => (doc[key] || []).includes(uid);
  const wasMember = contains(before, 'members'), member = contains(after, 'members');
  const wasPending = contains(before, 'pendingMemberIds'), pending = contains(after, 'pendingMemberIds');
  const invited = contains(before, 'invitedUids'), invitationRemains = contains(after, 'invitedUids');
  // A user cannot invent an invite. New membership requires a public group or a prior invitation.
  if (!invited && invitationRemains) return false;
  if (!wasMember && member && !invited && (String(before.privacy || '').toLowerCase() !== 'public' || before.requireJoinApproval === true)) return false;
  if (!wasPending && pending && (wasMember || member)) return false;
  return true;
}

// Existing relationship documents are visible only to their participants.
// Return a narrow projection: historical records may contain private extra fields.
export function friendshipReadView(uid, doc) {
  if (!validUid(uid) || !doc || !Array.isArray(doc.participants) || doc.participants.length !== 2
      || !doc.participants.every(validUid) || doc.participants[0] === doc.participants[1]
      || !doc.participants.includes(uid)) return null;
  const view = {participants: doc.participants};
  if (doc.createdAt !== undefined) view.createdAt = doc.createdAt;
  if (typeof doc.id === 'string') view.id = doc.id;
  return view;
}

const groupLists = ['members','adminIds','moderatorIds','pendingMemberIds','invitedUids','bannedMemberIds'];
const groupHas = (g,key,uid) => Array.isArray(g?.[key]) && g[key].includes(uid);
const groupArraysValid = g => groupLists.every(k => Array.isArray(g?.[k]) && g[k].every(validUid) && new Set(g[k]).size===g[k].length);
export function canCreateGroup(uid, group) {
  return validUid(uid) && !!group && groupArraysValid(group) && group.adminId===uid
    && same(group.members,[uid]) && same(group.adminIds,[uid])
    && ['moderatorIds','pendingMemberIds','invitedUids','bannedMemberIds'].every(k=>group[k].length===0)
    && ['public','private'].includes(String(group.privacy||'').toLowerCase())
    && typeof group.name==='string' && group.name.trim().length>0 && group.name.length<=200;
}
export function canManageGroup(uid, owner, group) {
  return validUid(uid) && validUid(owner) && group?.adminId===owner && groupArraysValid(group)
    && groupHas(group,'members',uid) && !groupHas(group,'bannedMemberIds',uid)
    && (uid===owner || groupHas(group,'adminIds',uid));
}
export function canUpdateGroup(uid, owner, before, after) {
  if (!validUid(uid) || !validUid(owner) || !before || !after || before.adminId!==owner || after.adminId!==owner
      || !groupArraysValid(before) || !groupArraysValid(after)
      || !groupHas(after,'members',owner) || !groupHas(after,'adminIds',owner)
      || groupHas(after,'bannedMemberIds',owner)
      || !after.adminIds.every(id=>after.members.includes(id))
      || !after.moderatorIds.every(id=>after.members.includes(id))
      || after.bannedMemberIds.some(id=>after.members.includes(id))
      || !['public','private'].includes(String(after.privacy||'').toLowerCase())) return false;
  if (canManageGroup(uid,owner,before)) return true;
  // Self-service also lets a moderator remove their own role when leaving.
  if (sameExcept(before,after,new Set(['members','pendingMemberIds','invitedUids','adminIds','moderatorIds']))
      && ['adminIds','moderatorIds'].every(key => isSelfArrayChange(before[key],after[key],uid)
        && (groupHas(before,key,uid) || !groupHas(after,key,uid)))) {
    const stripped={...before,adminIds:after.adminIds,moderatorIds:after.moderatorIds};
    if (canSelfManageGroup(uid,stripped,after)) return true;
  }
  if (groupHas(before,'members',uid) && groupHas(before,'moderatorIds',uid) && !groupHas(before,'bannedMemberIds',uid)) {
    const mutable=new Set(['members','pendingMemberIds','invitedUids','bannedMemberIds']);
    if (!sameExcept(before,after,mutable)) return false;
    const protectedUsers=new Set([owner,...before.adminIds,...before.moderatorIds]);
    for (const key of mutable) for (const id of protectedUsers)
      if (groupHas(before,key,id)!==groupHas(after,key,id)) return false;
    return true;
  }
  // A regular member may remove their own moderator/admin roles when leaving,
  // but may never add them, touch somebody else's roles or edit group metadata.
  if (!sameExcept(before,after,new Set(['members','pendingMemberIds','invitedUids','adminIds','moderatorIds']))) return false;
  for (const key of ['adminIds','moderatorIds']) {
    if (!isSelfArrayChange(before[key],after[key],uid) || (!groupHas(before,key,uid) && groupHas(after,key,uid))) return false;
  }
  const stripped={...before,adminIds:after.adminIds,moderatorIds:after.moderatorIds};
  return canSelfManageGroup(uid,stripped,after);
}

// Discovery exposes group metadata and only the caller's own invitation/request.
// Existing untrusted groups never acquire a privileged manager projection.
export function groupReadView(uid,group,trustedOwner) {
  if (!validUid(uid) || !group || groupHas(group,'bannedMemberIds',uid)) return null;
  if (canManageGroup(uid,trustedOwner,group) || (trustedOwner && group.adminId===trustedOwner && groupHas(group,'moderatorIds',uid) && groupHas(group,'members',uid))) return group;
  const view={};
  const fields=['id','name','about','category','rules','privacy','requireJoinApproval','requirePostApproval','postPermission','adminId','adminIds','moderatorIds','photoUrl','coverUrl','createdAt','pinnedPostId'];
  for (const key of fields) if (group[key]!==undefined) view[key]=group[key];
  view.members=groupHas(group,'members',uid) && Array.isArray(group.members) ? group.members : [];
  view.pendingMemberIds=groupHas(group,'pendingMemberIds',uid)?[uid]:[];
  view.invitedUids=groupHas(group,'invitedUids',uid)?[uid]:[];
  view.bannedMemberIds=[];
  return view;
}
export async function trustedGroupOwner(db,path) {
  const row=await db.prepare('SELECT owner_uid FROM friendbook_cf_trusted_groups WHERE group_path=?').bind(path).first();
  return typeof row?.owner_uid==='string' ? row.owner_uid : null;
}
