// Public profiles are projections, not raw account documents. Unknown fields are private.
const PUBLIC_FIELDS = new Set([
  'uid','id','displayName','photoUrl','coverUrl','bio','professionalMode',
  'isVerified','tickLevel','profileLocked','profilePicGuard','createdAt',
  'pinnedPostId','friendsCount','followers','following'
]);
const SERVER_FIELDS = new Set([
  'isAdmin','isModerator','banned','deactivated','isVerified','tickLevel',
  'suspendedUntil','suspendedLifetime','stars','dailyStreak','lastBonusAt',
  'verificationStatus','admin','role','friendsCount','followers','following',
  'followerCount','followingCount','followedByMe'
]);
const ADMIN_FIELDS = new Set([...PUBLIC_FIELDS, 'email','banned','deactivated','isAdmin','isModerator','suspendedUntil','suspendedLifetime']);
// Summary is computed from server-maintained arrays, never a client-written count.
// It allows a locked profile to show counts and the viewer's own follow state
// without exposing the private membership lists.
function withFollowSummary(view,data,uid) {
  const followers=Array.isArray(data.followers)?data.followers:[];
  const following=Array.isArray(data.following)?data.following:[];
  view.followerCount=followers.length;
  view.followingCount=following.length;
  view.followedByMe=followers.includes(uid);
  return view;
}
export function profileReadView(path, uid, data, admin = false) {
  const parts=path.split('/');
  if ((parts[0] === 'users' || parts[0] === 'pushDevices') && parts[1] === uid) return data;
  if (admin && parts[0]==='users' && parts.length===2 && data) {
    const view={};
    for (const [key,value] of Object.entries(data)) if (ADMIN_FIELDS.has(key)) view[key]=value;
    return withFollowSummary(view,data,uid);
  }
  if (parts[0] === 'stories' && data && data.authorId !== uid) {
    const {viewedBy, reactions, ...publicStory}=data;
    return publicStory;
  }
  if (parts[0] !== 'users' && parts[0] !== 'pushDevices') return data;
  if (parts[1] === uid) return data;
  if (parts[0] !== 'users' || parts.length !== 2 || !data || data.deactivated === true || data.banned === true) return null;
  const result={};
  for (const [k,v] of Object.entries(data)) {
    if (PUBLIC_FIELDS.has(k) && !(data.profileLocked === true && ['bio','coverUrl','pinnedPostId','followers','following'].includes(k))) result[k]=v;
  }
  return withFollowSummary(result,data,uid);
}
export function profileWriteAllowed(path, uid, before, after) {
  const parts=path.split('/');
  if (parts[0] !== 'users' && parts[0] !== 'pushDevices') return true;
  if (parts[1] !== uid) return false;
  if (parts[0] !== 'users' || parts.length !== 2 || !after) return true;
  if (after.uid != null && after.uid !== uid) return false;
  for (const key of SERVER_FIELDS) {
    const old=before?.[key], value=after[key];
    if (before) {
      if (JSON.stringify(old) !== JSON.stringify(value)) return false;
    } else if (value !== undefined && value !== null && value !== false && value !== 0 && value !== '' && !(key === 'tickLevel' && value === 'none') && !(['followers','following'].includes(key) && Array.isArray(value) && value.length === 0)) return false;
  }
  return true;
}
