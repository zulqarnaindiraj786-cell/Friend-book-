import { authorizeLiveRoom } from './live-access.js';
import { isSelfArrayChange } from './social-access.js';
import { authorizePost } from './firebase-post-access.js';

// Access is denied by default: add a collection only after its read and write policy exists.
const SUPPORTED = new Set(['users','pushDevices','chats','messages','notifications','posts','reels','stories','friend_requests','friends','payments','groups','reports','feedback','config','events','pages','lives']);
export function supportedDocumentPath(path) {
  const p=path.split('/');
  if (!SUPPORTED.has(p[0])) return false;
  if (p[0]==='lives') return p.length===2 || (p.length===4 && p[2]==='chat');
  if (p[0]==='chats') return p.length===2 || (p.length===4 && p[2]==='messages');
  if (p[0]==='config') return p.length===2 && p[1]==='ads';
  if (['messages','notifications','friend_requests','friends','payments','groups','stories','reports','feedback','events','pages','lives'].includes(p[0])) return p.length===2;
  if (['posts','reels'].includes(p[0])) return p.length===2 || (p.length===4 && ['comments','reactions'].includes(p[2]));
  return true;
}
export function supportedCollectionPath(path) {
  const p=path.split('/');
  if (!SUPPORTED.has(p[0]) || p[0]==='config') return false;
  if (p[0]==='lives') return p.length===1 || (p.length===3 && p[2]==='chat');
  if (p[0]==='chats') return p.length===1 || (p.length===3 && p[2]==='messages');
  if (['messages','notifications','friend_requests','friends','payments','groups','stories','reports','feedback','events','pages','lives'].includes(p[0])) return p.length===1;
  if (['posts','reels'].includes(p[0])) return p.length===1 || (p.length===3 && ['comments','reactions'].includes(p[2]));
  return true;
}
export async function contentReadAllowed(db, uid, path, requestUserReads, includeArchivedOwner = false) {
  const p=path.split('/');
  if (p[0]==='lives') return authorizeLiveRoom(db,p[1],uid);
  if (!['posts','reels','stories'].includes(p[0])) return true;
  return authorizePost({uid,db},p[0],p[1],undefined,undefined,requestUserReads,includeArchivedOwner);
}
export function contentWriteAllowed(path, uid, before, after) {
  const p=path.split('/');
  if (p[0]==='lives') return p.length===2 && !!after && after.hostId===uid
    && (!before ? (after.likesCount===undefined || after.likesCount===0) : (before.hostId===uid && before.status==='live' && after.likesCount===before.likesCount))
    && (after.status==='live' || (!!before && after.status==='ended'))
    && ['public','friends'].includes(after.privacy)
    && !['liveFrameB64','audioB64','coFrameB64','coAudioB64'].some(key=>after[key]);
  if (p[0]==='events' || p[0]==='pages') {
    if (p.length!==2) return false;
    const page=p[0]==='pages';
    const owner=page ? 'ownerId' : 'hostId';
    const members=page ? 'followers' : 'interested';
    if (!before) return !!after && after[owner]===uid
      && isSelfArrayChange([],after[members] ?? [],uid)
      && (!page || after.tickLevel===undefined || after.tickLevel==='none');
    if (!after) return before[owner]===uid;
    if (after[owner]!==before[owner] || (page && after.tickLevel!==before.tickLevel)) return false;
    if (!isSelfArrayChange(before[members] ?? [],after[members] ?? [],uid)) return false;
    if (before[owner]===uid) return true;
    return [...new Set([...Object.keys(before),...Object.keys(after)])]
      .every(key=>key===members || JSON.stringify(before[key])===JSON.stringify(after[key]));
  }
  if (p[0]==='stories') {
    // Viewer counters/reactions need a dedicated atomic transition; do not let a
    // viewer overwrite another person's story through the generic document API.
    if (p.length!==2) return false;
    if (!before) return !!after && after.authorId===uid;
    return before.authorId===uid && (!after || after.authorId===uid);
  }
  if (p[0]!=='posts' && p[0]!=='reels') return true;
  // Reactions and comments have their own authenticated engagement API.
  if (p.length!==2) return false;
  // Group post membership and moderation must be checked atomically with the write.
  // Keep generic writes closed until that dedicated transition is available.
  if (before?.groupId || after?.groupId) return false;
  if (!before) return !!after && after.authorId===uid;
  if (before.authorId!==uid) return false;
  return !after || after.authorId===uid;
}
