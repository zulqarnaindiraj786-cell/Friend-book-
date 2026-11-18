import { authorizePost } from './firebase-post-access.js';

// Access is denied by default: add a collection only after its read and write policy exists.
const SUPPORTED = new Set(['users','pushDevices','chats','messages','notifications','posts','reels','friend_requests','friends','payments','groups']);
export function supportedDocumentPath(path) {
  const p=path.split('/');
  if (!SUPPORTED.has(p[0])) return false;
  if (['messages','notifications','friend_requests','friends','payments','groups'].includes(p[0])) return p.length===2;
  if (['posts','reels'].includes(p[0])) return p.length===2 || (p.length===4 && ['comments','reactions'].includes(p[2]));
  return true;
}
export function supportedCollectionPath(path) {
  const p=path.split('/');
  if (!SUPPORTED.has(p[0])) return false;
  if (['messages','notifications','friend_requests','friends','payments','groups'].includes(p[0])) return p.length===1;
  if (['posts','reels'].includes(p[0])) return p.length===1 || (p.length===3 && ['comments','reactions'].includes(p[2]));
  return true;
}
export async function contentReadAllowed(db, uid, path) {
  const p=path.split('/');
  if (p[0]!=='posts' && p[0]!=='reels') return true;
  return authorizePost({uid,db},p[0],p[1]);
}
export function contentWriteAllowed(path, uid, before, after) {
  const p=path.split('/');
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
