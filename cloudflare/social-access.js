// Building blocks for social ACLs. Not wired into the document endpoints yet:
// accepting a request also requires an atomic, conditional server-side friendship
// transition; caller-controlled profile flags must never grant admin privileges.
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
